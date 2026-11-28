package com.dx9901mk5.pixelvoiceime.models

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface PackDownloadState {
    data object NotInstalled : PackDownloadState
    data class Downloading(val progress: Float, val statusLabel: String) : PackDownloadState
    data class Installed(val sizeBytes: Long) : PackDownloadState
    data class Error(val message: String) : PackDownloadState
}

/**
 * Manages downloading, unpacking (.tar.bz2), verifying, and deleting offline speech model packs.
 * Languages sharing a multilingual pack automatically reflect Ready status without duplicate downloads.
 */
class OfflineModelManager(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val modelsRoot = File(context.applicationContext.filesDir, "offline_models").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _packStates = MutableStateFlow<Map<String, PackDownloadState>>(emptyMap())
    val packStates: StateFlow<Map<String, PackDownloadState>> = _packStates.asStateFlow()

    private val _enabledLanguages = MutableStateFlow(loadEnabledLanguages())
    val enabledLanguages: StateFlow<Set<String>> = _enabledLanguages.asStateFlow()

    init {
        cleanupLegacyLocalLlm()
        refreshInstalledStates()
    }

    private fun cleanupLegacyLocalLlm() {
        runCatching {
            val legacyDir = File(modelsRoot, VoiceLanguageCatalog.LEGACY_PACK_GEMMA3_1B)
            if (legacyDir.exists()) legacyDir.deleteRecursively()
            val legacyTmp = File(modelsRoot, "${VoiceLanguageCatalog.LEGACY_PACK_GEMMA3_1B}.download.tmp")
            if (legacyTmp.exists()) legacyTmp.delete()
        }
    }

    fun refreshInstalledStates() {
        val states = buildMap {
            VoiceLanguageCatalog.modelPacks.keys.forEach { packId ->
                val dir = File(modelsRoot, packId)
                val readyMarker = File(dir, ".ready")
                if (readyMarker.exists()) {
                    put(packId, PackDownloadState.Installed(calculateDirSize(dir)))
                } else {
                    put(packId, PackDownloadState.NotInstalled)
                }
            }
        }
        _packStates.value = states
    }

    fun isLanguageOfflineReady(bcp47: String): Boolean {
        val item = VoiceLanguageCatalog.findByTag(bcp47)
        return _packStates.value[item.packId] is PackDownloadState.Installed
    }

    fun getPackDirectory(packId: String): File = File(modelsRoot, packId)

    fun isPunctuationReady(): Boolean =
        _packStates.value[VoiceLanguageCatalog.PACK_PUNCTUATION_ZH_EN] is PackDownloadState.Installed

    fun findSenseVoiceFiles(packId: String): Pair<File, File>? {
        val dir = getPackDirectory(packId)
        if (!File(dir, ".ready").exists()) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val model = files.firstOrNull { it.name.equals("model.int8.onnx", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
            ?: return null
        val tokens = files.firstOrNull { it.name.equals("tokens.txt", ignoreCase = true) }
            ?: return null
        return model to tokens
    }

    fun findWhisperFiles(packId: String): Triple<File, File, File>? {
        val dir = getPackDirectory(packId)
        if (!File(dir, ".ready").exists()) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val encoder = files.firstOrNull {
            it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx", ignoreCase = true)
        } ?: return null
        val decoder = files.firstOrNull {
            it.name.contains("decoder", ignoreCase = true) && it.name.endsWith(".onnx", ignoreCase = true)
        } ?: return null
        val tokens = files.firstOrNull {
            it.name.contains("tokens", ignoreCase = true) && it.name.endsWith(".txt", ignoreCase = true)
        } ?: return null
        return Triple(encoder, decoder, tokens)
    }

    fun findPunctuationModelFile(): File? {
        val dir = getPackDirectory(VoiceLanguageCatalog.PACK_PUNCTUATION_ZH_EN)
        if (!File(dir, ".ready").exists()) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.firstOrNull { it.name.equals("model.int8.onnx", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
    }

    fun findSpeakerModelFile(): File? {
        val dir = getPackDirectory(VoiceLanguageCatalog.PACK_SPEAKER_3DSPEAKER)
        if (!File(dir, ".ready").exists()) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val onnx = files.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) }
        if (onnx != null) return onnx
        // Auto-migrate legacy download saved as model.bin (from v3.4.17-v3.4.19) to model.onnx
        val legacyBin = files.firstOrNull { it.name.equals("model.bin", ignoreCase = true) } ?: return null
        val migrated = File(legacyBin.parentFile, "model.onnx")
        return if (legacyBin.renameTo(migrated)) migrated else legacyBin
    }

    fun isLocalLlmReady(): Boolean =
        _packStates.value[VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B] is PackDownloadState.Installed

    fun findLocalLlmModelFile(): File? {
        val dir = getPackDirectory(VoiceLanguageCatalog.PACK_LOCAL_LLM_QWEN25_05B)
        if (!File(dir, ".ready").exists()) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.firstOrNull { it.name.endsWith(".task", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("model.bin", ignoreCase = true) }
    }

    fun enableLanguage(bcp47: String) {
        val next = _enabledLanguages.value + bcp47
        saveEnabledLanguages(next)
    }

    fun disableLanguage(bcp47: String) {
        if (_enabledLanguages.value.size <= 1) return
        val next = _enabledLanguages.value - bcp47
        saveEnabledLanguages(next)
    }

    suspend fun downloadLanguage(bcp47: String): Result<Unit> {
        val item = VoiceLanguageCatalog.findByTag(bcp47)
        enableLanguage(item.bcp47)
        val currentState = _packStates.value[item.packId]
        val mainResult = if (currentState is PackDownloadState.Installed) {
            Result.success(Unit)
        } else {
            downloadPack(item.packId)
        }
        if (mainResult.isSuccess &&
            item.packId == VoiceLanguageCatalog.PACK_SENSEVOICE_CJK &&
            !isPunctuationReady()
        ) {
            downloadPack(VoiceLanguageCatalog.PACK_PUNCTUATION_ZH_EN)
        }
        return mainResult
    }

    suspend fun redownloadLanguage(bcp47: String): Result<Unit> {
        val item = VoiceLanguageCatalog.findByTag(bcp47)
        deletePack(item.packId)
        if (item.packId == VoiceLanguageCatalog.PACK_SENSEVOICE_CJK) {
            deletePack(VoiceLanguageCatalog.PACK_PUNCTUATION_ZH_EN)
        }
        return downloadLanguage(bcp47)
    }

    suspend fun downloadPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val pack = VoiceLanguageCatalog.modelPacks[packId]
            ?: return@withContext Result.failure(IllegalArgumentException("找不到語音包：$packId"))

        updatePackState(packId, PackDownloadState.Downloading(0f, "連線中…"))

        val targetDir = File(modelsRoot, packId)
        val tempArchive = File(modelsRoot, "$packId.download.tmp")

        try {
            val request = Request.Builder()
                .url(pack.downloadUrl)
                .addHeader("User-Agent", "DxVoice-IME")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "下載失敗 HTTP ${response.code}"
                    updatePackState(packId, PackDownloadState.Error(msg))
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("伺服器無回應內容"))
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val fraction = if (totalBytes > 0L) {
                                (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) * 0.85f
                            } else {
                                0.4f
                            }
                            val mb = downloaded / (1024f * 1024f)
                            val totalMb = if (totalBytes > 0L) totalBytes / (1024f * 1024f) else pack.approxSizeMb.toFloat()
                            updatePackState(
                                packId,
                                PackDownloadState.Downloading(
                                    progress = fraction,
                                    statusLabel = String.format("下載中 %.1f / %.0f MB", mb, totalMb)
                                )
                            )
                        }
                    }
                }
            }

            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            if (pack.isArchiveTarBz2) {
                updatePackState(packId, PackDownloadState.Downloading(0.90f, "解壓縮離線語音模型中…"))
                extractTarBz2(tempArchive, targetDir)
            } else {
                val targetName = when {
                    pack.downloadUrl.endsWith(".task", ignoreCase = true) -> "model.task"
                    pack.downloadUrl.endsWith(".onnx", ignoreCase = true) -> "model.onnx"
                    else -> "model.bin"
                }
                tempArchive.copyTo(File(targetDir, targetName), overwrite = true)
            }

            File(targetDir, ".ready").writeText("ok:${System.currentTimeMillis()}")
            tempArchive.delete()

            val sizeBytes = calculateDirSize(targetDir)
            updatePackState(packId, PackDownloadState.Installed(sizeBytes))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            tempArchive.delete()
            updatePackState(packId, PackDownloadState.NotInstalled)
            throw ce
        } catch (e: Exception) {
            tempArchive.delete()
            val msg = e.localizedMessage ?: "離線語音檔下載失敗"
            updatePackState(packId, PackDownloadState.Error(msg))
            Result.failure(e)
        }
    }

    suspend fun deletePack(packId: String) = withContext(Dispatchers.IO) {
        File(modelsRoot, packId).deleteRecursively()
        File(modelsRoot, "$packId.download.tmp").delete()
        updatePackState(packId, PackDownloadState.NotInstalled)
    }

    fun totalInstalledBytes(): Long {
        return _packStates.value.values
            .filterIsInstance<PackDownloadState.Installed>()
            .sumOf { it.sizeBytes }
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry = tis.nextEntry
                        val buffer = ByteArray(16 * 1024)
                        while (entry != null) {
                            val outFile = File(destDir, entry.name)
                            if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                                throw SecurityException("Blocked path traversal in archive: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        val len = tis.read(buffer)
                                        if (len <= 0) break
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun updatePackState(packId: String, state: PackDownloadState) {
        _packStates.update { it + (packId to state) }
    }

    private fun loadEnabledLanguages(): Set<String> {
        val saved = prefs.getStringSet(KEY_ENABLED_LANGS, null)
        return if (saved.isNullOrEmpty()) setOf("zh-TW", "en-US") else saved.toSet()
    }

    private fun saveEnabledLanguages(langs: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_LANGS, langs).apply()
        _enabledLanguages.value = langs
    }

    companion object {
        private const val PREFS_NAME = "pixelvoice_models_prefs"
        private const val KEY_ENABLED_LANGS = "enabled_languages"
    }
}
