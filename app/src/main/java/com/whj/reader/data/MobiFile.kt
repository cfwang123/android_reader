package com.whj.reader.data

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * 按 PalmDB 记录表随机读 MOBI/AZW。只读用到的记录，不把整本载入内存。
 */
internal class MobiFile(val file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    val fileLength: Long = raf.length()
    val numRecords: Int
    private val starts: LongArray

    init {
        if (fileLength < 80) error("MOBI 文件过小")
        val head = ByteArray(78)
        raf.readFully(head)
        numRecords = u16(head, 76)
        if (numRecords <= 0 || 78L + numRecords.toLong() * 8L > fileLength) {
            error("无效 MOBI：记录表异常")
        }
        val table = ByteArray(numRecords * 8)
        raf.readFully(table)
        starts = LongArray(numRecords)
        for (i in 0 until numRecords) {
            starts[i] = u32(table, i * 8).toLong() and 0xffffffffL
        }
    }

    fun readRecord(index: Int): ByteArray {
        val n = recordLength(index)
        if (n <= 0) return ByteArray(0)
        if (n > MAX_RECORD) error("MOBI 记录过大")
        val buf = ByteArray(n)
        raf.seek(starts[index])
        raf.readFully(buf)
        return buf
    }

    /** 只读记录开头，用来判断是不是图片，避免打开时扫完整张图。 */
    fun readPrefix(index: Int, max: Int): ByteArray {
        val n = recordLength(index).coerceAtMost(max)
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        raf.seek(starts[index])
        raf.readFully(buf)
        return buf
    }

    fun imageRef(index: Int): String {
        val n = recordLength(index)
        if (n <= 0) return ""
        return BookImageSource.ref(file.absolutePath, starts[index], n)
    }

    override fun close() {
        raf.close()
    }

    private fun recordLength(index: Int): Int {
        if (index < 0 || index >= numRecords) return 0
        val start = starts[index]
        val end = if (index + 1 < numRecords) starts[index + 1] else fileLength
        if (start < 0 || end < start) return 0
        val n = minOf(end, fileLength) - start
        if (n <= 0 || n > Int.MAX_VALUE) return 0
        return n.toInt()
    }

    private fun u16(data: ByteArray, off: Int): Int {
        if (off + 1 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
    }

    private fun u32(data: ByteArray, off: Int): Int {
        if (off + 3 >= data.size) return 0
        return ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)
    }

    private companion object {
        const val MAX_RECORD = 48 * 1024 * 1024
    }
}
