package com.davnozdu.supertonicrust

import android.util.Log

/**
 * Thin Kotlin facade over the Rust JNI surface.
 *
 * The new architecture (vs. the upstream fork) is: Kotlin owns Android
 * lifecycle + AudioTrack only, Rust does all text processing and ORT
 * inference. So this class is intentionally minimal — one `external fun`
 * per JNI symbol declared in `rust/src/lib.rs`, plus a static `init {}`
 * block that loads both native libraries (onnxruntime first because the
 * Rust crate dynamically links against it).
 */
object SupertonicRust {

    private const val TAG = "SupertonicRust"

    @Volatile
    private var engineHandle: Long = 0

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_rust")
            initLogger()
            Log.i(TAG, "Native libraries loaded, version = ${nativeVersion()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /** Returns the Cargo package version string of libsupertonic_rust. */
    external fun nativeVersion(): String

    /** Initialises android_logger inside Rust so log::info! reaches logcat. */
    external fun initLogger()

    /** mmap the accent dictionary file. Returns true on success. */
    external fun loadAccentDictionary(path: String): Boolean

    /**
     * Initialise the inference engine. modelPath is the directory holding
     * the ONNX files; libPath is the absolute path to libonnxruntime.so
     * unpacked from our jniLibs. Returns a handle (0 on failure).
     */
    external fun initEngine(modelPath: String, libPath: String): Long

    /**
     * Runs the text pipeline (yofication / lexicon / numbers / accent
     * dict) and returns the normalised string. Used by both the synthesis
     * code path and the planned diagnostic UI.
     */
    external fun processText(text: String): String

    /**
     * Stub for streaming synthesis. The real call will deliver PCM chunks
     * back via a callback parameter; for the MVP scaffold it returns an
     * empty byte array so the rest of the code paths compile.
     */
    external fun synthesize(
        engine: Long,
        text: String,
        voicePath: String,
        speed: Float,
        steps: Int
    ): ByteArray

    @Synchronized
    fun ensureEngineInitialised(modelPath: String, libPath: String): Boolean {
        if (engineHandle != 0L) return true
        engineHandle = initEngine(modelPath, libPath)
        return engineHandle != 0L
    }

    fun handle(): Long = engineHandle
}
