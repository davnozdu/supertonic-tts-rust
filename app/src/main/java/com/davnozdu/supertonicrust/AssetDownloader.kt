package com.davnozdu.supertonicrust

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Downloads the data files the engine needs from the user's GitHub
 * releases, into the app's private filesDir.
 *
 * Two repos are involved:
 *   * `davnozdu/supertonic-dictionaries` — Russian accent dict (.sacc).
 *     This repo already exists from the upstream fork and we keep using
 *     it as-is.
 *   * `davnozdu/supertonic-models` — placeholder for the model bundle
 *     (ONNX weights + voice styles). The MVP keeps the URL configurable
 *     so the user can point it at any GitHub release tag.
 *
 * MVP scope: linear, blocking download with progress callback. No
 * resume-on-failure yet; if a file is partially written we delete it
 * on next launch and re-download.
 */
class AssetDownloader(private val context: Context) {

    companion object {
        private const val TAG = "AssetDownloader"

        // Russian accent dictionary — same release v1.1 the fork already uses.
        const val DICT_BASE =
            "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1"

        // Model bundle — placeholder. The user is expected to publish a
        // release under this name with the ONNX files + voice_styles zipped.
        // The app downloads + unzips into filesDir/model/.
        const val MODEL_BASE =
            "https://github.com/davnozdu/supertonic-models/releases/download/v3.0.0"
    }

    enum class Asset(val remote: String, val local: String) {
        ACCENT_DICT_FULL_SACC("$DICT_BASE/russian_accents_full.sacc", "accent_dictionary.sacc"),
        MODEL_BUNDLE_ZIP("$MODEL_BASE/supertonic-v3.zip", "supertonic-v3.zip"),
    }

    fun localFile(asset: Asset): File = File(context.filesDir, asset.local)

    fun isPresent(asset: Asset): Boolean = localFile(asset).exists()

    /**
     * Download `asset` if not already present. Calls `onProgress(soFar, total)`
     * (total = -1 if unknown). Returns true on success.
     */
    fun download(
        asset: Asset,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val target = localFile(asset)
        if (target.exists()) return true
        val tmp = File(context.filesDir, "${asset.local}.tmp")
        return try {
            val conn = URL(asset.remote).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
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
                        if (soFar - lastReport >= 256 * 1024) {
                            onProgress(soFar, total)
                            lastReport = soFar
                        }
                    }
                    onProgress(soFar, soFar)
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                Log.e(TAG, "Failed to move tmp to target for ${asset.name}")
                return false
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed for ${asset.name}: ${e.message}", e)
            tmp.delete()
            false
        }
    }
}
