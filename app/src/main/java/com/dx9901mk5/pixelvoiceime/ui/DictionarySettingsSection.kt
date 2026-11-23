package com.dx9901mk5.pixelvoiceime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.data.CustomDictionaryStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionarySettingsSection(
    dictionaryStore: CustomDictionaryStore
) {
    val state by dictionaryStore.state.collectAsState()
    var newWordsInput by remember { mutableStateOf("") }
    var repFromInput by remember { mutableStateOf("") }
    var repToInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. 修飾依據 & 學習開關
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "修飾依據與慣用語學習（參考 voice-keyboard v1.12）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("將詞庫與慣用語作為 AI 修飾依據", fontWeight = FontWeight.Medium)
                        Text(
                            "自動餵給 Whisper prompt 與 DeepSeek / Gemini / 本地 LLM 作拼寫參考",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.useAsHintsEnabled,
                        onCheckedChange = { dictionaryStore.setUseAsHintsEnabled(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("自動學習慣用語與手動改字", fontWeight = FontWeight.Medium)
                        Text(
                            "語音輸出後若您在輸入框手動改字，或高頻使用英文／專有名詞，將於本機自動累積學習",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoLearnEnabled,
                        onCheckedChange = { dictionaryStore.setAutoLearnEnabled(it) }
                    )
                }
            }
        }

        // 2. 自定義文字詞（專有名詞 / 人名 / 術語）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "自定義文字詞庫（${state.customWords.size} 個）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "支援逗號、頓號或換行一次新增多個詞（例：DxVoice, 台積電, Antigravity）。點擊詞彙標籤可移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newWordsInput,
                    onValueChange = { newWordsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("輸入自定義詞彙") },
                    placeholder = { Text("例：DxVoice, 傑克, Whisper") },
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        dictionaryStore.addCustomWordsBulk(newWordsInput)
                        newWordsInput = ""
                    },
                    enabled = newWordsInput.isNotBlank()
                ) {
                    Text("加入詞庫")
                }

                if (state.customWords.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.customWords.forEach { word ->
                            FilterChip(
                                selected = true,
                                onClick = { dictionaryStore.removeCustomWord(word) },
                                label = { Text("$word ✕") }
                            )
                        }
                    }
                }
            }
        }

        // 3. 0ms 本地強制替換規則
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "強制文字替換規則（${state.replacements.size} 條）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "送出文字前於本機 0ms 直接替換，100% 修正固定誤判（例：哀鳳 -> iPhone）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = repFromInput,
                        onValueChange = { repFromInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("原錯詞") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = repToInput,
                        onValueChange = { repToInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("替換為") },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                Button(
                    onClick = {
                        dictionaryStore.addReplacement(repFromInput, repToInput)
                        repFromInput = ""
                        repToInput = ""
                    },
                    enabled = repFromInput.isNotBlank() && repToInput.isNotBlank()
                ) {
                    Text("新增替換規則")
                }

                state.replacements.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${rule.from}  →  ${rule.to}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { dictionaryStore.removeReplacement(rule.from) }) {
                            Text("刪除")
                        }
                    }
                }
            }
        }

        // 4. 自動學習的慣用語清單
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自動學習的慣用語（${state.learnedPhrases.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.learnedPhrases.isNotEmpty()) {
                        TextButton(onClick = { dictionaryStore.clearLearnedPhrases() }) {
                            Text("全部清除")
                        }
                    }
                }
                Text(
                    text = "累計 ≥ 2 次自動成為修飾依據；≥ 3 次的改字規則會自動套用。可隨時釘選或封鎖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.learnedPhrases.isEmpty()) {
                    Text(
                        text = "目前尚無自動學習紀錄，正常語音輸入或手動修正錯字後會自動出現。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.learnedPhrases.take(30).forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (item.sourceWord.isNotBlank()) {
                                        "${item.sourceWord} → ${item.phrase}"
                                    } else {
                                        item.phrase
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "學習次數：${item.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (item.sourceWord.isNotBlank()) {
                                            dictionaryStore.promoteLearnedToReplacement(item)
                                        } else {
                                            dictionaryStore.promoteLearnedToCustomWord(item.phrase)
                                        }
                                    }
                                ) {
                                    Text("釘選")
                                }
                                TextButton(onClick = { dictionaryStore.removeAndBlockLearned(item.phrase) }) {
                                    Text("封鎖")
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
