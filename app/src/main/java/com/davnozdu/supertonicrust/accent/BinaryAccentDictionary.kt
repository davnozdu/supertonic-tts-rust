package com.davnozdu.supertonicrust.accent

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Memory-mapped read-only accent dictionary backed by a `.sacc` v1 file.
 *
 * Format (mirrors `dictionaries/build/build_binary.py` upstream):
 *
 * ```
 * Header (28 bytes, little-endian):
 *   0:  magic           u8[4]  = "SACC"
 *   4:  version         u32    = 1
 *   8:  entry_count     u32
 *   12: offsets_offset  u64    (= 28)
 *   20: data_offset     u64    (= 28 + entry_count * 4)
 *
 * Offsets table (entry_count × 4 bytes, little-endian u32):
 *   each entry is the offset of that record relative to data_offset.
 *
 * Data section (entry_count entries, sorted by lowercased UTF-8 key):
 *   u16 key_len, u16 value_len, <key_len bytes>, <value_len bytes>
 * ```
 *
 * Lookup is binary search on the offsets table, comparing UTF-8 bytes
 * directly against the query — no String allocations on the hot path.
 * Ported verbatim from the upstream supertonic-android fork.
 */
class BinaryAccentDictionary private constructor(
    private val raf: RandomAccessFile,
    private val buffer: ByteBuffer,
    val entryCount: Int,
    private val offsetsOffset: Long,
    private val dataOffset: Long
) {

    // ByteBuffer is NOT thread-safe (position/limit are mutable). A
    // per-thread duplicate avoids allocating a fresh view on every word
    // lookup.
    private val threadLocalView: ThreadLocal<ByteBuffer> = ThreadLocal.withInitial {
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    }

    /**
     * Look up [lowerKey] (already lowercased UTF-8 bytes). Returns the
     * stressed-form value, or null if not present.
     */
    fun lookup(lowerKey: ByteArray): String? {
        if (entryCount == 0 || lowerKey.isEmpty()) return null
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val entryAbs = entryAbsoluteOffset(mid)
            val cmp = compareKeyAt(entryAbs, lowerKey)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return readValueAt(entryAbs)
            }
        }
        return null
    }

    private fun entryAbsoluteOffset(i: Int): Int {
        val tableEntryAbs = (offsetsOffset + i.toLong() * 4L).toInt()
        val rel = buffer.getInt(tableEntryAbs).toLong() and 0xFFFFFFFFL
        return (dataOffset + rel).toInt()
    }

    private fun compareKeyAt(entryAbs: Int, query: ByteArray): Int {
        val keyLen = buffer.getShort(entryAbs).toInt() and 0xFFFF
        val keyStart = entryAbs + 4
        val cmpLen = minOf(keyLen, query.size)
        for (j in 0 until cmpLen) {
            val a = buffer.get(keyStart + j).toInt() and 0xFF
            val b = query[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        return keyLen - query.size
    }

    private fun readValueAt(entryAbs: Int): String {
        val keyLen = buffer.getShort(entryAbs).toInt() and 0xFFFF
        val valLen = buffer.getShort(entryAbs + 2).toInt() and 0xFFFF
        val valStart = entryAbs + 4 + keyLen
        val bytes = ByteArray(valLen)
        val view = threadLocalView.get()!!
        view.position(valStart)
        view.get(bytes, 0, valLen)
        return String(bytes, Charsets.UTF_8)
    }

    fun close() {
        try {
            raf.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    companion object {
        private const val TAG = "BinaryAccentDict"
        private const val HEADER_SIZE = 28
        private val MAGIC = byteArrayOf(
            'S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte()
        )
        private const val SUPPORTED_VERSION = 1
        private const val MAX_MAPPED_BYTES = 2_147_483_647L

        fun looksLikeSacc(file: File): Boolean {
            if (!file.exists() || file.length() < HEADER_SIZE) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(4)
                    raf.readFully(buf)
                    buf.contentEquals(MAGIC)
                }
            } catch (e: Exception) {
                false
            }
        }

        fun open(file: File): BinaryAccentDictionary? {
            var raf: RandomAccessFile? = null
            return try {
                val size = file.length()
                if (size < HEADER_SIZE) {
                    Log.w(TAG, "file too small: ${file.absolutePath}")
                    return null
                }
                if (size > MAX_MAPPED_BYTES) {
                    Log.w(TAG, "file > 2 GiB: $size")
                    return null
                }
                raf = RandomAccessFile(file, "r")
                val channel = raf.channel
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { for (i in 0..3) it[i] = buffer.get(i) }
                if (!magic.contentEquals(MAGIC)) {
                    Log.w(TAG, "bad magic")
                    raf.close()
                    return null
                }
                val version = buffer.getInt(4)
                if (version != SUPPORTED_VERSION) {
                    Log.w(TAG, "unsupported version: $version")
                    raf.close()
                    return null
                }
                val entryCount = buffer.getInt(8)
                val offsetsOffset = buffer.getLong(12)
                val dataOffset = buffer.getLong(20)
                if (entryCount < 0 ||
                    offsetsOffset < HEADER_SIZE ||
                    dataOffset < offsetsOffset + entryCount.toLong() * 4L ||
                    dataOffset > size
                ) {
                    Log.w(TAG, "header out of range")
                    raf.close()
                    return null
                }
                Log.i(TAG, "opened .sacc: $entryCount entries (${size / 1_048_576.0} MB)")
                BinaryAccentDictionary(raf, buffer, entryCount, offsetsOffset, dataOffset)
            } catch (e: Throwable) {
                Log.e(TAG, "open failed: ${file.absolutePath}", e)
                try { raf?.close() } catch (_: Exception) {}
                null
            }
        }
    }
}
