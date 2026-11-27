package com.dx9901mk5.pixelvoiceime

import android.app.Application
import com.dx9901mk5.pixelvoiceime.ai.GeminiPolisher
import com.dx9901mk5.pixelvoiceime.ai.LocalLlmPolisher
import com.dx9901mk5.pixelvoiceime.ai.OpenAiCompatibleClient
import com.dx9901mk5.pixelvoiceime.asr.LocalSherpaAsrEngine
import com.dx9901mk5.pixelvoiceime.asr.VoiceprintManager
import com.dx9901mk5.pixelvoiceime.billing.ProEntitlementManager
import com.dx9901mk5.pixelvoiceime.data.ApiKeyStore
import com.dx9901mk5.pixelvoiceime.data.CustomDictionaryStore
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.dx9901mk5.pixelvoiceime.updater.AutoUpdateWorker
import com.dx9901mk5.pixelvoiceime.updater.GitHubUpdater

class PixelVoiceApp : Application() {

    lateinit var apiKeyStore: ApiKeyStore
        private set
    lateinit var customDictionaryStore: CustomDictionaryStore
        private set
    lateinit var prefs: VoiceImePrefs
        private set
    lateinit var modelManager: OfflineModelManager
        private set
    lateinit var localSherpaAsrEngine: LocalSherpaAsrEngine
        private set
    lateinit var localLlmPolisher: LocalLlmPolisher
        private set
    lateinit var geminiPolisher: GeminiPolisher
        private set
    lateinit var openAiClient: OpenAiCompatibleClient
        private set
    lateinit var updater: GitHubUpdater
        private set
    lateinit var entitlementManager: ProEntitlementManager
        private set
    lateinit var voiceprintManager: VoiceprintManager
        private set

    override fun onCreate() {
        super.onCreate()
        apiKeyStore = ApiKeyStore(this)
        customDictionaryStore = CustomDictionaryStore(this)
        prefs = VoiceImePrefs(this)
        modelManager = OfflineModelManager(this)
        localSherpaAsrEngine = LocalSherpaAsrEngine(modelManager)
        localLlmPolisher = LocalLlmPolisher(this, modelManager)
        geminiPolisher = GeminiPolisher(apiKeyStore)
        openAiClient = OpenAiCompatibleClient()
        updater = GitHubUpdater()
        entitlementManager = ProEntitlementManager(this)
        voiceprintManager = VoiceprintManager(this, modelManager)

        if (!BuildConfig.IS_PLAY_BUILD && prefs.config.value.autoUpdateEnabled) {
            AutoUpdateWorker.schedule(this)
        }
    }
}
