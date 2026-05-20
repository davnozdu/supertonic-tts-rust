package com.davnozdu.supertonicrust

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Downloads the assets the engine needs on first launch:
 *
 *   1. Supertonic 3 ONNX models + voice styles from the official Hugging
 *      Face repository at https://huggingface.co/Supertone/supertonic-3.
 *      Total ~398 MB. These are the same files the upstream fork pulls.
 *   2. Russian accent dictionary (.sacc) from the user's GitHub release
 *      `davnozdu/supertonic-dictionaries` tag `russian-v1.1`.
 *
 * Layout under `filesDir`:
 *   filesDir/
 *     model/                  ← MODEL_DIR
 *       onnx/
 *         text_encoder.onnx, duration_predictor.onnx,
 *         vector_estimator.onnx, vocoder.onnx,
 *         tts.json, unicode_indexer.json
 *       voice_styles/
 *         M1.json … M5.json, F1.json … F5.json
 *     accent_dictionary.sacc  ← single .sacc file
 *
 * The full ONNX bundle covers all 31 languages Supertonic 3 supports;
 * the .sacc dictionary is the Russian-specific stress + ё-restoration
 * data layered on top.
 */
object AssetDownloader {
    private const val TAG = "AssetDownloader"

    /** Subdirectory of filesDir that holds the official Supertonic bundle. */
    const val MODEL_DIR = "model"

    /** Filename for the accent dictionary in filesDir/. */
    const val ACCENT_DICT_NAME = "accent_dictionary.sacc"

    private const val HF_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/main"
    private const val DICT_BASE =
        "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1"

    /**
     * Relative-to-MODEL_DIR paths the engine expects. Listed in download
     * order so the user sees the smaller files complete fast, then the
     * big vector_estimator (257 MB) + vocoder (101 MB) at the end.
     */
    private val MODEL_FILES = listOf(
        "onnx/tts.json",
        "onnx/unicode_indexer.json",
        "onnx/duration_predictor.onnx",
        "onnx/text_encoder.onnx",
        "onnx/vector_estimator.onnx",
        "onnx/vocoder.onnx",
        "voice_styles/M1.json", "voice_styles/M2.json", "voice_styles/M3.json",
        "voice_styles/M4.json", "voice_styles/M5.json",
        "voice_styles/F1.json", "voice_styles/F2.json", "voice_styles/F3.json",
        "voice_styles/F4.json", "voice_styles/F5.json",
    )

    /** Snapshot of download progress for the active session. */
    data class Progress(
        val currentFile: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val bytesSoFar: Long,
        val bytesTotal: Long
    )

    /**
     * True if every required file is already in place on disk.
     */
    fun isReady(context: Context): Boolean {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) return false
        val allModel = MODEL_FILES.all { File(modelDir, it).exists() }
        if (!allModel) return false
        return File(context.filesDir, ACCENT_DICT_NAME).exists()
    }

    /**
     * Download everything that's missing. Calls [onProgress] frequently so
     * the UI can render a fine-grained progress bar. Returns true on full
     * success; on any single-file failure aborts, cleans up the partial
     * file, and returns false.
     */
    suspend fun downloadAll(
        context: Context,
        onProgress: (Progress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        // Build the merged work list: model files first, then dict.
        val all = MODEL_FILES.map { rel ->
            DownloadTask(
                remoteUrl = "$HF_BASE/$rel",
                localFile = File(modelDir, rel),
                label = rel
            )
        } + DownloadTask(
            remoteUrl = "$DICT_BASE/russian_accents_full.sacc",
            localFile = File(context.filesDir, ACCENT_DICT_NAME),
            label = ACCENT_DICT_NAME
        )

        all.forEachIndexed { i, task ->
            if (task.localFile.exists()) {
                onProgress(
                    Progress(task.label, i, all.size, task.localFile.length(), task.localFile.length())
                )
                return@forEachIndexed
            }
            task.localFile.parentFile?.mkdirs()
            val ok = downloadOne(task, i, all.size, onProgress)
            if (!ok) {
                task.localFile.delete()
                return@withContext false
            }
        }
        true
    }

    private fun downloadOne(
        task: DownloadTask,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: (Progress) -> Unit
    ): Boolean {
        return try {
            val conn = URL(task.remoteUrl).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
            val tmp = File(task.localFile.parentFile, "${task.localFile.name}.tmp")
            conn.getInputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var soFar = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        soFar += read
                        if (soFar - lastReport >= 256 * 1024 || (total > 0 && soFar >= total)) {
                            onProgress(Progress(task.label, fileIndex, totalFiles, soFar, total))
                            lastReport = soFar
                        }
                    }
                    onProgress(Progress(task.label, fileIndex, totalFiles, soFar, soFar))
                }
            }
            if (!tmp.renameTo(task.localFile)) {
                tmp.delete()
                Log.e(TAG, "rename failed: ${task.label}")
                return false
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed for ${task.label}: ${e.message}", e)
            false
        }
    }

    private data class DownloadTask(
        val remoteUrl: String,
        val localFile: File,
        val label: String
    )
}
