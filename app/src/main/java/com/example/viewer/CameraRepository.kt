package com.example.viewer

import com.example.core.NetUtils
import com.example.core.SettingsRepository
import com.example.core.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class CameraItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8080,
    val token: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** Internet (IPv6/HTTPS) address: DuckDNS name or IPv6 literal; blank if not set up */
    val remoteHost: String = "",
    val remotePort: Int = 8443,
    /** Pinned SHA-256 of the camera's HTTPS certificate */
    val certFp: String = ""
) {
    val baseUrl: String
        get() = "http://${NetUtils.hostForUrl(host)}:$port"

    val streamUrl: String
        get() = "$baseUrl/stream.mjpg"

    val snapshotUrl: String
        get() = "$baseUrl/snapshot.jpg"

    val statusUrl: String
        get() = "$baseUrl/status"

    val hasRemote: Boolean
        get() = remoteHost.isNotBlank() && certFp.isNotBlank()

    val remoteBaseUrl: String
        get() = "https://${NetUtils.hostForUrl(remoteHost)}:$remotePort"

    val isTailscale: Boolean
        get() {
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }
}

/** How the viewer is currently reaching a camera. */
enum class Route(val label: String) {
    LAN("Home Wi-Fi"),
    TAILSCALE("Tailscale"),
    INTERNET("Internet (IPv6)")
}

/** One way to reach a camera: a base URL plus the HTTP client to use for it. */
data class Endpoint(
    val baseUrl: String,
    val route: Route,
    val client: OkHttpClient
) {
    /** Same client with timeouts suited to a long-running MJPEG stream. */
    fun streamClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS) // no frame for 10 s = dropped connection
        .build()
}

data class CameraStatus(
    val isOnline: Boolean = false,
    val fps: Int = 0,
    val viewers: Int = 0,
    val lastMotion: Long = 0L,
    val battery: Int = 0,
    val temperature: Float = 0f,
    val isCharging: Boolean = false,
    val isHeatThrottled: Boolean = false,
    val torchOn: Boolean = false,
    val lensFacing: String = "BACK",
    val motionEnabled: Boolean = true,
    val route: Route? = null,
    val errorMessage: String? = null
)

/** Motion detection settings of a camera, as served by /config/motion. */
data class MotionConfig(
    val enabled: Boolean = true,
    /** Fraction of the image that must change to trigger (0.02 = 2%). */
    val sensitivity: Float = 0.02f,
    val cooldownSec: Int = 30
)

