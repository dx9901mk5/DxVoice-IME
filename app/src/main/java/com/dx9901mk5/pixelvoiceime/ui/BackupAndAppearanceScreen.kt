package com.dx9901mk5.pixelvoiceime.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode
import com.dx9901mk5.pixelvoiceime.data.BackupManager
import com.dx9901mk5.pixelvoiceime.ime.FloatingMicService
import com.dx9901mk5.pixelvoiceime.ime.PixelVoiceAccessibilityService
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupAndAppearanceScreen(app: PixelVoiceApp) {
    val context = LocalContext.current
    val config by app.prefs.config.collectAsState()
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }

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
            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager)
            val res = BackupManager.writeBackupToTreeUri(context, uri, fileName, json)
            backupStatus = res.fold(
                onSuccess = { savedName -> "已記住備份路徑並完成備份：$savedName" },
                onFailure = { e -> "寫入資料夾失敗：${e.localizedMessage}" }
            )
        }
    }

    val createLocalDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager)
            val res = BackupManager.writeBackupToDocumentUri(context, uri, json)
            backupStatus = res.fold(
                onSuccess = { "已成功匯出備份檔案" },
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
                modelManager = app.modelManager
            )
            backupStatus = res.fold(
                onSuccess = { dev -> "已從「$dev」的備份檔還原設定" },
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
        // Theme Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "外觀主題",
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

        // Floating Ball Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "懸浮球快速切換捷徑",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "使用其他輸入法時顯示可拖曳懸浮麥克風球，點擊立即呼叫切換輸入法；摺疊機將自動分開記憶外螢幕與內螢幕位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                canOverlay = Settings.canDrawOverlays(context)
                if (!canOverlay) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text("授權「顯示於其他應用程式上層」權限")
                    }
                }

                val accessibilityActive = PixelVoiceAccessibilityService.isRunning()
                Text(
                    text = if (accessibilityActive) {
                        "跟隨鍵盤顯示＋一鍵直切：已啟用 ✓（僅在鍵盤出現時顯示，點擊直接切換收音）"
                    } else {
                        "提示：啟用「PixelVoice 無障礙輔助」後，懸浮球會在鍵盤出現才顯示、鍵盤關閉就消失，且點擊直接切到本輸入法開始收音（免跳系統選單）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!accessibilityActive) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    ) {
                        Text("前往開啟「PixelVoice 語音輸入法」無障礙輔助")
                    }
                }

                SettingSwitchRow(
                    title = "啟用懸浮語音捷徑球",
                    subtitle = if (canOverlay) "切換到其他鍵盤時自動顯示，可自由拖曳記憶位置" else "請先授予上層顯示權限",
                    checked = config.floatingBallEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            app.prefs.update { it.copy(floatingBallEnabled = enabled) }
                            if (enabled) {
                                FloatingMicService.start(context)
                            } else {
                                FloatingMicService.stop(context)
                            }
                        }
                    }
                )

                Text(
                    text = "懸浮球透明度：${(config.floatingBallAlpha * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = config.floatingBallAlpha,
                    onValueChange = { alpha ->
                        app.prefs.update { it.copy(floatingBallAlpha = alpha.coerceIn(0.25f, 1.0f)) }
                    },
                    valueRange = 0.25f..1.0f
                )

                Text(
                    text = "記憶座標｜外螢幕/手機：(${config.floatingBallOuterX}, ${config.floatingBallOuterY}) ｜ 摺疊內螢幕/平板：(${config.floatingBallInnerX}, ${config.floatingBallInnerY})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        app.prefs.update {
                            it.copy(
                                floatingBallOuterX = 40,
                                floatingBallOuterY = 420,
                                floatingBallInnerX = 60,
                                floatingBallInnerY = 520
                            )
                        }
                    }
                ) {
                    Text("重設內/外螢幕懸浮球位置")
                }
            }
        }

        // Backup & Restore Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "設定備份與還原（本地 / Google 雲端硬碟）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                            val json = BackupManager.exportJson(app.prefs, app.apiKeyStore, app.modelManager)
                            val res = BackupManager.writeBackupToTreeUri(
                                context = context,
                                treeUri = Uri.parse(config.backupTreeUri),
                                fileName = previewName,
                                jsonContent = json
                            )
                            backupStatus = res.fold(
                                onSuccess = { savedName -> "已一鍵備份至記憶路徑：$savedName" },
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
                        onClick = { folderPickerLauncher.launch(null) }
                    ) {
                        Text(if (config.backupTreeUri.isBlank()) "指定 Google 雲端硬碟 / 本地資料夾" else "更換備份資料夾")
                    }
                    OutlinedButton(
                        onClick = { createLocalDocLauncher.launch(previewName) }
                    ) {
                        Text("單次另存備份檔")
                    }
                    OutlinedButton(
                        onClick = { restoreDocLauncher.launch(arrayOf("application/json", "*/*")) }
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

        Spacer(Modifier.height(16.dp))
    }
}
