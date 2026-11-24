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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dx9901mk5.pixelvoiceime.PixelVoiceApp
import com.dx9901mk5.pixelvoiceime.R
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
    val context = LocalContext.current
    val config by prefs.config.collectAsState()
    val hasApiKey by apiKeyStore.hasApiKey.collectAsState()
    val selectedModel by apiKeyStore.selectedModel.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val app = context.applicationContext as PixelVoiceApp
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
                label = { Text(stringResource(R.string.models_subtab_local)) }
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text(stringResource(R.string.models_subtab_cloud)) }
            )
        }

        if (subTab == 0) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.local_polish_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.local_typo_title),
                        subtitle = stringResource(R.string.local_typo_sub),
                        checked = config.localTaiwanTypoFixEnabled,
                        onCheckedChange = { v -> prefs.update { it.copy(localTaiwanTypoFixEnabled = v) } }
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.local_llm_title),
                        subtitle = stringResource(R.string.local_llm_sub),
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
                        text = stringResource(R.string.local_llm_accel_note),
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
                                Text(stringResource(R.string.local_llm_download_btn))
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
                                    text = stringResource(
                                        R.string.local_llm_ready,
                                        s.sizeBytes / (1024 * 1024),
                                        app.localLlmPolisher.activeBackendLabel()
                                    ),
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
                                    Text(stringResource(R.string.local_llm_remove))
                                }
                            }
                        }
                        is PackDownloadState.Error -> {
                            Text(
                                text = stringResource(R.string.offline_download_failed, s.message),
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
                                Text(stringResource(R.string.local_llm_retry))
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
                            text = stringResource(R.string.dict_pro_locked_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.dict_pro_locked_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { upgradeFeature = ProFeature.CUSTOM_DICTIONARY }) {
                            Text(stringResource(R.string.dict_pro_unlock_btn))
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
                        text = stringResource(R.string.gemini_key_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.gemini_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                apiKeyStore.saveApiKey(keyInput)
                                testStatus = context.getString(R.string.gemini_key_saved)
                            }
                        ) {
                            Text(stringResource(R.string.gemini_key_save_btn))
                        }
                        OutlinedButton(
                            enabled = !testing && (keyInput.isNotBlank() || hasApiKey),
                            onClick = {
                                if (keyInput.isNotBlank()) {
                                    apiKeyStore.saveApiKey(keyInput)
                                }
                                testing = true
                                testStatus = context.getString(R.string.gemini_key_testing)
                                scope.launch {
                                    testStatus = when (val res = polisher.testConnection()) {
                                        is GeminiResult.Success -> context.getString(R.string.gemini_key_test_ok, res.data)
                                        is GeminiResult.Error -> context.getString(R.string.gemini_key_test_fail, res.message)
                                    }
                                    testing = false
                                }
                            }
                        ) {
                            Text(stringResource(R.string.gemini_key_test_btn))
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
                        text = stringResource(R.string.gemini_howto_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.gemini_howto_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }
                    ) {
                        Text(stringResource(R.string.gemini_howto_btn))
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.gemini_model_title),
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
                            label = { Text(stringResource(R.string.gemini_custom_model_label)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { apiKeyStore.setModel(customModel) }) {
                            Text(stringResource(R.string.common_apply))
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.gemini_mode_label),
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
                            label = { Text(stringResource(R.string.mode_local_only)) }
                        )
                        FilterChip(
                            selected = config.geminiPolishEnabled && config.polishMode == PolishMode.FAST_TEXT,
                            onClick = {
                                prefs.update {
                                    it.copy(geminiPolishEnabled = true, polishMode = PolishMode.FAST_TEXT)
                                }
                            },
                            label = { Text(stringResource(R.string.mode_fast_text)) }
                        )
                        FilterChip(
                            selected = config.geminiPolishEnabled && config.polishMode == PolishMode.AUDIO_MULTIMODAL,
                            onClick = {
                                prefs.update {
                                    it.copy(geminiPolishEnabled = true, polishMode = PolishMode.AUDIO_MULTIMODAL)
                                }
                            },
                            label = { Text(stringResource(R.string.mode_audio_multimodal)) }
                        )
                        FilterChip(
                            selected = config.geminiPolishEnabled && config.polishMode == PolishMode.OPENAI_COMPATIBLE,
                            onClick = {
                                prefs.update {
                                    it.copy(geminiPolishEnabled = true, polishMode = PolishMode.OPENAI_COMPATIBLE)
                                }
                            },
                            label = { Text(stringResource(R.string.mode_openai_compat)) }
                        )
                    }
                    Text(
                        text = when {
                            !config.geminiPolishEnabled -> stringResource(R.string.mode_desc_local)
                            config.polishMode == PolishMode.FAST_TEXT -> stringResource(R.string.mode_desc_fast)
                            config.polishMode == PolishMode.AUDIO_MULTIMODAL -> stringResource(R.string.mode_desc_audio)
                            else -> stringResource(R.string.mode_desc_openai)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (config.geminiPolishEnabled && config.polishMode == PolishMode.OPENAI_COMPATIBLE) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.custom_endpoint_title),
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
                            ) { Text(stringResource(R.string.preset_mac_lan)) }
                            OutlinedButton(
                                onClick = {
                                    prefs.update {
                                        it.copy(
                                            customAsrEndpoint = "https://api.groq.com/openai/v1",
                                            customAsrModel = "whisper-large-v3-turbo"
                                        )
                                    }
                                }
                            ) { Text(stringResource(R.string.preset_groq)) }
                            OutlinedButton(
                                onClick = {
                                    prefs.update {
                                        it.copy(
                                            customAsrEndpoint = "https://api.openai.com/v1",
                                            customAsrModel = "whisper-1"
                                        )
                                    }
                                }
                            ) { Text(stringResource(R.string.preset_openai)) }
                        }
                        OutlinedTextField(
                            value = config.customAsrEndpoint,
                            onValueChange = { v -> prefs.update { it.copy(customAsrEndpoint = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.custom_asr_url)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = config.customAsrModel,
                            onValueChange = { v -> prefs.update { it.copy(customAsrModel = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.custom_asr_model)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = config.customAsrApiKey,
                            onValueChange = { v -> prefs.update { it.copy(customAsrApiKey = v) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.custom_asr_key)) },
                            shape = RoundedCornerShape(14.dp)
                        )

                        SettingSwitchRow(
                            title = stringResource(R.string.custom_llm_switch_title),
                            subtitle = stringResource(R.string.custom_llm_switch_sub),
                            checked = config.customLlmEnabled,
                            onCheckedChange = { v -> prefs.update { it.copy(customLlmEnabled = v) } }
                        )
                        if (config.customLlmEnabled) {
                            OutlinedTextField(
                                value = config.customLlmEndpoint,
                                onValueChange = { v -> prefs.update { it.copy(customLlmEndpoint = v) } },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.custom_llm_url)) },
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = config.customLlmModel,
                                onValueChange = { v -> prefs.update { it.copy(customLlmModel = v) } },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.custom_llm_model)) },
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = config.customLlmApiKey,
                                onValueChange = { v -> prefs.update { it.copy(customLlmApiKey = v) } },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.custom_llm_key)) },
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
                        text = stringResource(R.string.polish_rules_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.rule_gemini_title),
                        subtitle = stringResource(R.string.rule_gemini_sub),
                        checked = config.geminiPolishEnabled,
                        onCheckedChange = { v -> prefs.update { it.copy(geminiPolishEnabled = v) } }
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.rule_punct_title),
                        subtitle = stringResource(R.string.rule_punct_sub),
                        checked = config.autoPunctuation,
                        onCheckedChange = { v -> prefs.update { it.copy(autoPunctuation = v) } }
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.rule_filler_title),
                        subtitle = stringResource(R.string.rule_filler_sub),
                        checked = config.removeFillerWords,
                        onCheckedChange = { v -> prefs.update { it.copy(removeFillerWords = v) } }
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.rule_context_title),
                        subtitle = stringResource(R.string.rule_context_sub),
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
