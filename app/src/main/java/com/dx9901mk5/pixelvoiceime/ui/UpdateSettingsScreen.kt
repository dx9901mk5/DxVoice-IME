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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.BuildConfig
import com.dx9901mk5.pixelvoiceime.R
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
    val activityContext = LocalHostActivity.current ?: context
    val config by prefs.config.collectAsState()
    val scope = rememberCoroutineScope()

    var repoInput by remember(config.githubRepoUrl) { mutableStateOf(config.githubRepoUrl) }
    var repoSaveHint by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }

    val canInstall = if (BuildConfig.IS_PLAY_BUILD) false else updater.canInstallPackages(context)

    fun startDownloadAndInstall(apkUrl: String, versionLabel: String? = null) {
        downloading = true
        downloadProgress = 0f
        statusMessage = if (versionLabel != null) {
            context.getString(R.string.about_dl_new, versionLabel)
        } else {
            context.getString(R.string.about_dl_generic)
        }
        scope.launch {
            val result = updater.downloadAndInstall(
                context = activityContext,
                downloadUrl = apkUrl,
                onProgress = { p -> downloadProgress = p }
            )
            statusMessage = if (result.isSuccess) {
                context.getString(R.string.about_dl_done)
            } else {
                context.getString(R.string.about_dl_fail, result.exceptionOrNull()?.localizedMessage ?: "")
            }
            downloading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (BuildConfig.IS_PLAY_BUILD) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.about_play_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.about_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.about_play_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val marketIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                            )
                            runCatching { activityContext.startActivity(marketIntent) }
                        }
                    ) {
                        Text(stringResource(R.string.about_play_btn))
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.about_github_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.about_github_desc),
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
                                    repoSaveHint = context.getString(R.string.about_github_invalid)
                                } else {
                                    val normalized = "https://github.com/${parsed.first}/${parsed.second}"
                                    prefs.update { it.copy(githubRepoUrl = normalized) }
                                    repoInput = normalized
                                    repoSaveHint = context.getString(R.string.about_github_saved, normalized)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.about_github_save_btn))
                        }
                        OutlinedButton(
                            onClick = {
                                repoInput = VoiceImeConfig.DEFAULT_GITHUB_REPO
                                prefs.update { it.copy(githubRepoUrl = VoiceImeConfig.DEFAULT_GITHUB_REPO) }
                                repoSaveHint = context.getString(R.string.about_github_reset_msg)
                            }
                        ) {
                            Text(stringResource(R.string.about_github_reset_btn))
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
                        text = stringResource(R.string.about_update_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.about_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.about_auto_update_title),
                        subtitle = stringResource(R.string.about_auto_update_sub),
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
                                activityContext.startActivity(intent)
                            }
                        ) {
                            Text(stringResource(R.string.about_allow_install))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !checking && !downloading,
                            onClick = {
                                checking = true
                                statusMessage = context.getString(R.string.about_checking_msg)
                                releaseInfo = null
                                scope.launch {
                                    when (val res = updater.checkLatestRelease(config.githubRepoUrl)) {
                                        is UpdateCheckResult.Success -> {
                                            releaseInfo = res.info
                                            checking = false
                                            val apkUrl = res.info.apkDownloadUrl
                                            if (res.info.hasUpdate && !apkUrl.isNullOrBlank()) {
                                                startDownloadAndInstall(apkUrl, res.info.tagName)
                                            } else if (res.info.hasUpdate) {
                                                statusMessage = context.getString(R.string.about_no_apk, res.info.tagName)
                                            } else {
                                                statusMessage = context.getString(R.string.about_up_to_date, res.info.tagName)
                                            }
                                        }

                                        is UpdateCheckResult.Error -> {
                                            statusMessage = context.getString(R.string.about_check_fail, res.message)
                                            checking = false
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(if (checking) stringResource(R.string.about_checking_btn) else stringResource(R.string.about_check_btn))
                        }

                        val info = releaseInfo
                        val apkUrl = info?.apkDownloadUrl
                        if (info?.hasUpdate == true && apkUrl != null) {
                            Button(
                                enabled = !downloading,
                                onClick = { startDownloadAndInstall(apkUrl) }
                            ) {
                                Text(if (downloading) stringResource(R.string.about_downloading_btn) else stringResource(R.string.about_download_btn))
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
                            text = stringResource(R.string.about_release_notes, releaseInfo?.title ?: ""),
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

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.credits_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.credits_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.credits_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.credits_special_thanks),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/dx9901mk5/DxVoice-IME/blob/main/PRIVACY_POLICY.md")
                        )
                        activityContext.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.credits_privacy_btn))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
