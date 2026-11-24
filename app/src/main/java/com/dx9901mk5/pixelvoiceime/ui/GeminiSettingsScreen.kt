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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.ai.GeminiPolisher
import com.dx9901mk5.pixelvoiceime.ai.GeminiResult
import com.dx9901mk5.pixelvoiceime.billing.ProFeature
import com.dx9901mk5.pixelvoiceime.data.ApiKeyStore
import com.dx9901mk5.pixelvoiceime.data.PolishMode
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import com.dx9901mk5.pixelvoiceime.models.PackDownloadState
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageCatalog
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
    val app = LocalContext.current.applicationContext as PixelVoiceApp
    val packStates by app.modelManager.packStates.collectAsState()
    val localLlmState = packStates[VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B] ?: PackDownloadState.NotInstalled

    var keyInput by remember { mutableStateOf(apiKeyStore.getApiKey()) }
    var customModel by remember(selectedModel) { mutableStateOf(selectedModel) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var subTab by remember { mutableIntStateOf(0) }
    var upgradeFeature by remember { mutableStateOf<ProFeature?>(null) }
    val hasProAccess = app.entitlementManager.hasProAccess()

    if (upgradeFeature != null) {
        ProUpgradeDialog(
            entitlementManager = app.entitlementManager,
            highlightedFeature = upgradeFeature,
            onDismiss = { upgradeFeature = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text("詞庫與本地模型") }
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text("雲端 Gemini / 自架端點") }
            )
        }

        if (subTab == 0) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "本地端台灣口語修潤（可選）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingSwitchRow(
                        title = "台灣口語／同音錯字本地校正表",
                        subtitle = "參照教育部與台灣常見錯別字範本（如：因該→應該、以經→已經），0.01 秒內離線完成",
                        checked = config.localTaiwanTypoFixEnabled,
                        onCheckedChange = { v -> prefs.update { it.copy(localTaiwanTypoFixEnabled = v) } }
                    )
                    SettingSwitchRow(
                        title = "旗艦機本地極速小模型修潤 (Qwen2.5 0.5B · Pro)",
                        subtitle = "無須網路、XNNPACK 極速推論，中文錯字修潤更快更精準（約 521 MB，自動清理舊 Gemma 3 模型；關閉時改走 0ms 詞庫直出）",
                        checked = config.localLlmEnabled && hasProAccess,
                        onCheckedChange = { v ->
                            if (v && !hasProAccess) {
                                upgradeFeature = ProFeature.LOCAL_LLM
                            } else {
                                prefs.update { it.copy(localLlmEnabled = v) }
                                if (v) {
                                    app.localLlmPolisher.clearCrashGuard()
                                } else {
                                    app.localLlmPolisher.release()
                                }
                            }
                        }
                    )
                    Text(
                        text = "⚡ 加速說明：Qwen2.5 0.5B 採 XNNPACK 多執行緒極速 CPU 推論（避免 GPU 編譯卡頓，內建 4.5 秒超時自動降級保護），建議具備 8GB+ RAM 之手機使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when (val s = localLlmState) {
                        is PackDownloadState.NotInstalled -> {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        app.modelManager.downloadPack(VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B)
                                    }
                                }
                            ) {
                                Text("下載 Qwen2.5 0.5B 極速中文模型 (約 521 MB)")
                            }
                        }
                        is PackDownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = s.statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is PackDownloadState.Installed -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Qwen2.5 0.5B 已就緒 (${s.sizeBytes / (1024 * 1024)} MB · ${app.localLlmPolisher.activeBackendLabel()})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            app.localLlmPolisher.release()
                                            app.modelManager.deletePack(VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B)
                                        }
                                    }
                                ) {
                                    Text("移除模型")
                                }
                            }
                        }
                        is PackDownloadState.Error -> {
                            Text(
                                text = "下載失敗：${s.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        app.modelManager.downloadPack(VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B)
                                    }
                                }
                            ) {
                                Text("重試下載 Qwen2.5 0.5B")
                            }
                        }
                    }
                }
            }

            if (hasProAccess) {
                DictionarySettingsSection(dictionaryStore = app.customDictionaryStore)
            } else {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "自定義詞庫、0ms 替換與習慣用語學習（Pro）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "升級 DxVoice-IME Pro 即可解鎖專屬詞彙表、即時詞彙替換與個人習慣用語自動學習。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { upgradeFeature = ProFeature.CUSTOM_DICTIONARY }) {
                            Text("解鎖 Pro 自定義詞庫")
                        }
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
                    FilterChip(
                        selected = config.geminiPolishEnabled && config.polishMode == PolishMode.OPENAI_COMPATIBLE,
                        onClick = {
                            prefs.update {
                                it.copy(geminiPolishEnabled = true, polishMode = PolishMode.OPENAI_COMPATIBLE)
                            }
                        },
                        label = { Text("自架 / OpenAI 端點") }
                    )
                }
                Text(
                    text = when {
                        !config.geminiPolishEnabled ->
                            "完全使用裝置本地語音辨識（有下載離線包時即走 Sherpa-ONNX 極速本地引擎），不呼叫 API。"
                        config.polishMode == PolishMode.FAST_TEXT ->
                            "先以本地/系統語音極速轉寫，說完再送 Gemini API 修潤標點，延遲最低。"
                        config.polishMode == PolishMode.AUDIO_MULTIMODAL ->
                            "直接錄製 16kHz WAV 音訊送交 Gemini API 聆聽轉錄與標點。"
                        else ->
                            "連線至自架 Mac / 區網 Whisper 伺服器，或 Groq / OpenAI / DeepSeek 相容端點（支援 HTTP 區網）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (config.geminiPolishEnabled && config.polishMode == PolishMode.OPENAI_COMPATIBLE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "自架 / OpenAI 相容端點設定（voice-keyboard 上游規範）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                prefs.update {
                                    it.copy(
                                        customAsrEndpoint = "http://192.168.1.100:8000/v1",
                                        customAsrModel = "whisper-1",
                                        customAsrApiKey = ""
                                    )
                                }
                            }
                        ) { Text("套用 Mac 區網範例") }
                        OutlinedButton(
                            onClick = {
                                prefs.update {
                                    it.copy(
                                        customAsrEndpoint = "https://api.groq.com/openai/v1",
                                        customAsrModel = "whisper-large-v3-turbo"
                                    )
                                }
                            }
                        ) { Text("套用 Groq") }
                        OutlinedButton(
                            onClick = {
                                prefs.update {
                                    it.copy(
                                        customAsrEndpoint = "https://api.openai.com/v1",
                                        customAsrModel = "whisper-1"
                                    )
                                }
                            }
                        ) { Text("套用 OpenAI") }
                    }
                    OutlinedTextField(
                        value = config.customAsrEndpoint,
                        onValueChange = { v -> prefs.update { it.copy(customAsrEndpoint = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ASR 端點 URL（自動補 /audio/transcriptions）") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = config.customAsrModel,
                        onValueChange = { v -> prefs.update { it.copy(customAsrModel = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ASR 模型名稱（如 whisper-1 / whisper-large-v3-turbo）") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = config.customAsrApiKey,
                        onValueChange = { v -> prefs.update { it.copy(customAsrApiKey = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("ASR API Key（自架 Mac 端可留空）") },
                        shape = RoundedCornerShape(14.dp)
                    )

                    SettingSwitchRow(
                        title = "額外接自訂 LLM 修潤（如 DeepSeek / Mac Ollama）",
                        subtitle = "關閉時將使用本地標點與台灣繁體詞庫直接輸出",
                        checked = config.customLlmEnabled,
                        onCheckedChange = { v -> prefs.update { it.copy(customLlmEnabled = v) } }
                    )
                    if (config.customLlmEnabled) {
                        OutlinedTextField(
                            value = config.customLlmEndpoint,
                            onValueChange = { v -> prefs.update { it.copy(customLlmEndpoint = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("LLM 端點 URL（自動補 /chat/completions）") },
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = config.customLlmModel,
                            onValueChange = { v -> prefs.update { it.copy(customLlmModel = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("LLM 模型（如 deepseek-chat / gpt-4o-mini）") },
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = config.customLlmApiKey,
                            onValueChange = { v -> prefs.update { it.copy(customLlmApiKey = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("LLM API Key（自架 Ollama 可留空）") },
                            shape = RoundedCornerShape(14.dp)
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
