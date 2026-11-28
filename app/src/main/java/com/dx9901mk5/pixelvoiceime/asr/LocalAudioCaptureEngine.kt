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

            // Always prefer VOICE_RECOGNITION first; if an OEM HAL yields dead silence,
            // automatically switch to raw MediaRecorder.AudioSource.MIC mid-session.
            val primarySources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION to (hardwareNoiseReduction && noiseFilterMode != NoiseFilterMode.NORMAL),
                MediaRecorder.AudioSource.MIC to false
            )

            val pcmOut = ByteArrayOutputStream()
            val frameBuffer = ByteArray(1600) // ~50ms at 16kHz 16-bit mono
            var startTime = System.currentTimeMillis()
            var speechStarted = false
            var consecutiveSpeechFrames = 0
            var lastSpeechTime = startTime
            var noiseFloorRms = 110f
            var calibratedFrames = 0
            var peakRms = 0f
            // 100Hz 1st-order high-pass filter state (cuts AC hum, wind rumble, traffic bass)
            var hpPrevIn = 0f
            var hpPrevOut = 0f
            val hpAlpha = 0.962f

            val (thresholdMultiplier, thresholdFloor, thresholdCap) = when (noiseFilterMode) {
                NoiseFilterMode.NORMAL -> Triple(1.75f, 130f, 1200f)
                NoiseFilterMode.NOISY -> Triple(2.1f, 240f, 1600f)
                NoiseFilterMode.NEAR_FIELD -> Triple(2.5f, 360f, 2000f)
            }

            var sourceIdx = 0
            var switchedToMicFallback = false
            while (isActive && sourceIdx < primarySources.size) {
                val (audioSource, attachHalEffects) = primarySources[sourceIdx]
                val recorder = try {
                    AudioRecord(
                        audioSource,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                } catch (e: Exception) {
                    if (sourceIdx + 1 < primarySources.size) {
                        sourceIdx++
                        continue
                    }
                    withContext(Dispatchers.Main) {
                        callbacks.onError(e.localizedMessage ?: "無法開啟麥克風")
                    }
                    return@launch
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    if (sourceIdx + 1 < primarySources.size) {
                        sourceIdx++
                        continue
                    }
                    withContext(Dispatchers.Main) { callbacks.onError("麥克風初始化失敗") }
                    return@launch
                }

                var noiseSuppressor: NoiseSuppressor? = null
                var echoCanceler: AcousticEchoCanceler? = null
                if (attachHalEffects) {
                    runCatching {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                                enabled = true
                            }
                        }
                        if (AcousticEchoCanceler.isAvailable()) {
                            echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                                enabled = true
                            }
                        }
                    }
                }

                var retryWithFallbackSource = false
                try {
                    recorder.startRecording()
                    val attemptStartTime = System.currentTimeMillis()
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
                        if (rms > peakRms) peakRms = rms
                        val now = System.currentTimeMillis()

                        // Detect broken OEM VOICE_RECOGNITION / NoiseSuppressor HAL producing dead zeros
                        if (!switchedToMicFallback &&
                            sourceIdx == 0 &&
                            (now - attemptStartTime) >= 1200L &&
                            peakRms < 15f
                        ) {
                            switchedToMicFallback = true
                            retryWithFallbackSource = true
                            pcmOut.reset()
                            calibratedFrames = 0
                            noiseFloorRms = 110f
                            peakRms = 0f
                            hpPrevIn = 0f
                            hpPrevOut = 0f
                            startTime = now
                            lastSpeechTime = now
                            break
                        }

                        // Min-tracking noise floor calibration: never let immediate user speech
                        // during the first 200ms poison the ambient noise floor.
                        if (calibratedFrames < 4) {
                            if (rms > 1f) {
                                noiseFloorRms = if (calibratedFrames == 0) {
                                    rms.coerceAtMost(320f)
                                } else {
                                    minOf(noiseFloorRms, rms).coerceIn(45f, 320f)
                                }
                                calibratedFrames++
                            }
                        } else if (!speechStarted && rms < noiseFloorRms * 1.5f) {
                            noiseFloorRms = (noiseFloorRms * 0.90f + rms * 0.10f).coerceIn(45f, 480f)
                        } else if (rms < noiseFloorRms) {
                            noiseFloorRms = (noiseFloorRms * 0.85f + rms * 0.15f).coerceIn(45f, 480f)
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

                if (retryWithFallbackSource) {
                    sourceIdx++
                    continue
                }
                break
            }

            if (!isActive) return@launch
            val bytes = pcmOut.toByteArray()
            val hasSoftManualSpeech = manualFinishRequested && peakRms >= 80f && bytes.size > 3200
            if ((speechStarted || hasSoftManualSpeech) && bytes.size > 3200) {
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
