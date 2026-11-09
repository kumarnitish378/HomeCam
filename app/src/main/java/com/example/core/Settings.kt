package com.example.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "homecam_settings")

/**
 * DataStore repository holding all camera and viewer settings.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_APP_MODE = stringPreferencesKey("app_mode") // "CAMERA", "VIEWER", or "NONE"
        val KEY_CAMERA_NAME = stringPreferencesKey("camera_name")
        val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        val KEY_PORT = intPreferencesKey("server_port")
        val KEY_RESOLUTION_WIDTH = intPreferencesKey("resolution_width")
        val KEY_RESOLUTION_HEIGHT = intPreferencesKey("resolution_height")
        val KEY_MAX_FPS = intPreferencesKey("max_fps")
        val KEY_JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val KEY_LENS_FACING = stringPreferencesKey("lens_facing") // "BACK", "FRONT"
        val KEY_MOTION_SENSITIVITY = floatPreferencesKey("motion_sensitivity") // 0.01f to 0.10f
        val KEY_MOTION_ENABLED = booleanPreferencesKey("motion_enabled")
        val KEY_MOTION_COOLDOWN_SEC = intPreferencesKey("motion_cooldown_sec")
        val KEY_TAILSCALE_ONLY = booleanPreferencesKey("tailscale_only")
        val KEY_REMOTE_ENABLED = booleanPreferencesKey("remote_ipv6_enabled")
        val KEY_REMOTE_PORT = intPreferencesKey("remote_ipv6_port")
        val KEY_DUCK_DOMAIN = stringPreferencesKey("duckdns_domain")
        val KEY_DUCK_TOKEN = stringPreferencesKey("duckdns_token")
        val KEY_RECORDING_ENABLED = booleanPreferencesKey("recording_enabled")
        val KEY_RECORDING_LIMIT_MB = intPreferencesKey("recording_limit_mb")
        val KEY_RECORDING_QUALITY = stringPreferencesKey("recording_quality")
        val KEY_SAVED_CAMERAS_JSON = stringPreferencesKey("saved_cameras_json")
    }

    data class CameraConfig(
        val cameraName: String = "HomeCam 1",
        val authToken: String = "",
        val port: Int = 8080,
        val resolutionWidth: Int = 1280,
        val resolutionHeight: Int = 720,
        val maxFps: Int = 12,
        val jpegQuality: Int = 70,
        val lensFacing: String = "BACK",
        val motionSensitivity: Float = 0.02f,
        val motionEnabled: Boolean = true,
        val motionCooldownSec: Int = 30,
        val tailscaleOnly: Boolean = false,
        /** Internet access over IPv6 + HTTPS, without Tailscale */
        val remoteEnabled: Boolean = false,
        val remotePort: Int = 8443,
        /** Optional DuckDNS name (without .duckdns.org) kept pointing at the phone's IPv6 */
        val duckDomain: String = "",
        val duckToken: String = "",
        /** Continuous local loop recording */
        val recordingEnabled: Boolean = true,
        val recordingLimitMb: Int = 2048,
        val recordingQuality: String = "MEDIUM" // LOW, MEDIUM, HIGH
    )

    val appMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_MODE] ?: "NONE"
    }

    val cameraConfig: Flow<CameraConfig> = context.dataStore.data.map { prefs ->
        CameraConfig(
            cameraName = prefs[KEY_CAMERA_NAME] ?: "HomeCam 1",
            authToken = prefs[KEY_AUTH_TOKEN] ?: "",
            port = prefs[KEY_PORT] ?: 8080,
            resolutionWidth = prefs[KEY_RESOLUTION_WIDTH] ?: 1280,
            resolutionHeight = prefs[KEY_RESOLUTION_HEIGHT] ?: 720,
            maxFps = prefs[KEY_MAX_FPS] ?: 12,
            jpegQuality = prefs[KEY_JPEG_QUALITY] ?: 70,
            lensFacing = prefs[KEY_LENS_FACING] ?: "BACK",
            motionSensitivity = prefs[KEY_MOTION_SENSITIVITY] ?: 0.02f,
            motionEnabled = prefs[KEY_MOTION_ENABLED] ?: true,
            motionCooldownSec = prefs[KEY_MOTION_COOLDOWN_SEC] ?: 30,
            tailscaleOnly = prefs[KEY_TAILSCALE_ONLY] ?: false,
            remoteEnabled = prefs[KEY_REMOTE_ENABLED] ?: false,
            remotePort = prefs[KEY_REMOTE_PORT] ?: 8443,
            duckDomain = prefs[KEY_DUCK_DOMAIN] ?: "",
            duckToken = prefs[KEY_DUCK_TOKEN] ?: "",
            recordingEnabled = prefs[KEY_RECORDING_ENABLED] ?: true,
            recordingLimitMb = prefs[KEY_RECORDING_LIMIT_MB] ?: 2048,
            recordingQuality = prefs[KEY_RECORDING_QUALITY] ?: "MEDIUM"
        )
    }

    val savedCamerasJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAVED_CAMERAS_JSON] ?: "[]"
    }

    suspend fun setAppMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_MODE] = mode
        }
    }

    suspend fun getOrCreateToken(): String {
        val currentToken = context.dataStore.data.map { it[KEY_AUTH_TOKEN] }.first()
        if (!currentToken.isNullOrBlank()) {
            return currentToken
        }
        val newToken = AuthToken.newToken()
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_TOKEN] = newToken
        }
        return newToken
    }

    suspend fun regenerateToken(): String {
        val newToken = AuthToken.newToken()
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_TOKEN] = newToken
        }
        return newToken
    }

    suspend fun updateCameraConfig(
        cameraName: String? = null,
        port: Int? = null,
        width: Int? = null,
        height: Int? = null,
        fps: Int? = null,
        quality: Int? = null,
        lensFacing: String? = null,
        motionSensitivity: Float? = null,
        tailscaleOnly: Boolean? = null,
        motionEnabled: Boolean? = null,
        motionCooldownSec: Int? = null,
        remoteEnabled: Boolean? = null,
        remotePort: Int? = null,
        duckDomain: String? = null,
        duckToken: String? = null,
        recordingEnabled: Boolean? = null,
        recordingLimitMb: Int? = null,
        recordingQuality: String? = null
    ) {
        context.dataStore.edit { prefs ->
            cameraName?.let { prefs[KEY_CAMERA_NAME] = it }
            port?.let { prefs[KEY_PORT] = it }
            width?.let { prefs[KEY_RESOLUTION_WIDTH] = it }
            height?.let { prefs[KEY_RESOLUTION_HEIGHT] = it }
            fps?.let { prefs[KEY_MAX_FPS] = it }
            quality?.let { prefs[KEY_JPEG_QUALITY] = it }
            lensFacing?.let { prefs[KEY_LENS_FACING] = it }
            motionSensitivity?.let { prefs[KEY_MOTION_SENSITIVITY] = it }
            tailscaleOnly?.let { prefs[KEY_TAILSCALE_ONLY] = it }
            motionEnabled?.let { prefs[KEY_MOTION_ENABLED] = it }
            motionCooldownSec?.let { prefs[KEY_MOTION_COOLDOWN_SEC] = it }
            remoteEnabled?.let { prefs[KEY_REMOTE_ENABLED] = it }
            remotePort?.let { prefs[KEY_REMOTE_PORT] = it }
            duckDomain?.let { prefs[KEY_DUCK_DOMAIN] = it }
            duckToken?.let { prefs[KEY_DUCK_TOKEN] = it }
            recordingEnabled?.let { prefs[KEY_RECORDING_ENABLED] = it }
            recordingLimitMb?.let { prefs[KEY_RECORDING_LIMIT_MB] = it }
            recordingQuality?.let { prefs[KEY_RECORDING_QUALITY] = it }
        }
    }

    suspend fun saveCamerasJson(json: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAVED_CAMERAS_JSON] = json
        }
    }
}
