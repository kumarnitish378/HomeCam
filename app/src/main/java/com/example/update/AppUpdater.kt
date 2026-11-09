package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Over-the-air updates from the app's GitHub Releases.
 *
 * 1. [checkForUpdate] reads the newest releases and picks the first one with an .apk asset
 *    whose version is newer than this build (pre-releases count, drafts do not).
 * 2. [download] saves the APK into the app cache, reporting progress.
 * 3. [verifySignature] refuses an APK not signed with the same key as the installed app,
 *    so a tampered file can't be installed and a key mismatch gets a clear message.
 * 4. [install] opens Android's installer; the user taps Update to confirm.
 */
object AppUpdater {

    private const val OWNER = "kumarnitish378"
    private const val REPO = "HomeCam"
    private const val RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    private const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/$OWNER/$REPO/releases/download/"

    data class UpdateInfo(
        val versionName: String,
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String
    )

    sealed class VerifyResult {
        data object Ok : VerifyResult()
        data object KeyMismatch : VerifyResult()
        data object WrongPackage : VerifyResult()
        data object Unreadable : VerifyResult()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the newest update, or null when this build is up to date. Throws on network errors. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HomeCam-Android")
            .build()
        val body = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

        val releases = JSONArray(body)
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            if (rel.optBoolean("draft")) continue
            val assets = rel.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var apkSize = 0L
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val url = a.optString("browser_download_url")
                if (a.optString("name").endsWith(".apk", ignoreCase = true) &&
                    url.startsWith(ALLOWED_DOWNLOAD_PREFIX)
                ) {
                    apkUrl = url
                    apkSize = a.optLong("size")
                    break
                }
            }
            if (apkUrl == null) continue // release still building, or no APK

            val tag = rel.optString("tag_name")
            val version = tag.removePrefix("v").removePrefix("V")
            // Releases are newest first: the first one with an APK is the latest.
            return@withContext if (isNewer(version, currentVersion)) {
                UpdateInfo(
                    versionName = version,
                    tag = tag,
                    notes = rel.optString("body").trim(),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    pageUrl = rel.optString("html_url")
                )
            } else null
        }
        null
    }

    /** Downloads the APK to cache/updates/, calling [onProgress] with 0..1. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // drop older downloads
        val target = File(dir, "HomeCam-${info.tag}.apk")
        val partial = File(dir, target.name + ".part")

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "HomeCam-Android")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (!partial.renameTo(target)) throw IOException("Could not save update file")
        target
    }

    /** Checks the downloaded APK is this app and signed with the same certificate. */
    fun verifySignature(context: Context, apk: File): VerifyResult {
        val pm = context.packageManager
        val archive = getArchiveInfo(pm, apk.absolutePath) ?: return VerifyResult.Unreadable
        if (archive.packageName != context.packageName) return VerifyResult.WrongPackage
        val installed = try {
            getInstalledInfo(pm, context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return VerifyResult.Unreadable
        }
        val a = certDigests(archive)
        val b = certDigests(installed)
        return if (a.isNotEmpty() && a == b) VerifyResult.Ok else VerifyResult.KeyMismatch
    }

    /** True when the user still has to allow "Install unknown apps" for HomeCam. */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the APK to Android's package installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Compares dotted versions like 1.0.10 vs 1.0.9. A non-numeric current version is treated as oldest. */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parse(candidate) ?: return false
        val cur = parse(current) ?: return true
        for (i in 0 until maxOf(c.size, cur.size)) {
            val x = c.getOrElse(i) { 0 }
            val y = cur.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(path, flags)
    }

    @Suppress("DEPRECATION")
    private fun getInstalledInfo(pm: PackageManager, pkg: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(pkg, flags)
    }

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}
