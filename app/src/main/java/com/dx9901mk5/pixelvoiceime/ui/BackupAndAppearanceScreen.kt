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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.R
import com.dx9901mk5.pixelvoiceime.billing.ProFeature
import com.dx9901mk5.pixelvoiceime.data.AppThemeMode
import com.dx9901mk5.pixelvoiceime.data.AppUiLanguage
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
                onSuccess = { savedName -> context.getString(R.string.backup_status_folder_ok, savedName) },
                onFailure = { e -> context.getString(R.string.backup_status_folder_fail, e.localizedMessage ?: "") }
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
                onSuccess = { context.getString(R.string.backup_status_export_ok) },
                onFailure = { e -> context.getString(R.string.backup_status_export_fail, e.localizedMessage ?: "") }
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
                onSuccess = { dev -> context.getString(R.string.backup_status_restore_ok, dev) },
                onFailure = { e -> context.getString(R.string.backup_status_restore_fail, e.localizedMessage ?: "") }
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
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.backup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.backup_desc,
                        dictState.customWords.size,
                        dictState.replacements.size,
                        dictState.learnedPhrases.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = config.backupDeviceName,
                    onValueChange = { v -> app.prefs.update { it.copy(backupDeviceName = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_device_label)) },
                    shape = RoundedCornerShape(14.dp)
                )
                val previewName = BackupManager.buildBackupFileName(config.backupDeviceName)
                Text(
                    text = stringResource(R.string.backup_preview, previewName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (config.backupTreeUri.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.backup_saved_path,
                            Uri.parse(config.backupTreeUri).lastPathSegment ?: config.backupTreeUri
                        ),
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
                                onSuccess = { savedName -> context.getString(R.string.backup_status_one_click_ok, savedName) },
                                onFailure = { e -> context.getString(R.string.backup_status_one_click_fail, e.localizedMessage ?: "") }
                            )
                        }
                    ) {
                        Text(stringResource(R.string.backup_one_click_btn))
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
                        Text(
                            if (config.backupTreeUri.isBlank()) {
                                stringResource(R.string.backup_pick_folder)
                            } else {
                                stringResource(R.string.backup_change_folder)
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (!hasProAccess) showUpgradeDialog = true else createLocalDocLauncher.launch(previewName)
                        }
                    ) {
                        Text(stringResource(R.string.backup_save_once))
                    }
                    OutlinedButton(
                        onClick = {
                            if (!hasProAccess) showUpgradeDialog = true else restoreDocLauncher.launch(arrayOf("application/json", "*/*"))
                        }
                    ) {
                        Text(stringResource(R.string.backup_restore_btn))
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

        // UI Language Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.ui_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.uiLanguage == AppUiLanguage.SYSTEM,
                        onClick = { app.prefs.update { it.copy(uiLanguage = AppUiLanguage.SYSTEM) } },
                        label = { Text(stringResource(R.string.ui_language_system)) }
                    )
                    FilterChip(
                        selected = config.uiLanguage == AppUiLanguage.ZH_TW,
                        onClick = { app.prefs.update { it.copy(uiLanguage = AppUiLanguage.ZH_TW) } },
                        label = { Text(stringResource(R.string.ui_language_zh)) }
                    )
                    FilterChip(
                        selected = config.uiLanguage == AppUiLanguage.EN,
                        onClick = { app.prefs.update { it.copy(uiLanguage = AppUiLanguage.EN) } },
                        label = { Text(stringResource(R.string.ui_language_en)) }
                    )
                    FilterChip(
                        selected = config.uiLanguage == AppUiLanguage.JA,
                        onClick = { app.prefs.update { it.copy(uiLanguage = AppUiLanguage.JA) } },
                        label = { Text(stringResource(R.string.ui_language_ja)) }
                    )
                }
            }
        }

        // Theme Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.theme_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.appThemeMode == AppThemeMode.DYNAMIC_PIXEL,
                        onClick = { app.prefs.update { it.copy(appThemeMode = AppThemeMode.DYNAMIC_PIXEL) } },
                        label = { Text(stringResource(R.string.theme_dynamic)) }
                    )
                    FilterChip(
                        selected = config.appThemeMode == AppThemeMode.ORANGE_CHARCOAL,
                        onClick = { app.prefs.update { it.copy(appThemeMode = AppThemeMode.ORANGE_CHARCOAL) } },
                        label = { Text(stringResource(R.string.theme_orange)) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
