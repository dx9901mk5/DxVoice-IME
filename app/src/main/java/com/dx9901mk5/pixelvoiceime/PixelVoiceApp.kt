package com.dx9901mk5.pixelvoiceime

import android.app.Application
import com.dx9901mk5.pixelvoiceime.ai.GeminiPolisher
import com.dx9901mk5.pixelvoiceime.asr.LocalSherpaAsrEngine
import com.dx9901mk5.pixelvoiceime.data.ApiKeyStore
import com.dx9901mk5.pixelvoiceime.data.VoiceImePrefs
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.dx9901mk5.pixelvoiceime.updater.AutoUpdateWorker
import com.dx9901mk5.pixelvoiceime.updater.GitHubUpdater

class PixelVoiceApp : Application() {

    lateinit var apiKeyStore: ApiKeyStore
        private set
    lateinit var prefs: VoiceImePrefs
        private set
    lateinit var modelManager: OfflineModelManager
        private set
    lateinit var localSherpaAsrEngine: LocalSherpaAsrEngine
        private set
    lateinit var geminiPolisher: GeminiPolisher
        private set
    lateinit var updater: GitHubUpdater
        private set

    override fun onCreate() {
        super.onCreate()
        apiKeyStore = ApiKeyStore(this)
        prefs = VoiceImePrefs(this)
        modelManager = OfflineModelManager(this)
        localSherpaAsrEngine = LocalSherpaAsrEngine(modelManager)
        geminiPolisher = GeminiPolisher(apiKeyStore)
        updater = GitHubUpdater()

        if (prefs.config.value.autoUpdateEnabled) {
            AutoUpdateWorker.schedule(this)
        }
    }
}
