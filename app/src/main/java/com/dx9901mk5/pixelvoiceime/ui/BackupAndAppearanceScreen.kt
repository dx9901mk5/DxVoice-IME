package com.dx9901mk5.pixelvoiceime.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.billing.ProFeature
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode
import com.dx9901mk5.pixelvoiceime.data.BackupManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupAndAppearanceScreen(app: PixelVoiceApp) {
    val context = LocalContext.current
    val config by app.prefs.config.collectAsState()
    val dictState by app.customDictionaryStore.state.collectAsState()
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    val hasProAccess = app.entitlementManager.hasProAccess()

    if (showUpgradeDialog) {
        ProUpgradeDialog(
            entitlementManager = app.entitlementManager,
            highlightedFeature = ProFeature.CLOUD_BACKUP,
            onDismiss = { showUpgradeDialog = false }
        )
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            app.prefs.update { it.copy(backupTreeUri = uri.toString()) }
            val fileName = BackupManager.buildBackupFileName(config.backupDeviceName)
            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager, app.customDictionaryStore)
            val res = BackupManager.writeBackupToTreeUri(context, uri, fileName, json)
            backupStatus = res.fold(
                onSuccess = { savedName -> "已記住備份路徑並完成備份（含自定義詞庫）：$savedName" },
                onFailure = { e -> "寫入資料夾失敗：${e.localizedMessage}" }
            )
        }
    }

    val createLocalDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager, app.customDictionaryStore)
            val res = BackupManager.writeBackupToDocumentUri(context, uri, json)
            backupStatus = res.fold(
                onSuccess = { "已成功匯出備份檔案（含自定義詞庫與設定）" },
                onFailure = { e -> "匯出失敗：${e.localizedMessage}" }
            )
        }
    }

    val restoreDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val res = BackupManager.restoreFromDocumentUri(
                context = context,
                docUri = uri,
                prefs = app.prefs,
                apiKeyStore = app.apiKeyStore,
                modelManager = app.modelManager,
                dictionaryStore = app.customDictionaryStore
            )
            backupStatus = res.fold(
                onSuccess = { dev -> "已從「$dev」的備份檔還原設定與自定義詞庫" },
                onFailure = { e -> "還原失敗：${e.localizedMessage}" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Backup & Restore Card (Primary)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "備份與還原（含自定義詞庫・本地 / Google 雲端硬碟）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "備份內容包含所有輸入法設定、懸浮球內／外螢幕座標，以及自定義詞庫（專有名詞 ${dictState.customWords.size} 筆、替換規則 ${dictState.replacements.size} 筆、習慣用語 ${dictState.learnedPhrases.size} 筆）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = config.backupDeviceName,
                    onValueChange = { v -> app.prefs.update { it.copy(backupDeviceName = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("裝置名稱（備份檔名抬頭）") },
                    shape = RoundedCornerShape(14.dp)
                )
                val previewName = BackupManager.buildBackupFileName(config.backupDeviceName)
                Text(
                    text = "輸出檔名預覽：$previewName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (config.backupTreeUri.isNotBlank()) {
                    Text(
                        text = "已記憶備份路徑：${Uri.parse(config.backupTreeUri).lastPathSegment ?: config.backupTreeUri}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (!hasProAccess) {
                                showUpgradeDialog = true
                                return@Button
                            }
                            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager, app.customDictionaryStore)
                            val res = BackupManager.writeBackupToTreeUri(
                                context = context,
                                treeUri = Uri.parse(config.backupTreeUri),
                                fileName = previewName,
                                jsonContent = json
                            )
                            backupStatus = res.fold(
                                onSuccess = { savedName -> "已一鍵備份至記憶路徑（含自定義詞庫）：$savedName" },
                                onFailure = { e -> "寫入失敗（請重新指定資料夾）：${e.localizedMessage}" }
                            )
                        }
                    ) {
                        Text("一鍵備份至已記憶路徑")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!hasProAccess) showUpgradeDialog = true else folderPickerLauncher.launch(null)
                        }
                    ) {
                        Text(if (config.backupTreeUri.isBlank()) "指定 Google 雲端硬碟 / 本地資料夾" else "更換備份資料夾")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!hasProAccess) showUpgradeDialog = true else createLocalDocLauncher.launch(previewName)
                        }
                    ) {
                        Text("單次另存備份檔")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!hasProAccess) showUpgradeDialog = true else restoreDocLauncher.launch(arrayOf("application/json", "*/*"))
                        }
                    ) {
                        Text("從備份檔還原")
                    }
                }

                if (!backupStatus.isNullOrBlank()) {
                    Text(
                        text = backupStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Theme Card (Secondary)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "介面主題",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.appThemeMode == AppThemeMode.DYNAMIC_PIXEL,
                        onClick = { app.prefs.update { it.copy(appThemeMode = AppThemeMode.DYNAMIC_PIXEL) } },
                        label = { Text("預設動態色彩 (Pixel)") }
                    )
                    FilterChip(
                        selected = config.appThemeMode == AppThemeMode.ORANGE_CHARCOAL,
                        onClick = { app.prefs.update { it.copy(appThemeMode = AppThemeMode.ORANGE_CHARCOAL) } },
                        label = { Text("橘色灰黑底 (Orange Charcoal)") }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
