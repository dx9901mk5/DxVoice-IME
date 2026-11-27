package com.dx9901mk5.pixelvoiceime.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dx9901mk5.pixelvoiceime.models.OfflineModelManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

data class VoiceprintVerifyResult(
    val matched: Boolean,
    val similarity: Float
)

/**
 * On-device speaker verification (voiceprint) manager powered by Sherpa-ONNX 3D-Speaker.
 * All voiceprint embeddings are stored 100% locally in SharedPreferences and never uploaded.
 */
class VoiceprintManager(
    context: Context,
    private val modelManager: OfflineModelManager
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val extractorMutex = Mutex()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var loadedModelPath: String? = null

    private val _enrolledCount = MutableStateFlow(countEnrolledSamples())
    val enrolledCount: StateFlow<Int> = _enrolledCount.asStateFlow()

    fun isModelReady(): Boolean = modelManager.findSpeakerModelFile() != null

    fun isEnrolled(): Boolean = _enrolledCount.value >= REQUIRED_SAMPLES

    private fun countEnrolledSamples(): Int {
        var c = 0
        for (i in 0 until REQUIRED_SAMPLES) {
            if (!prefs.getString(keySample(i), null).isNullOrBlank()) c++
        }
        return c
    }

    fun clearEnrollment() {
        val editor = prefs.edit()
        for (i in 0 until REQUIRED_SAMPLES) {
            editor.remove(keySample(i))
        }
        editor.apply()
        _enrolledCount.value = 0
    }

    /**
     * Records a ~3.2s voice sample for enrollment step [sampleIndex] (0..2),
     * extracts its 3D-Speaker embedding vector, and saves it locally.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndEnrollSample(
        sampleIndex: Int,
        durationMs: Long = 3200L,
        onProgress: (progress: Float, amplitude: Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isModelReady()) {
            return@withContext Result.failure(IllegalStateException("聲紋模型尚未下載"))
        }
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            return@withContext Result.failure(IllegalStateException("裝置不支援 16kHz 錄音"))
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext Result.failure(IllegalStateException("麥克風初始化失敗"))
        }

        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }

        val pcmOut = ByteArrayOutputStream()
        val buf = ByteArray(1600) // 50ms
        val startMs = System.currentTimeMillis()
        var peakRms = 0f

        try {
            recorder.startRecording()
            while (true) {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= durationMs) break
                val read = recorder.read(buf, 0, buf.size)
                if (read <= 0) continue
                pcmOut.write(buf, 0, read)
                val rms = computeRms(buf, read)
                if (rms > peakRms) peakRms = rms
                val normAmp = (rms / 2500f).coerceIn(0.05f, 1f)
                val prog = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                withContext(Dispatchers.Main) { onProgress(prog, normAmp) }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        } finally {
            runCatching { ns?.release() }
            runCatching { aec?.release() }
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (peakRms < 300f) {
            return@withContext Result.failure(IllegalStateException("收音音量過小，請靠近麥克風清晰朗讀範本句子"))
        }

        val embedding = extractEmbedding(pcmOut.toByteArray())
            ?: return@withContext Result.failure(IllegalStateException("聲紋特徵萃取失敗，請重試"))

        val serialized = embedding.joinToString(",")
        prefs.edit().putString(keySample(sampleIndex), serialized).apply()
        _enrolledCount.value = countEnrolledSamples()
        withContext(Dispatchers.Main) { onProgress(1f, 0.1f) }
        Result.success(Unit)
    }

    /**
     * Verifies whether [pcmBytes] belongs to the enrolled user.
     * Uses max(centroidSimilarity, bestSampleSimilarity) for robust matching across speaking tones.
     */
    suspend fun verifySpeaker(
        pcmBytes: ByteArray,
        threshold: Float
    ): VoiceprintVerifyResult = withContext(Dispatchers.IO) {
        val enrolled = loadEnrolledEmbeddings()
        if (enrolled.isEmpty() || !isModelReady()) {
            return@withContext VoiceprintVerifyResult(matched = true, similarity = 1f)
        }
        val candidate = extractEmbedding(pcmBytes)
            ?: return@withContext VoiceprintVerifyResult(matched = true, similarity = 1f)

        val dim = candidate.size
        val centroid = FloatArray(dim)
        var bestSampleSim = -1f
        for (vec in enrolled) {
            if (vec.size != dim) continue
            val sim = cosineSimilarity(candidate, vec)
            if (sim > bestSampleSim) bestSampleSim = sim
            for (i in 0 until dim) centroid[i] += vec[i]
        }
        l2NormalizeInPlace(centroid)
        val centroidSim = cosineSimilarity(candidate, centroid)
        val finalSim = maxOf(centroidSim, bestSampleSim)
        VoiceprintVerifyResult(
            matched = finalSim >= threshold,
            similarity = finalSim
        )
    }

    private suspend fun extractEmbedding(pcmBytes: ByteArray): FloatArray? {
        val ext = ensureExtractor() ?: return null
        val samples = trimAndConvertPcm(pcmBytes)
        if (samples.size < 4800) return null // require >= 0.3s of speech
        val stream = ext.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            if (!ext.isReady(stream)) return null
            val raw = ext.compute(stream)
            if (raw.isEmpty()) return null
            l2NormalizeInPlace(raw)
            raw
        } catch (_: Throwable) {
            null
        } finally {
            stream.release()
        }
    }

    private suspend fun ensureExtractor(): SpeakerEmbeddingExtractor? = extractorMutex.withLock {
        val modelFile = modelManager.findSpeakerModelFile() ?: return null
        if (extractor != null && loadedModelPath == modelFile.absolutePath) {
            return extractor
        }
        runCatching { extractor?.release() }
        extractor = null
        loadedModelPath = null
        return try {
            val config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val created = SpeakerEmbeddingExtractor(assetManager = null, config = config)
            extractor = created
            loadedModelPath = modelFile.absolutePath
            created
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadEnrolledEmbeddings(): List<FloatArray> {
        val list = mutableListOf<FloatArray>()
        for (i in 0 until REQUIRED_SAMPLES) {
            val raw = prefs.getString(keySample(i), null) ?: continue
            val parsed = raw.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (parsed.isNotEmpty()) {
                l2NormalizeInPlace(parsed)
                list.add(parsed)
            }
        }
        return list
    }

    private fun trimAndConvertPcm(pcmBytes: ByteArray): FloatArray {
        val frameBytes = 1600
        val count = pcmBytes.size / frameBytes
        if (count <= 0) return pcm16ToFloat(pcmBytes, 0, pcmBytes.size)
        val rmsList = FloatArray(count) { idx ->
            computeRms(pcmBytes, frameBytes, idx * frameBytes)
        }
        val peak = rmsList.maxOrNull() ?: 0f
        val thresh = maxOf(240f, peak * 0.25f)
        val first = rmsList.indexOfFirst { it >= thresh }.coerceAtLeast(0)
        val last = rmsList.indexOfLast { it >= thresh }.let { if (it < first) count - 1 else it }
        val startByte = ((first - 2).coerceAtLeast(0)) * frameBytes
        val endByte = (((last + 3) * frameBytes).coerceAtMost(pcmBytes.size))
        return pcm16ToFloat(pcmBytes, startByte, endByte)
    }

    private fun pcm16ToFloat(buf: ByteArray, start: Int, end: Int): FloatArray {
        val len = ((end - start) / 2).coerceAtLeast(0)
        val out = FloatArray(len)
        for (i in 0 until len) {
            val idx = start + i * 2
            val lo = buf[idx].toInt() and 0xFF
            val hi = buf[idx + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768.0f
        }
        return out
    }

    private fun computeRms(buf: ByteArray, length: Int, offset: Int = 0): Float {
        var sum = 0.0
        var n = 0
        var i = offset
        while (i + 1 < offset + length && i + 1 < buf.size) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            sum += s.toDouble() * s.toDouble()
            n++
            i += 2
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (norm > 1e-6f) {
            for (i in v.indices) v[i] /= norm
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun release() {
        runCatching { extractor?.release() }
        extractor = null
        loadedModelPath = null
    }

    private fun keySample(index: Int) = "voiceprint_sample_$index"

    companion object {
        const val REQUIRED_SAMPLES = 3
        private const val PREFS_NAME = "dxvoice_voiceprint_prefs"
    }
}
