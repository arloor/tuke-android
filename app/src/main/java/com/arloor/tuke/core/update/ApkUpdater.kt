package com.arloor.tuke.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.arloor.tuke.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ApkUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder().build()

    private fun pendingPrefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pendingInstallApk(): File? {
        val path = pendingPrefs().getString(KEY_PENDING_APK, null) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            clearPendingInstall()
            return null
        }
        return file
    }

    suspend fun downloadApk(
        info: AppReleaseCheckResult,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val asset = info.apkAsset ?: throw IllegalStateException("未找到 APK 附件")
        val safeName = File(asset.name).name
            .takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "release-${info.latestVersion}.apk"
        val updateDir = UpdateApkCleaner.ensureUpdateDir(appContext)
        val outputFile = File(updateDir, safeName)
        val partialFile = File(updateDir, "$safeName.part")
        outputFile.delete()
        partialFile.delete()

        try {
            val request = Request.Builder().url(asset.browserDownloadUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("下载失败：响应为空")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: asset.size
                body.byteStream().use { input ->
                    partialFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        var lastProgressAt = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= 200L || downloadedBytes == totalBytes) {
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(downloadedBytes, totalBytes)
                                }
                                lastProgressAt = now
                            }
                        }
                    }
                }
            }
            if (partialFile.length() <= 0L) throw IllegalStateException("下载失败：文件为空")
            if (!partialFile.renameTo(outputFile)) {
                throw IllegalStateException("下载失败：无法保存 APK")
            }
            outputFile
        } catch (error: Throwable) {
            partialFile.delete()
            throw error
        }
    }

    fun launchInstall(apkFile: File): ApkInstallLaunchResult = try {
        if (needsUnknownSourcePermission()) {
            val intent = unknownSourceSettingsIntent()
                ?: return ApkInstallLaunchResult.Failed("未找到“安装未知应用”设置页")
            savePendingInstall(apkFile)
            ApkInstallLaunchResult.NeedUnknownSourcePermission(intent)
        } else {
            clearPendingInstall()
            startInstaller(apkFile)
        }
    } catch (error: Throwable) {
        installFailure(error)
    }

    fun resumePendingInstallIfPossible(): ApkInstallLaunchResult? {
        val apkFile = pendingInstallApk() ?: return null
        if (needsUnknownSourcePermission()) return null
        clearPendingInstall()
        return startInstaller(apkFile)
    }

    private fun startInstaller(apkFile: File): ApkInstallLaunchResult = try {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when {
            canResolveActivity(viewIntent) -> {
                appContext.startActivity(viewIntent)
                ApkInstallLaunchResult.InstallerOpened
            }
            else -> ApkInstallLaunchResult.Failed("系统未找到可处理 APK 安装的应用")
        }
    } catch (error: Throwable) {
        installFailure(error)
    }

    private fun needsUnknownSourcePermission(): Boolean =
        !appContext.packageManager.canRequestPackageInstalls()

    private fun unknownSourceSettingsIntent(): Intent? {
        val direct = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        )
        if (canResolveActivity(direct)) return direct
        return Intent(Settings.ACTION_SECURITY_SETTINGS).takeIf(::canResolveActivity)
    }

    private fun canResolveActivity(intent: Intent): Boolean =
        intent.resolveActivity(appContext.packageManager) != null

    private fun savePendingInstall(apkFile: File) {
        pendingPrefs().edit().putString(KEY_PENDING_APK, apkFile.absolutePath).apply()
    }

    private fun clearPendingInstall() {
        pendingPrefs().edit().remove(KEY_PENDING_APK).apply()
    }

    private fun installFailure(error: Throwable): ApkInstallLaunchResult.Failed {
        val fallback = when (error) {
            is ActivityNotFoundException -> "系统未找到安装器"
            is IllegalArgumentException -> "FileProvider 配置异常"
            is SecurityException -> "缺少安装权限"
            else -> "未知错误"
        }
        return ApkInstallLaunchResult.Failed(error.message ?: fallback)
    }

    private companion object {
        const val PREFS_NAME = "apk_updater"
        const val KEY_PENDING_APK = "pending_install_apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

sealed class ApkInstallLaunchResult {
    data object InstallerOpened : ApkInstallLaunchResult()
    data class NeedUnknownSourcePermission(val settingsIntent: Intent) : ApkInstallLaunchResult()
    data class Failed(val reason: String) : ApkInstallLaunchResult()
}
