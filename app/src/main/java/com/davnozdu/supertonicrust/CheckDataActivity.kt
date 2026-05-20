package com.davnozdu.supertonicrust

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Responds to `android.speech.tts.engine.CHECK_TTS_DATA` so Android's
 * system TTS settings can decide whether our engine is ready. For now we
 * always report SUCCESS — the in-app downloader handles missing assets.
 *
 * Required by the Android TTS framework when a user picks our engine as
 * their default; without this activity the system shows "(Not installed)".
 */
class CheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                arrayListOf("rus-RUS")
            )
            putExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                arrayListOf<String>()
            )
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
