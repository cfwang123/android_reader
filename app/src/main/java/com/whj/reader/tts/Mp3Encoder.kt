package com.whj.reader.tts

import android.os.Build
import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import com.naman14.androidlame.WaveReader
import java.io.File
import java.io.FileOutputStream

/**
 * WAV(PCM 16-bit) → MP3（LAME，仅打包 arm64-v8a 的 libandroidlame.so）。
 * 非 arm64 或加载失败时 [isAvailable] 为 false，调用方应回退 M4A。
 */
object Mp3Encoder {
    private const val TAG = "Mp3Encoder"

    @Volatile
    private var availableCache: Boolean? = null

    fun isAvailable(): Boolean {
        availableCache?.let { return it }
        val hasArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!hasArm64) {
            availableCache = false
            return false
        }
        val ok = runCatching {
            LameBuilder()
                .setInSampleRate(16000)
                .setOutChannels(1)
                .setOutBitrate(64)
                .setMode(LameBuilder.Mode.MONO)
                .build()
                .also { it.close() }
            true
        }.onFailure { t ->
            Log.w(TAG, "LAME unavailable: ${t.message}")
        }.getOrDefault(false)
        availableCache = ok
        return ok
    }

    /**
     * @param bitRateKbps 目标码率 kbps（如 32/64/128）
     */
    fun wavToMp3(wav: File, mp3: File, bitRateKbps: Int = 64) {
        if (!isAvailable()) error("mp3 encoder not available on this ABI")
        val kbps = bitRateKbps.coerceIn(16, 320)

        val reader = WaveReader(wav)
        reader.openWave()
        try {
            val sampleRate = reader.sampleRate
            val channels = reader.channels.coerceIn(1, 2)
            val builder = LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutChannels(channels)
                .setOutBitrate(kbps)
                .setOutSampleRate(sampleRate)
                .setQuality(5)
            if (channels == 1) {
                builder.setMode(LameBuilder.Mode.MONO)
            } else {
                builder.setMode(LameBuilder.Mode.STEREO)
            }
            val lame: AndroidLame = builder.build()

            mp3.parentFile?.mkdirs()
            if (mp3.exists()) mp3.delete()

            val frameSamples = 1152 * 4
            val left = ShortArray(frameSamples)
            val right = ShortArray(frameSamples)
            val interleaved = ShortArray(frameSamples * 2)
            val mp3buf = ByteArray((1.25 * frameSamples + 7200).toInt().coerceAtLeast(8192))

            FileOutputStream(mp3).use { out ->
                if (channels == 1) {
                    while (true) {
                        val n = reader.read(left, frameSamples)
                        if (n <= 0) break
                        val encoded = lame.encode(left, left, n, mp3buf)
                        if (encoded > 0) out.write(mp3buf, 0, encoded)
                    }
                } else {
                    while (true) {
                        val n = reader.read(left, right, frameSamples)
                        if (n <= 0) break
                        for (i in 0 until n) {
                            interleaved[i * 2] = left[i]
                            interleaved[i * 2 + 1] = right[i]
                        }
                        val encoded = lame.encodeBufferInterLeaved(interleaved, n, mp3buf)
                        if (encoded > 0) out.write(mp3buf, 0, encoded)
                    }
                }
                val flushBuf = ByteArray(7200 + 1024)
                val flushed = lame.flush(flushBuf)
                if (flushed > 0) out.write(flushBuf, 0, flushed)
            }
            lame.close()
            if (!mp3.exists() || mp3.length() <= 0L) {
                error("mp3 output empty")
            }
            Log.i(TAG, "wavToMp3 ok size=${mp3.length()} kbps=$kbps rate=$sampleRate ch=$channels")
        } finally {
            runCatching { reader.closeWaveFile() }
        }
    }
}