class CameraRepository(private val settingsRepo: SettingsRepository) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Endpoint that last worked for each camera id, tried first next time. */
    private val activeEndpoint = ConcurrentHashMap<String, Endpoint>()
    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    val cameras: Flow<List<CameraItem>> = settingsRepo.savedCamerasJson.map { json ->
        parseCamerasJson(json)
    }

    suspend fun addCamera(camera: CameraItem) = withContext(Dispatchers.IO) {
        val current = cameras.first().toMutableList()
        // Replace if exists, else append
        val index = current.indexOfFirst { it.id == camera.id }
        if (index >= 0) {
            current[index] = camera
        } else {
            current.add(camera)
        }
        saveCameras(current)
    }

    suspend fun removeCamera(id: String) = withContext(Dispatchers.IO) {
        val current = cameras.first().filterNot { it.id == id }
        activeEndpoint.remove(id)
        saveCameras(current)
    }

    suspend fun updateCameraName(id: String, newName: String) = withContext(Dispatchers.IO) {
        val current = cameras.first().map {
            if (it.id == id) it.copy(name = newName) else it
        }
        saveCameras(current)
    }

    private suspend fun saveCameras(list: List<CameraItem>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("host", item.host)
                put("port", item.port)
                put("token", item.token)
                put("addedAt", item.addedAt)
                put("remoteHost", item.remoteHost)
                put("remotePort", item.remotePort)
                put("certFp", item.certFp)
            }
            array.put(obj)
        }
        settingsRepo.saveCamerasJson(array.toString())
    }

    private fun parseCamerasJson(json: String): List<CameraItem> {
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<CameraItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CameraItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "HomeCam"),
                        host = obj.optString("host", "127.0.0.1"),
                        port = obj.optInt("port", 8080),
                        token = obj.optString("token", ""),
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                        remoteHost = obj.optString("remoteHost", ""),
                        remotePort = obj.optInt("remotePort", 8443),
                        certFp = obj.optString("certFp", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The saved copy of [camera] (it may have learned a new internet address since). */
    private suspend fun latest(camera: CameraItem): CameraItem =
        cameras.first().firstOrNull { it.id == camera.id } ?: camera

    /** All ways to reach [camera]: the last one that worked first, then LAN/Tailscale, then internet. */
    private fun endpointsFor(camera: CameraItem): List<Endpoint> {
        val list = mutableListOf(
            Endpoint(camera.baseUrl, if (camera.isTailscale) Route.TAILSCALE else Route.LAN, httpClient)
        )
        if (camera.hasRemote) {
            list.add(Endpoint(camera.remoteBaseUrl, Route.INTERNET, pinnedClient(camera.certFp)))
        }
        val cached = activeEndpoint[camera.id]
        return if (cached != null && list.any { it.baseUrl == cached.baseUrl }) {
            listOf(cached) + list.filterNot { it.baseUrl == cached.baseUrl }
        } else list
    }

    /** Endpoint for one-off commands: the one that last worked, else the first candidate. */
    private fun currentEndpoint(camera: CameraItem): Endpoint =
        activeEndpoint[camera.id] ?: endpointsFor(camera).first()

    /**
     * Finds a working endpoint for live streaming (probing /status on each candidate).
     * Returns null when the camera can't be reached any way.
     */
    suspend fun resolveEndpoint(camera: CameraItem): Endpoint? {
        val status = fetchStatus(camera)
        return if (status.isOnline) activeEndpoint[camera.id] else null
    }

    suspend fun fetchStatus(camera: CameraItem): CameraStatus = withContext(Dispatchers.IO) {
        val cam = latest(camera)
        var lastError = "Unreachable"
        for (ep in endpointsFor(cam)) {
            try {
                val request = Request.Builder()
                    .url("${ep.baseUrl}/status")
                    .header("Authorization", "Bearer ${cam.token}")
                    .get()
                    .build()
                val result = ep.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string().orEmpty()
                    } else {
                        lastError = "HTTP ${response.code}"
                        null
                    }
                } ?: continue

                val json = JSONObject(result)
                activeEndpoint[cam.id] = ep
                learnRemoteAddress(cam, json)
                return@withContext CameraStatus(
                    isOnline = true,
                    fps = json.optInt("fps", 0),
                    viewers = json.optInt("viewers", 0),
                    lastMotion = json.optLong("lastMotion", 0L),
                    battery = json.optInt("battery", 0),
                    temperature = json.optDouble("temperature", 0.0).toFloat(),
                    isCharging = json.optBoolean("isCharging", false),
                    isHeatThrottled = json.optBoolean("isHeatThrottled", false),
                    torchOn = json.optBoolean("torchOn", false),
                    lensFacing = json.optString("lensFacing", "BACK"),
                    motionEnabled = json.optBoolean("motionEnabled", true),
                    route = ep.route
                )
            } catch (e: Exception) {
                lastError = e.message ?: "Unreachable"
            }
        }
        activeEndpoint.remove(cam.id)
        CameraStatus(isOnline = false, errorMessage = lastError)
    }

    /**
     * Keeps the saved internet address current: whenever the camera reports a new IPv6
     * (e.g. the router got a new prefix) or turns internet access on, store it.
     */
    private suspend fun learnRemoteAddress(cam: CameraItem, json: JSONObject) {
        if (!json.optBoolean("remoteEnabled", false)) return
        val host = json.optString("remoteHost", "")
        val fp = json.optString("certFp", "").lowercase()
        val port = json.optInt("remotePort", 8443)
        if (host.isBlank() || fp.isBlank()) return
        if (host != cam.remoteHost || fp != cam.certFp || port != cam.remotePort) {
            addCamera(cam.copy(remoteHost = host, remotePort = port, certFp = fp))
        }
    }

    suspend fun sendTorchControl(camera: CameraItem, turnOn: Boolean): Boolean =
        postControl(camera, "/control/torch?on=${if (turnOn) 1 else 0}")

    suspend fun sendLensControl(camera: CameraItem, facing: String): Boolean =
        postControl(camera, "/control/lens?facing=${facing.lowercase()}")

    private suspend fun postControl(camera: CameraItem, pathAndQuery: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val cam = latest(camera)
                val ep = currentEndpoint(cam)
                val request = Request.Builder()
                    .url(ep.baseUrl + pathAndQuery)
                    .header("Authorization", "Bearer ${cam.token}")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                ep.client.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }

    suspend fun fetchMotionConfig(camera: CameraItem): MotionConfig? = withContext(Dispatchers.IO) {
        try {
            val cam = latest(camera)
            val ep = currentEndpoint(cam)
            val request = Request.Builder()
                .url("${ep.baseUrl}/config/motion")
                .header("Authorization", "Bearer ${cam.token}")
                .get()
                .build()
            ep.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) parseMotionConfig(response.body?.string().orEmpty()) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Sends new motion settings; returns the settings the camera now uses, or null on failure. */
    suspend fun updateMotionConfig(camera: CameraItem, config: MotionConfig): MotionConfig? =
        withContext(Dispatchers.IO) {
            try {
                val cam = latest(camera)
                val ep = currentEndpoint(cam)
                val url = "${ep.baseUrl}/config/motion" +
                    "?enabled=${if (config.enabled) 1 else 0}" +
                    "&sensitivity=${config.sensitivity}" +
                    "&cooldown=${config.cooldownSec}"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${cam.token}")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                ep.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) parseMotionConfig(response.body?.string().orEmpty()) else null
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun parseMotionConfig(body: String): MotionConfig? = try {
        val json = JSONObject(body)
        MotionConfig(
            enabled = json.optBoolean("enabled", true),
            sensitivity = json.optDouble("sensitivity", 0.02).toFloat(),
            cooldownSec = json.optInt("cooldownSec", 30)
        )
    } catch (_: Exception) {
        null
    }

    /**
     * HTTPS client that trusts exactly one certificate: the camera's, identified by the
     * SHA-256 fingerprint from pairing. No CA is involved, so the hostname check is not needed.
     * DNS results are ordered IPv6-first, because a DuckDNS name may also carry an
     * unreachable (CGNAT) IPv4 address.
     */
    private fun pinnedClient(fingerprint: String): OkHttpClient = pinnedClients.getOrPut(fingerprint) {
        val expected = fingerprint.lowercase()
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not used")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
                if (TlsIdentity.sha256Hex(leaf.encoded) != expected) {
                    throw CertificateException("Camera certificate does not match pairing")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val ipv6First = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet6Address) 0 else 1 }
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(ipv6First)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
