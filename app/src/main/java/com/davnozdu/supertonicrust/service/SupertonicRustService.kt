package com.davnozdu.supertonicrust.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.davnozdu.supertonicrust.AssetDownloader
import com.davnozdu.supertonicrust.SupertonicRust
import com.davnozdu.supertonicrust.accent.AccentDictionaryManager
import java.io.File
import java.util.Locale

/**
 * System TTS engine endpoint.
 *
 * Pulls text from the Android framework, normalises it with the Rust
 * pipeline, runs ONNX inference, and streams PCM back through the
 * `SynthesisCallback` chunked at `callback.maxBufferSize`.
 *
 * The model bundle and accent dictionary must already be on disk —
 * downloaded by `MainActivity` on first launch. If they're not,
 * `onLoadLanguage` reports `LANG_MISSING_DATA` and synthesis returns
 * an error; the user is expected to open the app once to fetch the
 * ~398 MB of model files before relying on the engine.
 */
class SupertonicRustService : TextToSpeechService() {

    @Volatile private var stopRequested = false
    private val voices = buildVoiceList()
    private val voiceByName = voices.associateBy { it.name }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created; native v${SupertonicRust.nativeVersion()}")
        // Best-effort warm-up: if the assets are already on disk, init
        // the engine now so the first onSynthesizeText() doesn't pay the
        // 5-8 second ORT session-build cost while Android is impatiently
        // waiting for audio.
        ensureEngine(logFailure = false)
        // Kick off the accent dictionary mmap in parallel. apply() is a
        // no-op until the loader thread finishes, so the first request
        // may go unstressed, but every subsequent one is properly
        // marked.
        AccentDictionaryManager.load(this)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!isRussian(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
        if (!AssetDownloader.isReady(this)) return TextToSpeech.LANG_MISSING_DATA
        return when {
            country.equals("RUS", ignoreCase = true) || country.equals("RU", ignoreCase = true) ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("rus", "RUS", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val avail = onIsLanguageAvailable(lang, country, variant)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            return avail
        }
        ensureEngine(logFailure = true)
        return avail
    }

    override fun onLoadVoice(voiceName: String?): Int =
        if (voiceName != null && voiceByName.containsKey(voiceName)) {
            ensureEngine(logFailure = true)
            TextToSpeech.SUCCESS
        } else TextToSpeech.ERROR

    override fun onGetDefaultVoiceNameFor(
        lang: String?, country: String?, variant: String?
    ): String = DEFAULT_VOICE

    override fun onGetVoices(): List<Voice> = voices.toList()

    override fun onStop() {
        Log.i(TAG, "onStop: cancelling current utterance")
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val raw = request.charSequenceText?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        if (!AssetDownloader.isReady(this)) {
            Log.w(TAG, "Assets missing — refusing to synthesise")
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }

        if (!ensureEngine(logFailure = true)) {
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }

        val voiceName = request.voiceName?.takeIf { voiceByName.containsKey(it) } ?: DEFAULT_VOICE
        val voicePath = voicePathFor(voiceName) ?: run {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val speed = speedFromRate(request.speechRate)
        val steps = 5

        val sampleRate = SupertonicRust.getSampleRate(SupertonicRust.handle())
        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            callback.error(TextToSpeech.ERROR_OUTPUT)
            return
        }

        // Apply stress marks + ё restoration from the .sacc dictionary
        // first, then hand the result to the Rust text pipeline. Wrap
        // both in try/catch so a malformed dictionary entry doesn't
        // bring the synthesis down.
        val withStress = try {
            AccentDictionaryManager.apply(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "AccentDict apply failed: ${t.message}", t)
            raw
        }
        val processed = try {
            SupertonicRust.processText(withStress)
        } catch (t: Throwable) {
            Log.e(TAG, "processText failed: ${t.message}", t)
            withStress
        }

        val streamer = ChunkedStreamer(callback) { stopRequested }
        try {
            SupertonicRust.synthesize(
                callback = streamer,
                engine = SupertonicRust.handle(),
                text = processed,
                lang = "ru",
                stylePath = voicePath,
                speed = speed,
                bufferSeconds = 0f,
                steps = steps,
                gain = 1.0f
            )
            // Whatever streamer didn't flush (Rust delivered a final PCM
            // blob via the return value rather than notifyAudioChunk) is
            // handled by ChunkedStreamer.flushTail() — most paths today
            // deliver per-sentence chunks, so this is a no-op fast path.
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed: ${t.message}", t)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        callback.done()
    }

    private fun ensureEngine(logFailure: Boolean): Boolean {
        if (SupertonicRust.handle() != 0L) return true
        if (!AssetDownloader.isReady(this)) {
            if (logFailure) Log.w(TAG, "Assets not ready — engine init skipped")
            return false
        }
        val modelDir = File(filesDir, "${AssetDownloader.MODEL_DIR}/onnx").absolutePath
        val libDir = applicationInfo.nativeLibraryDir
        val ok = SupertonicRust.ensureEngineInitialised(modelDir, libDir, ortThreads = 2, xnnThreads = 2)
        if (!ok && logFailure) Log.e(TAG, "Engine init failed")
        return ok
    }

    private fun voicePathFor(voiceName: String): String? {
        // voiceName is e.g. "ru-supertonic-F3"; the matching JSON file is
        // .../voice_styles/F3.json
        val tag = voiceName.substringAfterLast('-')
        val file = File(filesDir, "${AssetDownloader.MODEL_DIR}/voice_styles/$tag.json")
        return if (file.exists()) file.absolutePath else null
    }

    private fun speedFromRate(rate: Int): Float {
        // Android passes 100 for "normal", 200 for 2x, 50 for 0.5x — clamp
        // to the model's stable range.
        val raw = if (rate <= 0) 1.0f else rate / 100.0f
        return raw.coerceIn(0.7f, 1.5f)
    }

    private fun isRussian(lang: String?): Boolean =
        lang != null && (lang.equals("rus", ignoreCase = true) || lang.equals("ru", ignoreCase = true))

    private fun buildVoiceList(): MutableList<Voice> {
        val locale = Locale("ru", "RU")
        val voiceNames = listOf(
            "ru-supertonic-F1", "ru-supertonic-F2", "ru-supertonic-F3",
            "ru-supertonic-F4", "ru-supertonic-F5",
            "ru-supertonic-M1", "ru-supertonic-M2", "ru-supertonic-M3",
            "ru-supertonic-M4", "ru-supertonic-M5",
        )
        return voiceNames.mapTo(mutableListOf()) { name ->
            Voice(
                name,
                locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf()
            )
        }
    }

    companion object {
        private const val TAG = "SupertonicRustSvc"
        private const val DEFAULT_VOICE = "ru-supertonic-F3"
        private const val DEFAULT_SAMPLE_RATE = 24000
    }
}

/**
 * Pumps PCM bytes from Rust's `notifyAudioChunk` JNI callback straight
 * into the framework's `SynthesisCallback.audioAvailable`, respecting
 * `maxBufferSize`. Reflection-style invocation from JNI: only the
 * method names matter, the Rust side calls `notifyAudioChunk([B)V`,
 * `notifyProgress(II)V`, `isCancelled()Z`.
 */
class ChunkedStreamer(
    private val cb: SynthesisCallback,
    private val cancelled: () -> Boolean
) {
    fun isCancelled(): Boolean = cancelled()

    fun notifyAudioChunk(pcm: ByteArray) {
        if (pcm.isEmpty() || cancelled()) return
        val max = cb.maxBufferSize.coerceAtLeast(1024)
        var off = 0
        while (off < pcm.size) {
            if (cancelled()) return
            val len = minOf(max, pcm.size - off)
            cb.audioAvailable(pcm, off, len)
            off += len
        }
    }

    fun notifyProgress(current: Int, total: Int) {
        // No-op for now. Could surface via callback.rangeStart later.
        if (current > total) return
    }
}
