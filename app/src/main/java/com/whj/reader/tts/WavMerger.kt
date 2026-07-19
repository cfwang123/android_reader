package com.whj.reader.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 合并多段 16-bit PCM WAV（sampleRate/channels 须一致）。
 */
object WavMerger {

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
    )

    fun readInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44) { "wav too small" }
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            require(String(hdr, 0, 4) == "RIFF" && String(hdr, 8, 4) == "WAVE") {
                "not a wave file"
            }
            var sampleRate = 0
            var channels = 0
            var bits = 16
            var dataOffset = -1
            var dataSize = 0
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = ByteArray(4).also { raf.readFully(it) }
                val sizeBytes = ByteArray(4).also { raf.readFully(it) }
                val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int
                val id = String(chunkId)
                val next = raf.filePointer + size + (size and 1)
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.coerceAtMost(32))
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // format
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byte rate
                        bb.short // block align
                        if (fmt.size >= 16) bits = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = raf.filePointer.toInt()
                        dataSize = size
                        break
                    }
                    else -> { /* skip */ }
                }
                raf.seek(next)
            }
            require(dataOffset >= 0 && sampleRate > 0 && channels > 0) {
                "invalid wav: rate=$sampleRate ch=$channels"
            }
            return WavInfo(sampleRate, channels, bits, dataOffset, dataSize)
        }
    }

    fun merge(parts: List<File>, out: File) {
        require(parts.isNotEmpty()) { "no parts" }
        val infos = parts.map { readInfo(it) }
        val base = infos.first()
        for (i in infos) {
            require(i.sampleRate == base.sampleRate && i.channels == base.channels &&
                i.bitsPerSample == base.bitsPerSample) {
                "wav format mismatch"
            }
        }
        val totalData = infos.sumOf { it.dataSize.toLong() }.toInt()
        val bits = base.bitsPerSample
        val blockAlign = base.channels * bits / 8
        val byteRate = base.sampleRate * blockAlign

        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            // RIFF header
            raf.writeBytes("RIFF")
            writeIntLE(raf, 36 + totalData)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1) // PCM
            writeShortLE(raf, base.channels)
            writeIntLE(raf, base.sampleRate)
            writeIntLE(raf, byteRate)
            writeShortLE(raf, blockAlign)
            writeShortLE(raf, bits)
            raf.writeBytes("data")
            writeIntLE(raf, totalData)
            val buf = ByteArray(64 * 1024)
            for ((file, info) in parts.zip(infos)) {
                RandomAccessFile(file, "r").use { src ->
                    src.seek(info.dataOffset.toLong())
                    var left = info.dataSize
                    while (left > 0) {
                        val n = src.read(buf, 0, minOf(buf.size, left))
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write(v shr 8 and 0xFF)
        raf.write(v shr 16 and 0xFF)
        raf.write(v shr 24 and 0xFF)
    }

    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write(v shr 8 and 0xFF)
    }
}
