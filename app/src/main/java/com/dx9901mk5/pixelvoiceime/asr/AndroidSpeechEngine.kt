package com.dx9901mk5.pixelvoiceime.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's SpeechRecognizer with offline-preference, partial result streaming,
 * and RMS amplitude callbacks for the IME waveform.
 */
class AndroidSpeechEngine(
    private val context: Context
) {
    interface Callbacks {
        fun onReady()
        fun onAmplitude(normalized: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int, message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var lastPartial: String = ""
    private var active: Boolean = false

    fun isAvailable(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(context)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    fun start(
        languageTag: String,
        preferOffline: Boolean,
        silenceTimeoutMs: Long,
        callbacks: Callbacks
    ) {
        stop()
        if (!isAvailable()) {
            callbacks.onError(SpeechRecognizer.ERROR_CLIENT, "此裝置未安裝系統語音辨識服務")
            return
        }

        lastPartial = ""
        active = true

        val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
            (preferOffline || !SpeechRecognizer.isRecognitionAvailable(context))

        val sr = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active) callbacks.onReady()
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (!active) return
                // rmsdB typically ranges from -2 to 10
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                callbacks.onAmplitude(norm)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!active) return
                active = false
                // If we already captured partial speech and hit speech-timeout/no-match, emit partial!
                if (lastPartial.isNotBlank() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    val rescued = lastPartial
                    lastPartial = ""
                    callbacks.onFinal(rescued)
                    return
                }
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "麥克風收音異常"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "尚未授予麥克風權限"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路連線逾時（請下載離線語音包或檢查網路）"
                    SpeechRecognizer.ERROR_NO_MATCH -> "沒有偵測到清晰語音"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待說話逾時"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音辨識服務忙碌中"
                    else -> "語音辨識錯誤 ($error)"
                }
                callbacks.onError(error, msg)
            }

            override fun onResults(results: Bundle?) {
                if (!active) return
                active = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartial }
                lastPartial = ""
                if (best.isNotBlank()) {
                    callbacks.onFinal(best)
                } else {
                    callbacks.onError(SpeechRecognizer.ERROR_NO_MATCH, "沒有辨識到文字")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!active) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    lastPartial = text
                    callbacks.onPartial(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceTimeoutMs
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceTimeoutMs
            )
        }
        sr.startListening(intent)
    }

    fun finishListening() {
        if (!active) return
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        active = false
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
