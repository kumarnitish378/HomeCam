package com.example.update

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: AppUpdater.UpdateInfo) : UpdateState()
    data class Downloading(val info: AppUpdater.UpdateInfo, val progress: Float) : UpdateState()
    data class Ready(val info: AppUpdater.UpdateInfo, val apk: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * Shows the update flow as dialogs.
 *
 * Checks automatically once when first shown (quietly: only speaks up if an update exists),
 * and again every time [manualCheckRequest] increases (then it also says "up to date" or errors).
 */
@Composable
fun UpdateHost(manualCheckRequest: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun check(manual: Boolean) {
        if (state is UpdateState.Downloading) return
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val info = AppUpdater.checkForUpdate()
                when {
                    info != null -> UpdateState.Available(info)
                    manual -> {
                        Toast.makeText(
                            context,
                            "HomeCam ${AppUpdater.currentVersion} is up to date",
                            Toast.LENGTH_SHORT
                        ).show()
                        UpdateState.Idle
                    }
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Couldn't check for updates: ${e.message}") else UpdateState.Idle
            }
        }
    }

    fun startDownload(info: AppUpdater.UpdateInfo) {
        state = UpdateState.Downloading(info, 0f)
        scope.launch {
            state = try {
                val apk = AppUpdater.download(context, info) { p ->
                    state = UpdateState.Downloading(info, p)
                }
                when (AppUpdater.verifySignature(context, apk)) {
                    AppUpdater.VerifyResult.KeyMismatch -> UpdateState.Failed(
                        "This update is signed with a different key than the installed app, so Android " +
                            "won't install it over this version. Uninstall HomeCam and install " +
                            "${info.tag} from the GitHub release page once; after that, updates work here."
                    )
                    AppUpdater.VerifyResult.WrongPackage -> UpdateState.Failed(
                        "The downloaded file is not a HomeCam update."
                    )
                    // Unreadable: Android's installer still enforces the signature itself.
                    else -> UpdateState.Ready(info, apk)
                }
            } catch (e: Exception) {
                UpdateState.Failed("Download failed: ${e.message}")
            }
        }
    }

    fun install(apk: File) {
        if (AppUpdater.needsInstallPermission(context)) {
            Toast.makeText(
                context,
                "Allow HomeCam to install apps, then come back and tap Install",
                Toast.LENGTH_LONG
            ).show()
            AppUpdater.openInstallPermissionSettings(context)
        } else {
            AppUpdater.install(context, apk)
        }
    }

    // Quiet automatic check once per app start
    LaunchedEffect(Unit) { check(manual = false) }
    // Manual checks
    LaunchedEffect(manualCheckRequest) {
        if (manualCheckRequest > 0) check(manual = true)
    }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text("Update available: ${s.info.tag}") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("You have ${AppUpdater.currentVersion}. Size ${s.info.apkSize / (1024 * 1024)} MB.")
                    if (s.info.notes.isNotBlank()) {
                        Text(
                            s.info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "On a camera phone, open HomeCam and start the camera again after updating.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { startDownload(s.info) }) { Text("Download & install") }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text("Later") }
            }
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading ${s.info.tag}") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(s.progress * 100).toInt()}%")
                }
            },
            confirmButton = { }
        )

        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text("Ready to install ${s.info.tag}") },
            text = { Text("Android will ask you to confirm. Tap Update there.") },
            confirmButton = {
                TextButton(onClick = { install(s.apk) }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text("Later") }
            }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text("Update") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text("OK") }
            }
        )

        else -> Unit
    }
}
