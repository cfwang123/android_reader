package com.whj.reader.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 16-bit PCM WAV → AAC/M4A（MediaCodec + MediaMuxer）。
 */
object AacEncoder {

    fun wavToM4a(wav: File, m4a: File, bitRate: Int = 96_000) {
        val info = WavMerger.readInfo(wav)
        require(info.bitsPerSample == 16) { "only 16-bit pcm supported" }
        val sampleRate = info.sampleRate
        val channels = info.channels

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        m4a.parentFile?.mkdirs()
        if (m4a.exists()) m4a.delete()
        val muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuf = ByteArray(4096)
        var presentationUs = 0L
        val bytesPerSample = channels * 2
        var inputDone = false
        var outputDone = false

        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(info.dataOffset.toLong())
            var remaining = info.dataSize.toLong()

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val toRead = minOf(pcmBuf.size.toLong(), remaining, inBuf.capacity().toLong()).toInt()
                            val n = raf.read(pcmBuf, 0, toRead)
                            if (n <= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                inBuf.put(pcmBuf, 0, n)
                                val samples = n / bytesPerSample
                                codec.queueInputBuffer(inIndex, 0, n, presentationUs, 0)
                                presentationUs += samples * 1_000_000L / sampleRate
                                remaining -= n
                            }
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) error("format changed twice")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(track, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }
}
