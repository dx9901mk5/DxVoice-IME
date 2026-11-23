package com.dx9901mk5.pixelvoiceime.asr

import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.dx9901mk5.pixelvoiceime.models.VoiceLanguageCatalog
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * High-speed on-device Sherpa-ONNX ASR + CT-Transformer punctuation engine.
 * Caches loaded native models in memory for instant startup and zero Google Speech dependency.
 */
class LocalSherpaAsrEngine(
    private val modelManager: OfflineModelManager
) {
    private val recognizerMutex = Mutex()
    private val punctMutex = Mutex()
    private val tagRegex = Regex("""<\|[^|]+\|>""")
    private var loadedPackId: String? = null
    private var loadedLanguageHint: String? = null
    private var recognizer: OfflineRecognizer? = null
    private var punctuation: OfflinePunctuation? = null
    private var loadedPunctPath: String? = null

    fun isReadyForLanguage(bcp47: String): Boolean {
        val item = VoiceLanguageCatalog.findByTag(bcp47)
        return when (item.packId) {
            VoiceLanguageCatalog.PACK_SENSEVOICE_CJK ->
                modelManager.findSenseVoiceFiles(item.packId) != null
            else ->
                modelManager.findWhisperFiles(item.packId) != null
        }
    }

    suspend fun preload(bcp47: String) = withContext(Dispatchers.IO) {
        ensureRecognizer(bcp47)
        ensurePunctuation()
    }

    suspend fun transcribePcm16(
        pcmBytes: ByteArray,
        bcp47: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val rec = ensureRecognizer(bcp47)
            ?: return@withContext Result.failure(IllegalStateException("尚未下載本地離線語音模型包"))

        val segments = splitPcmBySilence(pcmBytes)
        if (segments.isEmpty()) {
            return@withContext Result.success("")
        }

        val decodedParts = mutableListOf<String>()
        for (seg in segments) {
            val samples = pcm16ToFloatArray(seg)
            if (samples.size < 1600) continue
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)
                val rawText = rec.getResult(stream).text
                if (rawText.contains("<|nospeech|>", ignoreCase = true) ||
                    rawText.contains("<|BGM|>", ignoreCase = true)
                ) {
                    continue
                }
                val text = rawText
                    .replace(tagRegex, "")
                    .trim(' ', '\t', '\n', '\r', '，', '。', '？', '！', '、', ',', '.', '?', '!')
                if (text.isNotBlank() && text.any { it.isLetterOrDigit() }) {
                    decodedParts.add(text)
                }
            } finally {
                stream.release()
            }
        }

        val isCjk = bcp47.startsWith("zh", ignoreCase = true) ||
            bcp47.startsWith("ja", ignoreCase = true) ||
            bcp47.startsWith("ko", ignoreCase = true) ||
            bcp47.startsWith("yue", ignoreCase = true)
        val joined = if (isCjk) {
            decodedParts.joinToString("，")
        } else {
            decodedParts.joinToString(", ")
        }
        Result.success(joined)
    }

    suspend fun getPunctuationModel(): OfflinePunctuation? = withContext(Dispatchers.IO) {
        ensurePunctuation()
    }

    private suspend fun ensureRecognizer(bcp47: String): OfflineRecognizer? = recognizerMutex.withLock {
        val item = VoiceLanguageCatalog.findByTag(bcp47)
        val langHint = mapLanguageHint(bcp47)
        if (recognizer != null && loadedPackId == item.packId && loadedLanguageHint == langHint) {
            return recognizer
        }

        runCatching { recognizer?.release() }
        recognizer = null
        loadedPackId = null
        loadedLanguageHint = null

        val modelConfig = when (item.packId) {
            VoiceLanguageCatalog.PACK_SENSEVOICE_CJK -> {
                val (modelFile, tokensFile) = modelManager.findSenseVoiceFiles(item.packId) ?: return null
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = langHint,
                        useInverseTextNormalization = true
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu"
                )
            }
            else -> {
                val (encoder, decoder, tokens) = modelManager.findWhisperFiles(item.packId) ?: return null
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        language = langHint,
                        task = "transcribe"
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu"
                )
            }
        }

        val config = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
        return try {
            val created = OfflineRecognizer(assetManager = null, config = config)
            recognizer = created
            loadedPackId = item.packId
            loadedLanguageHint = langHint
            created
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun ensurePunctuation(): OfflinePunctuation? = punctMutex.withLock {
        val file = modelManager.findPunctuationModelFile() ?: return null
        if (punctuation != null && loadedPunctPath == file.absolutePath) {
            return punctuation
        }
        runCatching { punctuation?.release() }
        punctuation = null
        loadedPunctPath = null

        val config = OfflinePunctuationConfig(
            model = OfflinePunctuationModelConfig(
                ctTransformer = file.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return try {
            val created = OfflinePunctuation(assetManager = null, config = config)
            punctuation = created
            loadedPunctPath = file.absolutePath
            created
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        punctuation?.release()
        punctuation = null
        loadedPackId = null
        loadedLanguageHint = null
        loadedPunctPath = null
    }

    private fun mapLanguageHint(bcp47: String): String {
        val lower = bcp47.lowercase()
        return when {
            lower.startsWith("yue") -> "yue"
            lower.startsWith("zh") -> "auto"
            lower.startsWith("en") -> "en"
            lower.startsWith("ja") -> "ja"
            lower.startsWith("ko") -> "ko"
            else -> lower.substringBefore('-')
        }
    }

    /**
     * Splits 16kHz 16-bit mono PCM bytes into speech segments whenever internal silence >= 450ms,
     * using an adaptive noise floor computed from the recording itself.
     */
    private fun splitPcmBySilence(pcmBytes: ByteArray): List<ByteArray> {
        val frameBytes = 1600 // 50ms at 16kHz 16-bit mono
        val minSilenceFrames = 9 // 9 * 50ms = 450ms
        val minSpeechFramesPerSegment = 4 // 4 * 50ms = 200ms

        val frameRmsList = ArrayList<Float>(pcmBytes.size / frameBytes + 1)
        var pos = 0
        while (pos + frameBytes <= pcmBytes.size) {
            frameRmsList.add(frameRms(pcmBytes, pos, frameBytes))
            pos += frameBytes
        }
        if (frameRmsList.isEmpty()) {
            return emptyList()
        }

        val sortedRms = frameRmsList.sorted()
        val noiseFloor = sortedRms[(sortedRms.size * 0.2f).toInt().coerceIn(0, sortedRms.lastIndex)]
        val speechThresholdRms = (noiseFloor * 2.2f).coerceIn(260f, 2200f)

        val segments = mutableListOf<ByteArray>()
        var segStart = -1
        var lastVoiceEnd = -1
        var silentFrames = 0
        var activeSpeechFramesInSeg = 0

        for (idx in frameRmsList.indices) {
            val framePos = idx * frameBytes
            val rms = frameRmsList[idx]
            if (rms >= speechThresholdRms) {
                if (segStart < 0) {
                    segStart = (framePos - frameBytes * 2).coerceAtLeast(0)
                    activeSpeechFramesInSeg = 0
                }
                activeSpeechFramesInSeg++
                lastVoiceEnd = (framePos + frameBytes * 2).coerceAtMost(pcmBytes.size)
                silentFrames = 0
            } else if (segStart >= 0) {
                silentFrames++
                if (silentFrames >= minSilenceFrames) {
                    if (activeSpeechFramesInSeg >= minSpeechFramesPerSegment && lastVoiceEnd > segStart + 3200) {
                        segments.add(pcmBytes.copyOfRange(segStart, lastVoiceEnd))
                    }
                    segStart = -1
                    lastVoiceEnd = -1
                    silentFrames = 0
                    activeSpeechFramesInSeg = 0
                }
            }
        }

        if (segStart >= 0 && activeSpeechFramesInSeg >= minSpeechFramesPerSegment && lastVoiceEnd > segStart + 3200) {
            segments.add(pcmBytes.copyOfRange(segStart, lastVoiceEnd))
        }
        return segments
    }

    private fun frameRms(buf: ByteArray, offset: Int, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = offset
        while (i + 1 < offset + len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample * sample.toDouble()
            count++
            i += 2
        }
        return if (count == 0) 0f else sqrt(sum / count).toFloat()
    }

    private fun pcm16ToFloatArray(pcmBytes: ByteArray): FloatArray {
        val count = pcmBytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            out[i] = sample / 32768.0f
        }
        return out
    }
}
