package com.dx9901mk5.pixelvoiceime.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Direct 16kHz PCM AudioRecord capture with lightweight RMS Voice Activity Detection (VAD).
 * Automatically detects speech onset, trailing silence, and no-speech timeout,
 * producing clean PCM bytes for Gemini Audio multimodal transcription or offline ONNX models.
 */
class LocalAudioCaptureEngine {

    interface Callbacks {
        fun onAmplitude(normalized: Float, speechDetected: Boolean)
        fun onCaptured(pcmBytes: ByteArray)
        fun onNoSpeechTimeout()
        fun onError(message: String)
    }

    private var captureJob: Job? = null
    @Volatile
    private var manualFinishRequested: Boolean = false

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        silenceTimeoutMs: Long,
        noSpeechTimeoutMs: Long,
        callbacks: Callbacks
    ) {
        stop()
        manualFinishRequested = false

        captureJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                withContext(Dispatchers.Main) { callbacks.onError("裝置不支援 16kHz 麥克風錄音") }
                return@launch
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
                withContext(Dispatchers.Main) {
                    callbacks.onError(e.localizedMessage ?: "無法開啟麥克風")
                }
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                withContext(Dispatchers.Main) { callbacks.onError("麥克風初始化失敗") }
                return@launch
            }

            val pcmOut = ByteArrayOutputStream()
            val frameBuffer = ByteArray(1600) // ~50ms at 16kHz 16-bit mono
            val startTime = System.currentTimeMillis()
            var speechStarted = false
            var consecutiveSpeechFrames = 0
            var lastSpeechTime = startTime
            var noiseFloorRms = 180f
            var calibratedFrames = 0

            var noiseSuppressor: NoiseSuppressor? = null
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }
            } catch (_: Exception) {
            }

            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(frameBuffer, 0, frameBuffer.size)
                    if (read <= 0) continue

                    pcmOut.write(frameBuffer, 0, read)
                    val rms = computeRms(frameBuffer, read)
                    val now = System.currentTimeMillis()

                    if (calibratedFrames < 6) {
                        noiseFloorRms = if (calibratedFrames == 0) {
                            rms
                        } else {
                            noiseFloorRms * 0.6f + rms * 0.4f
                        }
                        calibratedFrames++
                    } else if (!speechStarted || rms < noiseFloorRms * 1.6f) {
                        noiseFloorRms = (noiseFloorRms * 0.92f + rms * 0.08f).coerceIn(80f, 1600f)
                    }

                    val dynamicThreshold = (noiseFloorRms * 2.6f).coerceIn(380f, 2600f)
                    val normalized = (rms / (dynamicThreshold * 2.5f)).coerceIn(0.05f, 1f)

                    if (calibratedFrames >= 3 && rms >= dynamicThreshold) {
                        consecutiveSpeechFrames++
                        if (speechStarted || consecutiveSpeechFrames >= 2) {
                            speechStarted = true
                            lastSpeechTime = now
                        }
                    } else if (!speechStarted) {
                        consecutiveSpeechFrames = 0
                    }

                    withContext(Dispatchers.Main) {
                        callbacks.onAmplitude(normalized, speechStarted)
                    }

                    if (manualFinishRequested) {
                        break
                    }

                    if (speechStarted && (now - lastSpeechTime) >= silenceTimeoutMs) {
                        break
                    }

                    if (!speechStarted && (now - startTime) >= noSpeechTimeoutMs) {
                        withContext(Dispatchers.Main) { callbacks.onNoSpeechTimeout() }
                        return@launch
                    }

                    // Hard safety cap: 45 seconds per utterance
                    if ((now - startTime) >= 45_000L) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        callbacks.onError(e.localizedMessage ?: "錄音中斷")
                    }
                }
                return@launch
            } finally {
                try {
                    noiseSuppressor?.release()
                } catch (_: Exception) {
                }
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
            }

            if (!isActive) return@launch
            val bytes = pcmOut.toByteArray()
            if (speechStarted && bytes.size > 3200) {
                withContext(Dispatchers.Main) { callbacks.onCaptured(bytes) }
            } else {
                withContext(Dispatchers.Main) { callbacks.onNoSpeechTimeout() }
            }
        }
    }

    fun requestFinish() {
        manualFinishRequested = true
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
    }

    private fun computeRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }
        return if (samples == 0) 0f else sqrt(sum / samples).toFloat()
    }

    companion object {
        private const val SPEECH_RMS_THRESHOLD = 420f
    }
}
