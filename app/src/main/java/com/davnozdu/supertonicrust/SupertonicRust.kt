package com.davnozdu.supertonicrust

import android.util.Log

/**
 * Thin Kotlin facade over the Rust JNI surface.
 *
 * The architecture (vs. the upstream fork): Kotlin owns Android lifecycle
 * and AudioTrack, Rust owns all text processing and ORT inference. So
 * this class is intentionally minimal — one `external fun` per JNI symbol
 * exported by `rust/src/lib.rs`.
 *
 * The static init block loads `libonnxruntime.so` first because the Rust
 * crate uses ORT's `load-dynamic` feature and resolves the runtime via
 * the dynamic linker at session-build time.
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
     * Initialise the inference engine. `modelPath` is the directory
     * holding the ONNX files plus tts.json/unicode_indexer.json;
     * `libPath` is the absolute path to libonnxruntime.so. Returns a
     * handle (0 on failure).
     */
    external fun initEngine(
        modelPath: String,
        libPath: String,
        ortThreads: Int,
        xnnThreads: Int
    ): Long

    /**
     * Run the text pipeline (yofication for now; lexicon / numbers /
     * accent dict to land later) and return the normalised string.
     */
    external fun processText(text: String): String

    /**
     * Streaming synthesis. PCM chunks are delivered to `callback` via
     * `notifyAudioChunk(byte[])`; progress through `notifyProgress(int,
     * int)`; cancellation is polled via `isCancelled(): Boolean`. The
     * return value is the full PCM (16-bit LE mono) concatenated, for
     * callers that prefer the bulk path.
     */
    external fun synthesize(
        callback: Any,
        engine: Long,
        text: String,
        lang: String,
        stylePath: String,
        speed: Float,
        bufferSeconds: Float,
        steps: Int,
        gain: Float
    ): ByteArray

    /** PCM sample rate of the loaded model (24000 if engine is null). */
    external fun getSampleRate(engine: Long): Int

    /** SoC class: 0 low / 1 mid / 2 high / 3 flagship; -1 if null engine. */
    external fun getSocClass(engine: Long): Int

    /** Reset thermal / RTF averages. */
    external fun reset(engine: Long)

    /** True if XNNPACK execution provider was compiled in. */
    external fun isXnnpackEnabled(): Boolean

    /** Drop ORT sessions and free native memory. */
    external fun close(engine: Long)

    @Synchronized
    fun ensureEngineInitialised(
        modelPath: String,
        libPath: String,
        ortThreads: Int = 2,
        xnnThreads: Int = 2
    ): Boolean {
        if (engineHandle != 0L) return true
        engineHandle = initEngine(modelPath, libPath, ortThreads, xnnThreads)
        return engineHandle != 0L
    }

    fun handle(): Long = engineHandle

    @Synchronized
    fun shutdown() {
        if (engineHandle != 0L) {
            close(engineHandle)
            engineHandle = 0
        }
    }
}
