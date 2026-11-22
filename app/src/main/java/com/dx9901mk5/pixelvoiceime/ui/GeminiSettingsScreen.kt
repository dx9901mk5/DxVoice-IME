package com.dx9901mk5.pixelvoiceime.ui

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.ai.GeminiPolisher
import com.dx9901mk5.pixelvoiceime.ai.GeminiResult
import com.dx9901mk5.pixelvoiceime.data.ApiKeyStore
import com.dx9901mk5.pixelvoiceime.data.PolishMode
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiSettingsScreen(
    apiKeyStore: ApiKeyStore,
    polisher: GeminiPolisher,
    prefs: VoiceImePrefs
) {
    val config by prefs.config.collectAsState()
    val hasApiKey by apiKeyStore.hasApiKey.collectAsState()
    val selectedModel by apiKeyStore.selectedModel.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var keyInput by remember { mutableStateOf(apiKeyStore.getApiKey()) }
    var customModel by remember(selectedModel) { mutableStateOf(selectedModel) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

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
                    text = "Gemini API Key (BYOK 加密儲存)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("輸入 Google AI Studio API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            apiKeyStore.saveApiKey(keyInput)
                            testStatus = "已加密儲存 API Key"
                        }
                    ) {
                        Text("儲存金鑰")
                    }
                    OutlinedButton(
                        enabled = !testing && (keyInput.isNotBlank() || hasApiKey),
                        onClick = {
                            if (keyInput.isNotBlank()) {
                                apiKeyStore.saveApiKey(keyInput)
                            }
                            testing = true
                            testStatus = "測試連線中…"
                            scope.launch {
                                testStatus = when (val res = polisher.testConnection()) {
                                    is GeminiResult.Success -> "連線成功：${res.data}"
                                    is GeminiResult.Error -> "連線失敗：${res.message}"
                                }
                                testing = false
                            }
                        }
                    ) {
                        Text("測試連線")
                    }
                }
                if (!testStatus.isNullOrBlank()) {
                    Text(
                        text = testStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "如何免費取得 Gemini API Key？",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "1. 點擊下方按鈕前往 Google AI Studio 並登入 Google 帳號。\n" +
                        "2. 點擊「Create API key」建立新的 API 金鑰（可選用預設專案或新建專案）。\n" +
                        "3. 複製「AIza...」開頭的金鑰，貼回上方欄位後點擊「儲存金鑰」與「測試連線」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }
                ) {
                    Text("前往 Google AI Studio 申請 API Key ↗")
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Gemini 模型與修潤模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ApiKeyStore.PRESET_MODELS.forEach { model ->
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = { apiKeyStore.setModel(model) },
                            label = { Text(model) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("或自訂模型代號") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { apiKeyStore.setModel(customModel) }) {
                        Text("套用")
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "語音辨識與處理模式（選擇本機或走 API）",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !config.geminiPolishEnabled,
                        onClick = { prefs.update { it.copy(geminiPolishEnabled = false) } },
                        label = { Text("純本機語音 (不走 API)") }
                    )
                    FilterChip(
                        selected = config.geminiPolishEnabled && config.polishMode == PolishMode.FAST_TEXT,
                        onClick = {
                            prefs.update {
                                it.copy(geminiPolishEnabled = true, polishMode = PolishMode.FAST_TEXT)
                            }
                        },
                        label = { Text("本機語音 + Gemini 修潤 (推薦)") }
                    )
                    FilterChip(
                        selected = config.geminiPolishEnabled && config.polishMode == PolishMode.AUDIO_MULTIMODAL,
                        onClick = {
                            prefs.update {
                                it.copy(geminiPolishEnabled = true, polishMode = PolishMode.AUDIO_MULTIMODAL)
                            }
                        },
                        label = { Text("純走 Gemini 音訊 API") }
                    )
                }
                Text(
                    text = when {
                        !config.geminiPolishEnabled ->
                            "完全使用裝置內建/系統語音辨識，不呼叫 Gemini API，僅於本機補上結尾標點。"
                        config.polishMode == PolishMode.FAST_TEXT ->
                            "先以本機/系統語音即時轉寫草稿，說完立即送 Gemini API 修正錯字與標點，延遲最低。"
                        else ->
                            "直接錄製 16kHz WAV 音訊送交 Gemini API 聆聽轉錄與標點，完全不依賴系統語音服務。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "語意修正與標點規則",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                SettingSwitchRow(
                    title = "啟用 Gemini 自動修潤",
                    subtitle = "關閉時將使用純本地離線輸出",
                    checked = config.geminiPolishEnabled,
                    onCheckedChange = { v -> prefs.update { it.copy(geminiPolishEnabled = v) } }
                )
                SettingSwitchRow(
                    title = "自動加入標點符號",
                    subtitle = "自動補齊逗號、句號、問號與驚嘆號",
                    checked = config.autoPunctuation,
                    onCheckedChange = { v -> prefs.update { it.copy(autoPunctuation = v) } }
                )
                SettingSwitchRow(
                    title = "自動去除口語贅詞",
                    subtitle = "過濾「嗯、啊、那個、呃」等語助詞",
                    checked = config.removeFillerWords,
                    onCheckedChange = { v -> prefs.update { it.copy(removeFillerWords = v) } }
                )
                SettingSwitchRow(
                    title = "參考游標前後上下文",
                    subtitle = "提供輸入框前後少量文字讓 Gemini 銜接語氣更精準",
                    checked = config.includeSurroundingContext,
                    onCheckedChange = { v -> prefs.update { it.copy(includeSurroundingContext = v) } }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
