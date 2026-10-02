/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.update

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.utils.toast
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks `{homeserver}/element-classic/update.json`, downloads the APK when a newer version
 * is published, verifies SHA-256, then prompts the user to install.
 */
@Singleton
class AppUpdateManager @Inject constructor(
        @ApplicationContext private val context: Context,
        private val activeSessionHolder: ActiveSessionHolder,
        private val clock: Clock,
        @DefaultPreferences private val preferences: SharedPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checking = AtomicBoolean(false)
    private var lastPromptedVersionCode: Long = -1L
    private var downloadingDialog: Dialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var lastProgressUiMs: Long = 0L

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()

    fun onHomeResumed(activity: FragmentActivity) {
        if (!checking.compareAndSet(false, true)) return
        val activityRef = WeakReference(activity)
        scope.launch {
            try {
                val localVersion = withContext(Dispatchers.IO) { currentVersionCode() }
                val cached = withContext(Dispatchers.IO) { readCachedPrepared(localVersion) }
                val host = activityRef.get() ?: return@launch
                if (host.isFinishing || host.isDestroyed) return@launch

                if (cached != null) {
                    if (lastPromptedVersionCode != cached.manifest.versionCode) {
                        lastPromptedVersionCode = cached.manifest.versionCode
                        showInstallDialog(host, cached)
                    }
                    return@launch
                }

                val manifest = withContext(Dispatchers.IO) { fetchNewerManifest(localVersion) } ?: return@launch
                if (lastPromptedVersionCode == manifest.versionCode) return@launch
                if (!askToDownload(host, manifest)) {
                    lastPromptedVersionCode = manifest.versionCode
                    preferences.edit { putLong(PREF_LAST_CHECK_MS, clock.epochMillis() - CHECK_INTERVAL_MS + REMIND_DELAY_MS) }
                    return@launch
                }

                showProgressDialog(host)
                val apkFile = withContext(Dispatchers.IO) {
                    downloadAndVerify(manifest) { read, total ->
                        activityRef.get()?.runOnUiThread { updateProgress(read, total) }
                    }
                }
                dismissProgressDialog()
                val afterHost = activityRef.get()
                if (afterHost == null || afterHost.isFinishing || afterHost.isDestroyed) return@launch
                if (apkFile == null) {
                    afterHost.toast(CommonStrings.app_update_download_failed)
                    return@launch
                }
                preferences.edit {
                    putLong(PREF_DOWNLOADED_VERSION_CODE, manifest.versionCode)
                    putString(PREF_DOWNLOADED_APK_PATH, apkFile.absolutePath)
                }
                lastPromptedVersionCode = manifest.versionCode
                showInstallDialog(afterHost, PreparedAppUpdate(manifest, apkFile))
            } catch (failure: Throwable) {
                Timber.w(failure, "App update check failed")
            } finally {
                dismissProgressDialog()
                checking.set(false)
            }
        }
    }

    private fun fetchNewerManifest(localVersion: Long): AppUpdateManifest? {
        val session = activeSessionHolder.getSafeActiveSession() ?: return null
        val now = clock.epochMillis()
        val lastCheck = preferences.getLong(PREF_LAST_CHECK_MS, 0L)
        val lastManifest = preferences.getString(PREF_LAST_MANIFEST_JSON, null)
                ?.let { runCatching { parseManifest(JSONObject(it.trim().removePrefix("\uFEFF"))) }.getOrNull() }
        val knownNewer = lastManifest != null && isRemoteNewer(lastManifest, localVersion)
        val shouldHitNetwork = lastCheck == 0L || (now - lastCheck) >= CHECK_INTERVAL_MS || knownNewer
        if (!shouldHitNetwork) {
            return if (knownNewer) lastManifest else null
        }

        val manifestUrl = buildManifestUrl(session.sessionParams.homeServerConnectionConfig.homeServerUriBase)
                ?: return null
        Timber.i("Checking app update: $manifestUrl (localVersion=$localVersion ${currentVersionName()})")
        val manifest = fetchManifest(manifestUrl) ?: return null
        preferences.edit {
            putLong(PREF_LAST_CHECK_MS, now)
            putString(PREF_LAST_MANIFEST_JSON, manifest.toCacheJson())
        }
        if (!isRemoteNewer(manifest, localVersion)) {
            Timber.i("App is up to date (local=$localVersion ${currentVersionName()}, remote=${manifest.versionCode} ${manifest.versionName})")
            clearCachedApk()
            return null
        }
        return manifest
    }

    private fun readCachedPrepared(localVersion: Long): PreparedAppUpdate? {
        val path = preferences.getString(PREF_DOWNLOADED_APK_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        val json = preferences.getString(PREF_LAST_MANIFEST_JSON, null) ?: return null
        val manifest = parseManifest(JSONObject(json.trim().removePrefix("\uFEFF"))) ?: return null
        if (!isRemoteNewer(manifest, localVersion)) return null
        if (manifest.apkSha256.isNotBlank() && !verifySha256(file, manifest.apkSha256)) {
            file.delete()
            return null
        }
        return PreparedAppUpdate(manifest, file)
    }

    private fun fetchManifest(url: String): AppUpdateManifest? {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Update manifest HTTP ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty().trim().removePrefix("\uFEFF")
            if (body.isBlank()) return null
            return parseManifest(JSONObject(body))
        }
    }

    private fun downloadAndVerify(
            manifest: AppUpdateManifest,
            onProgress: (read: Long, total: Long) -> Unit,
    ): File? {
        val target = File(context.cacheDir, APK_FILE_NAME)
        if (target.exists()) {
            if (manifest.apkSha256.isNotBlank() && verifySha256(target, manifest.apkSha256)) {
                val size = target.length()
                onProgress(size, size)
                return target
            }
            target.delete()
        }

        val request = Request.Builder().url(manifest.apkUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("APK download HTTP ${response.code}")
                return null
            }
            val body = response.body ?: return null
            val total = when {
                body.contentLength() > 0 -> body.contentLength()
                manifest.apkSize != null && manifest.apkSize > 0 -> manifest.apkSize
                else -> -1L
            }
            var read = 0L
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            onProgress(read, if (total > 0) total else read)
        }

        if (manifest.apkSize != null && manifest.apkSize > 0 && target.length() != manifest.apkSize) {
            Timber.w("APK size mismatch: expected=${manifest.apkSize}, actual=${target.length()}")
            target.delete()
            return null
        }
        if (manifest.apkSha256.isNotBlank() && !verifySha256(target, manifest.apkSha256)) {
            Timber.w("APK sha256 mismatch")
            target.delete()
            return null
        }
        return target
    }

    private suspend fun askToDownload(activity: FragmentActivity, manifest: AppUpdateManifest): Boolean {
        val notes = manifest.releaseNotes?.takeIf { it.isNotBlank() }
                ?: activity.getString(CommonStrings.app_update_default_notes)
        val message = activity.getString(CommonStrings.app_update_available_content, manifest.versionName, notes)
        return suspendCancellableCoroutine { cont ->
            val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(CommonStrings.app_update_dialog_title)
                    .setMessage(message)
                    .setPositiveButton(CommonStrings.app_update_download) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
            if (manifest.forceUpdate) {
                builder.setCancelable(false)
            } else {
                builder.setNegativeButton(CommonStrings.action_cancel) { _, _ ->
                    if (cont.isActive) cont.resume(false)
                }
                builder.setOnCancelListener {
                    if (cont.isActive) cont.resume(false)
                }
                builder.setCancelable(true)
            }
            val dialog = builder.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    private fun showProgressDialog(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismissProgressDialog()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update_progress, null, false)
        progressBar = view.findViewById(R.id.appUpdateProgressBar)
        progressText = view.findViewById(R.id.appUpdateProgressText)
        progressBar?.isIndeterminate = true
        lastProgressUiMs = 0L
        downloadingDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.app_update_dialog_title)
                .setView(view)
                .setCancelable(false)
                .show()
    }

    private fun updateProgress(read: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressUiMs < 200L && read != total) return
        lastProgressUiMs = now
        val bar = progressBar ?: return
        val text = progressText ?: return
        if (total > 0) {
            bar.isIndeterminate = false
            bar.max = 1000
            bar.progress = ((read * 1000L) / total).toInt().coerceIn(0, 1000)
            val percent = ((read * 100L) / total).toInt().coerceIn(0, 100)
            text.text = text.context.getString(
                    CommonStrings.app_update_progress,
                    formatMegabytes(read),
                    formatMegabytes(total),
                    percent,
            )
        } else {
            bar.isIndeterminate = true
            text.text = text.context.getString(CommonStrings.app_update_downloading) + "  " + formatMegabytes(read)
        }
    }

    private fun dismissProgressDialog() {
        downloadingDialog?.dismiss()
        downloadingDialog = null
        progressBar = null
        progressText = null
    }

    private fun showInstallDialog(activity: FragmentActivity, prepared: PreparedAppUpdate) {
        if (activity.isFinishing) return
        val notes = prepared.manifest.releaseNotes?.takeIf { it.isNotBlank() }
                ?: activity.getString(CommonStrings.app_update_default_notes)
        val message = activity.getString(
                CommonStrings.app_update_dialog_content,
                prepared.manifest.versionName,
                notes,
        )
        val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.app_update_dialog_title)
                .setMessage(message)
                .setPositiveButton(CommonStrings.app_update_install) { _, _ ->
                    installApk(activity, prepared.apkFile)
                }
        if (prepared.manifest.forceUpdate) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton(CommonStrings.action_cancel) { _, _ ->
                // Allow reminding next day / next check window
                lastPromptedVersionCode = -1L
                preferences.edit { putLong(PREF_LAST_CHECK_MS, clock.epochMillis() - CHECK_INTERVAL_MS + REMIND_DELAY_MS) }
            }
            builder.setCancelable(true)
        }
        builder.show()
    }

    private fun installApk(activity: FragmentActivity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                    .setTitle(CommonStrings.app_update_permission_title)
                    .setMessage(CommonStrings.app_update_permission_content)
                    .setPositiveButton(CommonStrings.ok) { _, _ ->
                        val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"),
                        )
                        activity.startActivity(intent)
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
            return
        }

        val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileProvider",
                apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun currentVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName.orEmpty()
        } catch (failure: Throwable) {
            ""
        }
    }

    /**
     * Release APKs append an ABI digit (versionCode * 10 + abi).
     * update.json may publish either the split code or the unsplit gradle versionCode.
     */
    private fun isRemoteNewer(manifest: AppUpdateManifest, localVersion: Long): Boolean {
        val nameCmp = compareVersionNames(manifest.versionName, currentVersionName())
        if (nameCmp > 0) return true
        if (nameCmp < 0) return false
        return normalizeVersionCode(manifest.versionCode) > normalizeVersionCode(localVersion)
    }

    private fun currentVersionCode(): Long {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to read versionCode")
            0L
        }
    }

    private fun buildManifestUrl(homeServerUriBase: Uri): String? {
        val scheme = homeServerUriBase.scheme ?: return null
        val host = homeServerUriBase.host ?: return null
        val port = homeServerUriBase.port
        val authority = if (port != -1) "$host:$port" else host
        return "$scheme://$authority$UPDATE_PATH"
    }

    private fun clearCachedApk() {
        preferences.edit {
            remove(PREF_DOWNLOADED_VERSION_CODE)
            remove(PREF_DOWNLOADED_APK_PATH)
        }
        File(context.cacheDir, APK_FILE_NAME).delete()
    }

    companion object {
        private const val UPDATE_PATH = "/element-classic/update.json"
        private const val APK_FILE_NAME = "element-classic-update.apk"
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
        private const val REMIND_DELAY_MS = 24L * 60L * 60L * 1000L
        private const val PREF_LAST_CHECK_MS = "app_update_last_check_ms"
        private const val PREF_LAST_MANIFEST_JSON = "app_update_last_manifest_json"
        private const val PREF_DOWNLOADED_VERSION_CODE = "app_update_downloaded_version_code"
        private const val PREF_DOWNLOADED_APK_PATH = "app_update_downloaded_apk_path"

        private fun compareVersionNames(remote: String, local: String): Int {
            fun parts(value: String): List<Int> {
                return value.substringBefore("-")
                        .split('.')
                        .mapNotNull { it.toIntOrNull() }
            }
            val r = parts(remote)
            val l = parts(local)
            val n = maxOf(r.size, l.size)
            for (i in 0 until n) {
                val rv = r.getOrElse(i) { 0 }
                val lv = l.getOrElse(i) { 0 }
                if (rv != lv) return rv.compareTo(lv)
            }
            return 0
        }

        private fun normalizeVersionCode(code: Long): Long {
            // Split APKs use ~8-digit codes (base * 10 + abi). Unsplit gradle codes are ~7 digits.
            return if (code >= 10_000_000L) code / 10L else code
        }

        private fun formatMegabytes(bytes: Long): String {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }

        fun parseManifest(json: JSONObject): AppUpdateManifest? {
            return try {
                val apkObject = json.optJSONObject("apk")
                val apks = json.optJSONObject("apks")
                val preferred = selectApk(apks) ?: apkObject
                val apkUrl = preferred?.optString("url").orEmpty().ifBlank {
                    json.optString("apkUrl")
                }
                val apkSha256 = preferred?.optString("sha256").orEmpty().ifBlank {
                    json.optString("apkSha256")
                }
                val apkSize = when {
                    preferred?.has("size") == true -> preferred.optLong("size")
                    json.has("apkSize") -> json.optLong("apkSize")
                    else -> null
                }
                if (apkUrl.isBlank()) return null
                AppUpdateManifest(
                        versionName = json.getString("versionName"),
                        versionCode = json.getLong("versionCode"),
                        forceUpdate = json.optBoolean("forceUpdate", false),
                        releaseNotes = json.optString("releaseNotes").takeIf { it.isNotBlank() },
                        apkUrl = apkUrl,
                        apkSha256 = apkSha256,
                        apkSize = apkSize,
                )
            } catch (failure: Throwable) {
                Timber.e(failure, "Invalid update.json")
                null
            }
        }

        private fun selectApk(apks: JSONObject?): JSONObject? {
            if (apks == null) return null
            Build.SUPPORTED_ABIS.orEmpty().forEach { abi ->
                apks.optJSONObject(abi)?.let { return it }
            }
            return apks.optJSONObject("universal")
                    ?: apks.optJSONObject("arm64-v8a")
                    ?: apks.optJSONObject("armeabi-v7a")
        }

        private fun AppUpdateManifest.toCacheJson(): String {
            return JSONObject()
                    .put("versionName", versionName)
                    .put("versionCode", versionCode)
                    .put("forceUpdate", forceUpdate)
                    .put("releaseNotes", releaseNotes)
                    .put("apkUrl", apkUrl)
                    .put("apkSha256", apkSha256)
                    .put("apkSize", apkSize)
                    .toString()
        }

        fun verifySha256(file: File, expected: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(expected.trim(), ignoreCase = true)
        }
    }
}
