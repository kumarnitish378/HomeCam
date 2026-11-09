package com.example.viewer

import com.example.core.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CameraItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8080,
    val token: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    val baseUrl: String
        get() = "http://$host:$port"

    val streamUrl: String
        get() = "$baseUrl/stream.mjpg"

    val snapshotUrl: String
        get() = "$baseUrl/snapshot.jpg"

    val statusUrl: String
        get() = "$baseUrl/status"

    val isTailscale: Boolean
        get() {
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }
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
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchStatus(camera: CameraItem): CameraStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(camera.statusUrl)
                .header("Authorization", "Bearer ${camera.token}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string().orEmpty()
                    val json = JSONObject(bodyString)
                    CameraStatus(
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
                        motionEnabled = json.optBoolean("motionEnabled", true)
                    )
                } else {
                    CameraStatus(
                        isOnline = false,
                        errorMessage = "HTTP ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            CameraStatus(
                isOnline = false,
                errorMessage = e.message ?: "Unreachable"
            )
        }
    }

    suspend fun sendTorchControl(camera: CameraItem, turnOn: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${camera.baseUrl}/control/torch?on=${if (turnOn) 1 else 0}"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${camera.token}")
                .post(ByteArray(0).toRequestBody())
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendLensControl(camera: CameraItem, facing: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${camera.baseUrl}/control/lens?facing=${facing.lowercase()}"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${camera.token}")
                .post(ByteArray(0).toRequestBody())
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchMotionConfig(camera: CameraItem): MotionConfig? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${camera.baseUrl}/config/motion")
                .header("Authorization", "Bearer ${camera.token}")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
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
                val url = "${camera.baseUrl}/config/motion" +
                    "?enabled=${if (config.enabled) 1 else 0}" +
                    "&sensitivity=${config.sensitivity}" +
                    "&cooldown=${config.cooldownSec}"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${camera.token}")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                httpClient.newCall(request).execute().use { response ->
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
}
