package com.dx9901mk5.pixelvoiceime.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.dx9901mk5.pixelvoiceime.data.NoiseFilterMode
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
        noiseFilterMode: NoiseFilterMode = NoiseFilterMode.NORMAL,
        hardwareNoiseReduction: Boolean = true,
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

            // Always prefer VOICE_RECOGNITION to avoid severe mic gain attenuation caused by
            // VOICE_COMMUNICATION on many Android OEMs (Vivo/Oppo/Samsung), which caused false
            // "no speech detected" timeouts.
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

            val recorder = try {
                AudioRecord(
                    audioSource,
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
            var noiseFloorRms = 140f
            var calibratedFrames = 0
            // 100Hz 1st-order high-pass filter state (cuts AC hum, wind rumble, traffic bass)
            var hpPrevIn = 0f
            var hpPrevOut = 0f
            val hpAlpha = 0.962f

            val (thresholdMultiplier, thresholdFloor, thresholdCap) = when (noiseFilterMode) {
                NoiseFilterMode.NORMAL -> Triple(2.0f, 220f, 1400f)
                NoiseFilterMode.NOISY -> Triple(2.4f, 340f, 1800f)
                NoiseFilterMode.NEAR_FIELD -> Triple(2.8f, 480f, 2200f)
            }

            var noiseSuppressor: NoiseSuppressor? = null
            var echoCanceler: AcousticEchoCanceler? = null
            if (hardwareNoiseReduction) {
                runCatching {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                            enabled = true
                        }
                    }
                    if (noiseFilterMode != NoiseFilterMode.NORMAL && AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                            enabled = true
                        }
                    }
                }
            }

            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(frameBuffer, 0, frameBuffer.size)
                    if (read <= 0) continue

                    if (hardwareNoiseReduction) {
                        var i = 0
                        while (i + 1 < read) {
                            val lo = frameBuffer[i].toInt() and 0xFF
                            val hi = frameBuffer[i + 1].toInt()
                            val x = ((hi shl 8) or lo).toFloat()
                            val y = hpAlpha * (hpPrevOut + x - hpPrevIn)
                            hpPrevIn = x
                            hpPrevOut = y
                            val clamped = y.toInt().coerceIn(-32768, 32767)
                            frameBuffer[i] = (clamped and 0xFF).toByte()
                            frameBuffer[i + 1] = ((clamped shr 8) and 0xFF).toByte()
                            i += 2
                        }
                    }

                    pcmOut.write(frameBuffer, 0, read)
                    val rms = computeRms(frameBuffer, read)
                    val now = System.currentTimeMillis()

                    // Min-tracking noise floor calibration: never let immediate user speech
                    // during the first 200ms poison the ambient noise floor.
                    if (calibratedFrames < 4) {
                        noiseFloorRms = if (calibratedFrames == 0) {
                            rms.coerceAtMost(350f)
                        } else {
                            minOf(noiseFloorRms, rms).coerceIn(60f, 350f)
                        }
                        calibratedFrames++
                    } else if (!speechStarted && rms < noiseFloorRms * 1.5f) {
                        noiseFloorRms = (noiseFloorRms * 0.90f + rms * 0.10f).coerceIn(60f, 520f)
                    } else if (rms < noiseFloorRms) {
                        noiseFloorRms = (noiseFloorRms * 0.85f + rms * 0.15f).coerceIn(60f, 520f)
                    }

                    val dynamicThreshold = (noiseFloorRms * thresholdMultiplier).coerceIn(thresholdFloor, thresholdCap)
                    val normalized = (rms / (dynamicThreshold * 2.2f)).coerceIn(0.05f, 1f)

                    if (rms >= dynamicThreshold) {
                        consecutiveSpeechFrames++
                        if (speechStarted || consecutiveSpeechFrames >= 2 || rms >= dynamicThreshold * 1.35f) {
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
                    echoCanceler?.release()
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
