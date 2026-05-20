package com.davnozdu.supertonicrust

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Responds to `android.speech.tts.engine.CHECK_TTS_DATA` so the system
 * TTS settings can decide whether our engine has the data it needs.
 *
 *   * If the model bundle + accent dictionary are on disk, return
 *     `CHECK_VOICE_DATA_PASS` with `rus-RUS` listed as available — the
 *     engine is fully ready to be picked as the system default.
 *   * Otherwise return `CHECK_VOICE_DATA_FAIL` so the system shows the
 *     "Install voice data" button, which fires
 *     `android.speech.tts.engine.INSTALL_TTS_DATA` → our MainActivity
 *     downloads the assets on first launch.
 */
class CheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = AssetDownloader.isReady(this)
        val result = Intent().apply {
            if (ready) {
                putExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf("rus-RUS"))
                putExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf<String>())
            } else {
                putExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf<String>())
                putExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf("rus-RUS"))
            }
        }
        setResult(
            if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            result
        )
        finish()
    }
}
