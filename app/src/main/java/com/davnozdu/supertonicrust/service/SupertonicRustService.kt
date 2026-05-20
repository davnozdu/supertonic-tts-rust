package com.davnozdu.supertonicrust.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.davnozdu.supertonicrust.SupertonicRust
import java.util.Locale

/**
 * System TTS engine endpoint. Currently a minimal shim — accepts requests
 * from Android (Moon+ Reader / MacroDroid / Accessibility), runs them
 * through the Rust text pipeline, and... emits silence until the engine
 * integration lands in the next iteration.
 *
 * Only Russian is advertised right now. Full multi-language support comes
 * back once the model assets are in place.
 */
class SupertonicRustService : TextToSpeechService() {

    private val voices = mutableListOf<Voice>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created; native v${SupertonicRust.nativeVersion()}")
        val locale = Locale("ru", "RU")
        voices.add(
            Voice(
                "ru-supertonic-F3",
                locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf()
            )
        )
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.equals("rus", ignoreCase = true) ||
            lang.equals("ru", ignoreCase = true)
        ) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> = arrayOf("rus", "RUS", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onLoadVoice(voiceName: String?): Int =
        if (voiceName == "ru-supertonic-F3") TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onGetDefaultVoiceNameFor(
        lang: String?, country: String?, variant: String?
    ): String = "ru-supertonic-F3"

    override fun onGetVoices(): List<Voice> = voices.toList()

    override fun onStop() {
        // No in-flight synthesis to cancel in the MVP scaffold yet.
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val raw = request.charSequenceText?.toString() ?: return
        val processed = SupertonicRust.processText(raw)
        Log.i(TAG, "onSynthesizeText: $raw -> $processed (stub, no audio yet)")
        // Tell Android we're done. Without real audio Moon+ Reader will move
        // immediately to the next utterance, which is acceptable for MVP.
        callback.start(22050, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        callback.done()
    }

    companion object {
        private const val TAG = "SupertonicRustService"
    }
}
