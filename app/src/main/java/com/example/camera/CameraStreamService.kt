package com.example.camera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.R
import com.example.core.NetUtils
import com.example.core.SettingsRepository
import com.example.core.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Foreground service that runs 24/7 with the screen off, holding CPU wake lock and Wi-Fi lock.
 * Captures frames via CameraX ImageAnalysis, compresses to JPEG, and serves them via MjpegServer.
 */
class CameraStreamService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.homecam.ACTION_START"
        const val ACTION_STOP = "com.example.homecam.ACTION_STOP"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_CAMERA_NAME = "extra_camera_name"
        const val EXTRA_TAILSCALE_ONLY = "extra_tailscale_only"

        private const val STREAM_NOTIFICATION_ID = 1001
        private const val MOTION_NOTIFICATION_ID = 2001
        private const val STREAM_CHANNEL_ID = "homecam_stream_channel"
        private const val MOTION_CHANNEL_ID = "homecam_motion_channel"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var encoder = FrameEncoder(quality = 70, maxFps = 12)
    private var motionDetector: MotionDetector? = null
    private var server: MjpegServer? = null
    private var remoteProxy: RemoteTlsProxy? = null
    private var remoteJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var cameraControl: CameraControl? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var targetResolution = Size(1280, 720)
    private var token: String = ""
    private var port: Int = 8080
    private var cameraName: String = "HomeCam 1"
    private var tailscaleOnly: Boolean = false
    private var settingsRepo: SettingsRepository? = null

    private var batteryReceiver: BroadcastReceiver? = null
    private var notificationUpdateJob: Job? = null
    private var controlCommandsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        cameraName = intent?.getStringExtra(EXTRA_CAMERA_NAME) ?: "HomeCam 1"
        tailscaleOnly = intent?.getBooleanExtra(EXTRA_TAILSCALE_ONLY, false) ?: false

        if (token.isEmpty()) {
            lifecycleScope.launch {
                token = settingsRepo?.getOrCreateToken().orEmpty()
                initService()
            }
        } else {
            initService()
        }

        return START_NOT_STICKY // Camera cannot restart from background without activity
    }

    @SuppressLint("WakelockTimeout") // Held for the whole stream; released in onDestroy()
    private fun initService() {
        // 1. Start foreground immediately
        val notification = buildStreamNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, STREAM_NOTIFICATION_ID, notification, foregroundType)

        // 2. Acquire CPU wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeCam:StreamWakeLock").apply {
            setReferenceCounted(false)
            // No timeout: the camera runs 24x7, so a timed lock would let the CPU
            // sleep after it expires and silently stop the stream.
            acquire()
        }

        // 3. Acquire Wi-Fi lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomeCam:WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        // 4. Register battery & thermal monitor
        registerBatteryMonitor()

        // 5. Initialize motion detector
        motionDetector = MotionDetector(threshold = 0.02f) { file ->
            postMotionNotification(file.name)
        }

        // 6. Start Ktor server
        server = MjpegServer(
            context = applicationContext,
            settingsRepo = settingsRepo,
            port = port,
            token = token,
            cameraName = cameraName,
            tailscaleOnly = tailscaleOnly
        ).also { it.start() }

        FrameBus.isStreaming.value = true

        // 6b. Internet access over IPv6 + HTTPS (optional, no Tailscale needed)
        remoteJob = lifecycleScope.launch { runRemoteAccess() }

        // 7. Observe settings from DataStore
        lifecycleScope.launch {
            settingsRepo?.cameraConfig?.collect { config ->
                encoder.quality = config.jpegQuality
                if (!FrameBus.isHeatThrottled.value) {
                    encoder.maxFps = config.maxFps
                    targetResolution = Size(config.resolutionWidth, config.resolutionHeight)
                }
                motionDetector?.threshold = config.motionSensitivity
                motionDetector?.enabled = config.motionEnabled
                motionDetector?.cooldownMs = config.motionCooldownSec * 1000L
                FrameBus.motionEnabled.value = config.motionEnabled
                currentLensFacing = if (config.lensFacing.equals("FRONT", ignoreCase = true)) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                FrameBus.lensFacing.value = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"
            }
        }

        // 8. Bind camera
        startCamera()

        // 9. Listen for torch and lens control commands
        observeControlCommands()

        // 10. Update notification periodically with viewer count
        notificationUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(5000)
                updateStreamNotification()
            }
        }
    }

    /**
     * Opens the HTTPS port when "Internet access (IPv6)" is on, then every minute refreshes
     * the phone's public IPv6 address and, if configured, the DuckDNS name.
     */
    private suspend fun runRemoteAccess() {
        val config = settingsRepo?.cameraConfig?.first() ?: return
        FrameBus.remoteEnabled.value = config.remoteEnabled
        FrameBus.remotePort.value = config.remotePort
        if (!config.remoteEnabled) {
            FrameBus.remoteHost.value = null
            FrameBus.remoteError.value = null
            return
        }

        val opened = withContext(Dispatchers.IO) {
            try {
                FrameBus.certFingerprint.value = TlsIdentity.fingerprint()
                remoteProxy = RemoteTlsProxy(TlsIdentity.serverContext(), config.remotePort, port)
                    .also { it.start() }
                true
            } catch (e: Exception) {
                FrameBus.remoteError.value = "Couldn't open HTTPS port ${config.remotePort}: ${e.message}"
                false
            }
        }
        if (!opened) return

        val useDuck = config.duckDomain.isNotBlank() && config.duckToken.isNotBlank()
        var lastSentIpv6: String? = null
        var lastSentAt = 0L
        while (currentCoroutineContextActive()) {
            val ipv6 = withContext(Dispatchers.IO) { NetUtils.findGlobalIpv6(applicationContext) }
            FrameBus.remoteHost.value = when {
                useDuck -> DuckDnsUpdater.fullName(config.duckDomain)
                else -> ipv6
            }
            var error: String? = if (ipv6 == null) {
                "No public IPv6 address on Wi-Fi. Check that the router has IPv6 enabled."
            } else null

            val now = System.currentTimeMillis()
            if (useDuck && ipv6 != null && (ipv6 != lastSentIpv6 || now - lastSentAt > 30 * 60_000L)) {
                val ok = withContext(Dispatchers.IO) {
                    DuckDnsUpdater.update(config.duckDomain, config.duckToken, ipv6)
                }
                if (ok) {
                    lastSentIpv6 = ipv6
                    lastSentAt = now
                } else {
                    error = "DuckDNS update failed. Check the domain and token."
                }
            }
            FrameBus.remoteError.value = error
            delay(60_000)
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                FrameBus.cameraError.value = "Failed to start camera: ${e.message}"
                // Self-healing: retry after 5 seconds
                lifecycleScope.launch {
                    delay(5000)
                    startCamera()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        val effectiveResolution = if (FrameBus.isHeatThrottled.value) {
            Size(640, 480)
        } else {
            targetResolution
        }

        val resolutionStrategy = ResolutionStrategy(
            effectiveResolution,
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(resolutionStrategy)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // YUV is the camera's native format: no RGBA conversion. CameraX rotates the
            // frame for us, so the encoder never copies a Bitmap to rotate it.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setOutputImageRotationEnabled(true)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            imageProxy.use { image ->
                val frame = encoder.encode(image) ?: return@use
                FrameBus.latest.value = frame.jpeg
                motionDetector?.process(frame, applicationContext)
            }
        }

        try {
            activeCamera = provider.bindToLifecycle(this, selector, analysis)
            cameraControl = activeCamera?.cameraControl
            activeCamera?.cameraControl?.enableTorch(FrameBus.torchEnabled.value)
            FrameBus.cameraError.value = null
        } catch (e: Exception) {
            FrameBus.cameraError.value = "Camera binding failed: ${e.message}"
            lifecycleScope.launch {
                delay(5000)
                startCamera()
            }
        }
    }

    private fun observeControlCommands() {
        controlCommandsJob = lifecycleScope.launch {
            launch {
                FrameBus.torchEnabled.collect { torchOn ->
                    try {
                        cameraControl?.enableTorch(torchOn)
                    } catch (_: Exception) {
                    }
                }
            }
            launch {
                FrameBus.controlCommands.collect { cmd ->
                    when {
                        cmd.startsWith("LENS_FRONT") -> {
                            if (currentLensFacing != CameraSelector.LENS_FACING_FRONT) {
                                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                                FrameBus.lensFacing.value = "FRONT"
                                bindCameraUseCases()
                            }
                        }
                        cmd.startsWith("LENS_BACK") -> {
                            if (currentLensFacing != CameraSelector.LENS_FACING_BACK) {
                                currentLensFacing = CameraSelector.LENS_FACING_BACK
                                FrameBus.lensFacing.value = "BACK"
                                bindCameraUseCases()
                            }
                        }
                        cmd == "REBIND_CAMERA" -> {
                            bindCameraUseCases()
                        }
                    }
                }
            }
        }
    }

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                val tempCelsius = tempRaw / 10.0f
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                FrameBus.batteryPercent.value = pct
                FrameBus.batteryTemp.value = tempCelsius
                FrameBus.isCharging.value = isCharging

                // Heat protection: drop to 480p at 5 fps if temp > 42°C
                if (tempCelsius > 42.0f && !FrameBus.isHeatThrottled.value) {
                    FrameBus.isHeatThrottled.value = true
                    encoder.maxFps = 5
                    bindCameraUseCases()
                } else if (tempCelsius < 39.0f && FrameBus.isHeatThrottled.value) {
                    FrameBus.isHeatThrottled.value = false
                    lifecycleScope.launch {
                        val config = settingsRepo?.cameraConfig?.first()
                        if (config != null) {
                            encoder.maxFps = config.maxFps
                            targetResolution = Size(config.resolutionWidth, config.resolutionHeight)
                            bindCameraUseCases()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val streamChannel = NotificationChannel(
                STREAM_CHANNEL_ID,
                "HomeCam Stream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification keeping camera active"
            }
            val motionChannel = NotificationChannel(
                MOTION_CHANNEL_ID,
                "HomeCam Motion Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security alerts when motion is detected"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(streamChannel)
            notificationManager.createNotificationChannel(motionChannel)
        }
    }

    private fun buildStreamNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val viewers = FrameBus.activeViewers.value
        val fps = FrameBus.currentFps.value
        val viewersText = if (viewers == 1) "1 viewer" else "$viewers viewers"
        val throttledText = if (FrameBus.isHeatThrottled.value) " · [Cooling down]" else ""

        return NotificationCompat.Builder(this, STREAM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("HomeCam is streaming")
            .setContentText("$viewersText · $fps fps$throttledText")
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateStreamNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(STREAM_NOTIFICATION_ID, buildStreamNotification())
    }

    private fun postMotionNotification(fileName: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MOTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Motion Detected!")
            .setContentText("Snapshot saved: $fileName")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(MOTION_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        notificationUpdateJob?.cancel()
        controlCommandsJob?.cancel()

        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }

        try {
            activeCamera?.cameraControl?.enableTorch(false)
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        try {
            server?.stop()
            server = null
        } catch (_: Exception) {
        }

        remoteJob?.cancel()
        remoteProxy?.stop()
        remoteProxy = null
        FrameBus.remoteEnabled.value = false
        FrameBus.remoteHost.value = null

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (_: Exception) {
        }

        executor.shutdown()
        FrameBus.isStreaming.value = false
        FrameBus.activeViewers.value = 0

        super.onDestroy()
    }
}
