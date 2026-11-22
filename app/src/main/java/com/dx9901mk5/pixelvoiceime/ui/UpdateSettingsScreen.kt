package com.dx9901mk5.pixelvoiceime.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.data.VoiceImeConfig
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import com.dx9901mk5.pixelvoiceime.updater.AutoUpdateWorker
import com.dx9901mk5.pixelvoiceime.updater.GitHubUpdater
import com.dx9901mk5.pixelvoiceime.updater.ReleaseInfo
import com.dx9901mk5.pixelvoiceime.updater.UpdateCheckResult
import kotlinx.coroutines.launch

@Composable
fun UpdateSettingsScreen(
    prefs: VoiceImePrefs,
    updater: GitHubUpdater
) {
    val context = LocalContext.current
    val config by prefs.config.collectAsState()
    val scope = rememberCoroutineScope()

    var repoInput by remember(config.githubRepoUrl) { mutableStateOf(config.githubRepoUrl) }
    var repoSaveHint by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }

    val canInstall = updater.canInstallPackages(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "GitHub 專案連結設定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "可保留預設專案連結，或改為你自己的 Fork / 自訂 GitHub Release 儲存庫。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub Repository URL") },
                    placeholder = { Text(VoiceImeConfig.DEFAULT_GITHUB_REPO) },
                    shape = RoundedCornerShape(14.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val parsed = updater.parseOwnerRepo(repoInput)
                            if (parsed == null) {
                                repoSaveHint = "格式錯誤，請輸入 https://github.com/帳號/專案名稱"
                            } else {
                                val normalized = "https://github.com/${parsed.first}/${parsed.second}"
                                prefs.update { it.copy(githubRepoUrl = normalized) }
                                repoInput = normalized
                                repoSaveHint = "已儲存更新來源：$normalized"
                            }
                        }
                    ) {
                        Text("儲存連結")
                    }
                    OutlinedButton(
                        onClick = {
                            repoInput = VoiceImeConfig.DEFAULT_GITHUB_REPO
                            prefs.update { it.copy(githubRepoUrl = VoiceImeConfig.DEFAULT_GITHUB_REPO) }
                            repoSaveHint = "已重設為預設連結"
                        }
                    ) {
                        Text("重設預設")
                    }
                }
                if (!repoSaveHint.isNullOrBlank()) {
                    Text(
                        text = repoSaveHint!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "版本檢查與自動下載安裝",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "目前安裝版本：v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium
                )
                SettingSwitchRow(
                    title = "背景自動檢測更新並下載",
                    subtitle = "每 12 小時自動檢查 GitHub 最新 Release，發現新版自動下載並通知安裝",
                    checked = config.autoUpdateEnabled,
                    onCheckedChange = { enabled ->
                        prefs.update { it.copy(autoUpdateEnabled = enabled) }
                        if (enabled) AutoUpdateWorker.schedule(context) else AutoUpdateWorker.cancel(context)
                    }
                )

                if (!canInstall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text("授權「安裝未知應用程式」權限")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !checking && !downloading,
                        onClick = {
                            checking = true
                            statusMessage = "正在檢查 GitHub 最新版本…"
                            releaseInfo = null
                            scope.launch {
                                when (val res = updater.checkLatestRelease(config.githubRepoUrl)) {
                                    is UpdateCheckResult.Success -> {
                                        releaseInfo = res.info
                                        statusMessage = if (res.info.hasUpdate) {
                                            "發現新版本 ${res.info.tagName}！"
                                        } else {
                                            "目前已是最新版本（遠端：${res.info.tagName}）"
                                        }
                                    }

                                    is UpdateCheckResult.Error -> {
                                        statusMessage = "檢查失敗：${res.message}"
                                    }
                                }
                                checking = false
                            }
                        }
                    ) {
                        Text(if (checking) "檢查中…" else "立即檢查更新")
                    }

                    val apkUrl = releaseInfo?.apkDownloadUrl
                    if (apkUrl != null) {
                        Button(
                            enabled = !downloading,
                            onClick = {
                                downloading = true
                                downloadProgress = 0f
                                statusMessage = "正在下載新版 APK…"
                                scope.launch {
                                    val result = updater.downloadAndInstall(
                                        context = context,
                                        downloadUrl = apkUrl,
                                        onProgress = { p -> downloadProgress = p }
                                    )
                                    statusMessage = if (result.isSuccess) {
                                        "下載完成，已啟動系統安裝程式"
                                    } else {
                                        "下載或安裝失敗：${result.exceptionOrNull()?.localizedMessage}"
                                    }
                                    downloading = false
                                }
                            }
                        ) {
                            Text(if (downloading) "下載中…" else "下載並自動安裝")
                        }
                    }
                }

                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val notes = releaseInfo?.body?.trim().orEmpty()
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "更新內容 (${releaseInfo?.title})：",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
