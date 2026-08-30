package com.arloor.tuke.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectState
import com.arloor.tuke.BuildConfig
import com.arloor.tuke.core.update.ApkInstallLaunchResult
import com.arloor.tuke.core.update.ApkUpdater
import com.arloor.tuke.core.update.AppReleaseCheckResult
import com.arloor.tuke.core.update.UpdateApkCleaner
import com.arloor.tuke.core.util.formatBytes
import com.arloor.tuke.core.util.openUrl
import com.arloor.tuke.ui.BannerTone
import com.arloor.tuke.ui.NoticeBanner
import com.arloor.tuke.ui.PageHorizontalPadding
import com.arloor.tuke.ui.SectionCard
import com.arloor.tuke.ui.SpacingMd
import com.arloor.tuke.ui.SpacingSm
import com.arloor.tuke.ui.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
) {
    val uiState by viewModel.uiState.collectState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val apkUpdater = remember { ApkUpdater(context) }
    var downloadingApk by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var downloadTotalBytes by remember { mutableLongStateOf(0L) }
    var updateActionError by remember { mutableStateOf<String?>(null) }
    var updateActionMessage by remember { mutableStateOf<String?>(null) }

    fun onInstallLaunchResult(result: ApkInstallLaunchResult?) {
        when (result) {
            null -> Unit
            ApkInstallLaunchResult.InstallerOpened -> {
                updateActionError = null
                updateActionMessage = "下载完成，已拉起安装页面"
            }
            is ApkInstallLaunchResult.NeedUnknownSourcePermission -> {
                updateActionError = null
                updateActionMessage = "请允许安装未知应用，返回后将自动继续安装"
            }
            is ApkInstallLaunchResult.Failed -> {
                updateActionError = "无法拉起安装页面：${result.reason}"
                updateActionMessage = null
            }
        }
    }

    fun resumePendingInstallAfterReturning() {
        val immediate = apkUpdater.resumePendingInstallIfPossible()
        if (immediate != null) {
            onInstallLaunchResult(immediate)
            return
        }
        if (apkUpdater.pendingInstallApk() == null) return
        updateActionError = null
        updateActionMessage = "请允许安装未知应用，返回后将自动继续安装"
        coroutineScope.launch {
            delay(400)
            onInstallLaunchResult(apkUpdater.resumePendingInstallIfPossible())
        }
    }

    val unknownSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        resumePendingInstallAfterReturning()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumePendingInstallAfterReturning()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        UpdateApkCleaner.cleanupDownloadedApks(context, apkUpdater.pendingInstallApk())
        resumePendingInstallAfterReturning()
    }

    fun startDownloadAndInstall(info: AppReleaseCheckResult) {
        if (downloadingApk) return
        if (info.apkAsset == null) {
            openUrl(context, info.releasePageUrl)
            return
        }
        coroutineScope.launch {
            downloadingApk = true
            downloadedBytes = 0L
            downloadTotalBytes = info.apkAsset.size
            updateActionError = null
            updateActionMessage = null
            val apkFile = runCatching {
                apkUpdater.downloadApk(info) { downloaded, total ->
                    downloadedBytes = downloaded
                    downloadTotalBytes = total
                }
            }.getOrElse { error ->
                updateActionError = error.message ?: "下载 APK 失败"
                downloadingApk = false
                return@launch
            }
            val launchResult = apkUpdater.launchInstall(apkFile)
            onInstallLaunchResult(launchResult)
            if (launchResult is ApkInstallLaunchResult.NeedUnknownSourcePermission) {
                unknownSourceLauncher.launch(launchResult.settingsIntent)
            }
            downloadingApk = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(SpacingMd),
    ) {
        Spacer(modifier = Modifier.height(SpacingSm))
        Text("设置", style = MaterialTheme.typography.titleLarge)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingSm)) {
                Text("DeepSeek", style = MaterialTheme.typography.titleMedium)
                Text(
                    "API Key 只保存在本机。对话、会话和附件都在设备上处理，不经过托管账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::setApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL（可选）") },
                    placeholder = { Text("https://api.deepseek.com") },
                    singleLine = true,
                )
                Button(onClick = viewModel::save, enabled = uiState.apiKey.isNotBlank()) {
                    Text(if (uiState.saved) "已保存" else "保存并启动引擎")
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingSm)) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        val updateInfo = uiState.updateInfo
                        Text(
                            text = when {
                                uiState.checkingUpdate -> "正在检查 GitHub Release…"
                                !uiState.updateError.isNullOrBlank() -> "检查失败"
                                updateInfo?.hasUpdate == true ->
                                    "发现 ${updateInfo.latestVersion} (${updateInfo.latestVersionCode ?: "-"})"
                                updateInfo != null -> "已是最新版本"
                                else -> "启动及回到前台时自动检查"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    if (uiState.checkingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            enabled = !downloadingApk,
                            onClick = {
                                updateActionError = null
                                updateActionMessage = null
                                viewModel.checkUpdate()
                            },
                        ) { Text("检查更新") }
                    }
                }

                val statusText = updateActionError ?: uiState.updateError ?: updateActionMessage
                if (statusText != null) {
                    NoticeBanner(
                        text = statusText,
                        tone = if (updateActionError != null || uiState.updateError != null) {
                            BannerTone.Error
                        } else {
                            BannerTone.Info
                        },
                    )
                }

                if (downloadingApk) {
                    val progress = if (downloadTotalBytes > 0L) {
                        (downloadedBytes.toFloat() / downloadTotalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    if (downloadTotalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(99.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(99.dp)),
                        )
                    }
                    Text(
                        text = buildDownloadProgressText(downloadedBytes, downloadTotalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }

                uiState.updateInfo?.takeIf { it.hasUpdate }?.let { updateInfo ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !downloadingApk,
                        onClick = { startDownloadAndInstall(updateInfo) },
                    ) {
                        val asset = updateInfo.apkAsset
                        Text(
                            when {
                                asset == null -> "打开更新说明"
                                downloadingApk && downloadTotalBytes > 0L -> {
                                    val percent = (downloadedBytes * 100.0 / downloadTotalBytes)
                                        .roundToInt()
                                        .coerceIn(0, 100)
                                    "下载中 $percent%"
                                }
                                downloadingApk -> "下载中…"
                                asset.size > 0L -> "下载并安装（${formatBytes(asset.size)}）"
                                else -> "下载并安装"
                            },
                        )
                    }
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingSm)) {
                Text("后台保活", style = MaterialTheme.typography.titleMedium)
                Text(
                    "生成回答时会显示前台通知。建议允许忽略电池优化，避免切到其他应用后被系统杀掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(intent)
                    }
                }) { Text("允许忽略电池优化") }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun buildDownloadProgressText(downloadedBytes: Long, totalBytes: Long): String {
    return if (totalBytes > 0L) {
        val percent = (downloadedBytes * 100.0 / totalBytes).roundToInt().coerceIn(0, 100)
        "下载中：${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}（$percent%）"
    } else {
        "下载中：${formatBytes(downloadedBytes)}"
    }
}
