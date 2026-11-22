package com.dx9901mk5.pixelvoiceime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.dx9901mk5.pixelvoiceime.models.PackDownloadState
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageCatalog
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageItem
import kotlinx.coroutines.launch

@Composable
fun OfflineLanguagesScreen(
    modelManager: OfflineModelManager,
    prefs: VoiceImePrefs
) {
    val packStates by modelManager.packStates.collectAsState()
    val config by prefs.config.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            VoiceLanguageCatalog.languages
        } else {
            VoiceLanguageCatalog.languages.filter {
                it.displayName.lowercase().contains(q) ||
                    it.nativeName.lowercase().contains(q) ||
                    it.bcp47.lowercase().contains(q) ||
                    it.regionGroup.lowercase().contains(q)
            }
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.regionGroup } }
    val installedPacksCount = packStates.values.count { it is PackDownloadState.Installed }
    val installedMb = modelManager.totalInstalledBytes() / (1024f * 1024f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "離線語音檔案庫（仿 Google 語音輸入法）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "支援 ${VoiceLanguageCatalog.languages.size} 種語系與地區變體。多語共用底層權重，下載一次即可離線辨識同組語系。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = String.format(
                            "已安裝離線包：%d 組 • 已用空間：%.1f MB • 目前語系：%s",
                            installedPacksCount,
                            installedMb,
                            VoiceLanguageCatalog.findByTag(config.activeLanguageTag).displayName
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜尋語系（例如：繁體中文、日文、English、粵語）") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(16.dp)
            )
        }

        grouped.forEach { (region, itemsInRegion) ->
            item(key = "header_$region") {
                Text(
                    text = "$region (${itemsInRegion.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(itemsInRegion, key = { it.bcp47 }) { lang ->
                val state = packStates[lang.packId] ?: PackDownloadState.NotInstalled
                val pack = VoiceLanguageCatalog.modelPacks[lang.packId]
                val isSelected = config.activeLanguageTag.equals(lang.bcp47, ignoreCase = true)

                LanguageRowCard(
                    lang = lang,
                    packSizeMb = pack?.approxSizeMb ?: 100,
                    state = state,
                    isActive = isSelected,
                    onSelectActive = {
                        modelManager.enableLanguage(lang.bcp47)
                        prefs.update { it.copy(activeLanguageTag = lang.bcp47) }
                    },
                    onDownload = {
                        scope.launch { modelManager.downloadLanguage(lang.bcp47) }
                    },
                    onDeletePack = {
                        scope.launch { modelManager.deletePack(lang.packId) }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LanguageRowCard(
    lang: VoiceLanguageItem,
    packSizeMb: Int,
    state: PackDownloadState,
    isActive: Boolean,
    onSelectActive: () -> Unit,
    onDownload: () -> Unit,
    onDeletePack: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSelectActive) {
                    Icon(
                        imageVector = if (isActive) {
                            Icons.Rounded.RadioButtonChecked
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = "設為目前語系",
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lang.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        text = "${lang.nativeName} • ${lang.bcp47}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (state) {
                    is PackDownloadState.Installed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "已下載",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "已下載",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = onDeletePack) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "刪除離線包",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is PackDownloadState.Downloading -> {
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    is PackDownloadState.Error,
                    PackDownloadState.NotInstalled -> {
                        FilledTonalIconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "下載離線語音檔 (~${packSizeMb}MB)"
                            )
                        }
                    }
                }
            }

            if (state is PackDownloadState.Downloading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state is PackDownloadState.Error) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "下載失敗：${state.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
