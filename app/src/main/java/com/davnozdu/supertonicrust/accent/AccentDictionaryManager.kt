package com.davnozdu.supertonicrust.accent

import android.content.Context
import android.util.Log
import com.davnozdu.supertonicrust.AssetDownloader
import java.io.File
import java.util.regex.Pattern

/**
 * Word-indexed accent / pronunciation dictionary, backed by the mmap'd
 * `.sacc` binary downloaded into filesDir on first launch.
 *
 * Two responsibilities folded into one:
 *  1. Insert the U+0301 combining acute over stressed vowels — so the
 *     model gets "Москва́" rather than "Москва" and pronounces it
 *     correctly.
 *  2. Restore the ё letter — the published `russian-v1.1` `.sacc`
 *     bundle is yoficator-augmented, so entries like "елка" map to
 *     "ёлка́" with both fixes in one lookup.
 *
 * Pre-existing combining marks in the input are preserved (the regex
 * matches `[\p{L}\p{M}]+`, so a hand-stressed word's key won't be
 * found in the dict and the user's mark stays intact).
 *
 * Trimmed-down version of the upstream `AccentDictionaryManager`:
 * only the .sacc backend, no JSON fallback, no manual import flow.
 * The Lexicon menu / format selector is deliberately out of scope for
 * the Rust-fork MVP; we ship the Full Russian dictionary at install
 * time and that's it.
 */
object AccentDictionaryManager {
    private const val TAG = "AccentDict"

    @Volatile private var dict: BinaryAccentDictionary? = null
    @Volatile private var isLoading = false
    private val loadLock = Object()

    // `[\p{L}\p{M}]+` matches a Unicode letter run together with any
    // combining marks already attached to it. This is what stops a
    // hand-stressed input from being double-stressed: the lookup key
    // includes the existing U+0301, won't be found in the dict, and the
    // word is preserved as the user marked it.
    private val wordPattern: Pattern = Pattern.compile("[\\p{L}\\p{M}]+")

    /**
     * Start loading the dictionary from disk. Returns immediately —
     * mmap is cheap, but we still do it on a background thread to keep
     * the call site from worrying about latency variance on slow
     * storage. After this call `isReady()` flips to true.
     */
    fun load(context: Context) {
        if (dict != null) return
        synchronized(loadLock) {
            if (dict != null || isLoading) return
            isLoading = true
        }
        val appContext = context.applicationContext
        Thread({
            try {
                val file = File(appContext.filesDir, AssetDownloader.ACCENT_DICT_NAME)
                if (!file.exists()) {
                    Log.w(TAG, "dict file missing: ${file.absolutePath}")
                    return@Thread
                }
                if (!BinaryAccentDictionary.looksLikeSacc(file)) {
                    Log.w(TAG, "file isn't a .sacc binary")
                    return@Thread
                }
                val opened = BinaryAccentDictionary.open(file) ?: return@Thread
                synchronized(loadLock) {
                    dict = opened
                }
                Log.i(TAG, "ready: ${opened.entryCount} entries")
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
            } finally {
                synchronized(loadLock) {
                    isLoading = false
                }
            }
        }, "AccentDict-Loader").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    fun isReady(): Boolean = dict != null

    fun size(): Int = dict?.entryCount ?: 0

    /**
     * Walk every word in [text], look it up in the dictionary, and
     * replace it with the stressed (and ё-restored) form. Words not in
     * the dictionary pass through unchanged.
     */
    fun apply(text: String): String {
        val d = dict ?: return text
        val matcher = wordPattern.matcher(text)
        val sb = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group() ?: continue
            val lower = original.lowercase()
            val hit = d.lookup(lower.toByteArray(Charsets.UTF_8)) ?: continue
            val cased = applyCasing(original, hit)
            matcher.appendReplacement(
                sb,
                java.util.regex.Matcher.quoteReplacement(cased)
            )
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Reapply the casing pattern of [original] to [replacement] so a
     * lowercase-keyed entry can still respond to "Москва" by returning
     * "Москва́" instead of "москва́".
     */
    private fun applyCasing(original: String, replacement: String): String {
        val origLower = original.lowercase()
        if (original == origLower) return replacement
        if (original == original.uppercase()) return replacement.uppercase()
        if (original.length > 1 &&
            original[0].isUpperCase() &&
            original.substring(1) == original.substring(1).lowercase()
        ) {
            return replacement.replaceFirstChar { it.uppercase() }
        }
        return replacement
    }
}
