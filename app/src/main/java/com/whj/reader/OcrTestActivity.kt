package com.whj.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whj.reader.databinding.ActivityOcrTestBinding
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * OCR 测试页：TFLite PP-OCRv4 mobile（Umi-OCR Rapid 同源），GPU / CPU。
 */
class OcrTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrTestBinding
    private var imageUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var engine: TfliteOcrEngine? = null
    private var engineBackend: TfliteOcrEngine.Backend? = null

    private val backends = listOf(
        TfliteOcrEngine.Backend.AUTO,
        TfliteOcrEngine.Backend.GPU,
        TfliteOcrEngine.Backend.CPU,
    )

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        imageUri = uri
        binding.tvResult.text = getString(R.string.ocr_result_empty)
        appendLog("已选图: $uri")
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(uri) }
            previewBitmap?.recycle()
            previewBitmap = bmp
            if (bmp != null) {
                binding.ivPreview.setImageBitmap(bmp)
                appendLog("解码成功 ${bmp.width}x${bmp.height}")
            } else {
                binding.ivPreview.setImageDrawable(null)
                appendLog("解码失败")
                Toasts.show(this@OcrTestActivity, getString(R.string.ocr_decode_fail))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRun.setOnClickListener { runOcr() }

        val labels = listOf(
            getString(R.string.ocr_backend_auto),
            getString(R.string.ocr_backend_gpu),
            getString(R.string.ocr_backend_cpu),
        )
        binding.spBackend.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )

        binding.tvProbe.text = TfliteOcrEngine.probeBackends(this)
        appendLog("环境探测完成")

        // 预加载引擎（后台）
        lifecycleScope.launch {
            appendLog("正在加载 TFLite 模型…")
            val ok = withContext(Dispatchers.Default) {
                ensureEngine(TfliteOcrEngine.Backend.AUTO)
            }
            if (ok) {
                appendLog("模型就绪 backend=${engineBackend}")
            }
        }
    }

    override fun onDestroy() {
        previewBitmap?.recycle()
        previewBitmap = null
        runCatching { engine?.close() }
        engine = null
        super.onDestroy()
    }

    private fun selectedBackend(): TfliteOcrEngine.Backend {
        val i = binding.spBackend.selectedItemPosition.coerceIn(0, backends.lastIndex)
        return backends[i]
    }

    private fun ensureEngine(backend: TfliteOcrEngine.Backend): Boolean {
        if (engine != null && engineBackend == backend) return true
        runCatching { engine?.close() }
        return try {
            engine = TfliteOcrEngine(this, backend)
            engineBackend = backend
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load engine", t)
            engine = null
            engineBackend = null
            false
        }
    }

    private fun runOcr() {
        val bmp = previewBitmap
        if (bmp == null) {
            Toasts.show(this, getString(R.string.ocr_need_image))
            return
        }
        val backend = selectedBackend()
        binding.btnRun.isEnabled = false
        binding.tvResult.text = getString(R.string.ocr_running)
        appendLog("开始识别 backend=$backend …")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                if (!ensureEngine(backend)) {
                    return@withContext null
                }
                val cfg = bmp.config ?: Bitmap.Config.ARGB_8888
                val copy = bmp.copy(cfg, false)
                try {
                    engine!!.recognize(copy)
                } finally {
                    if (copy !== bmp) copy.recycle()
                }
            }
            binding.btnRun.isEnabled = true
            if (result == null) {
                binding.tvResult.text = getString(R.string.ocr_load_fail)
                appendLog("引擎加载失败")
                return@launch
            }
            appendLog(result.log.trimEnd())
            appendLog(
                "完成 backend=${result.backend} det=${result.detMs}ms " +
                    "rec=${result.recMs}ms total=${result.totalMs}ms lines=${result.lines.size}",
            )
            binding.tvResult.text = result.text.ifBlank {
                getString(R.string.ocr_result_empty_text)
            }
            if (result.lines.isNotEmpty()) {
                Toasts.show(this@OcrTestActivity, getString(R.string.ocr_success))
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            val raw = if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = calcInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } ?: return null
            scaleIfNeeded(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "decode", t)
            null
        }
    }

    private fun calcInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var side = max(width, height)
        while (side / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return sample
    }

    private fun scaleIfNeeded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val side = max(w, h)
        if (side <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / side
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun appendLog(msg: String) {
        val old = binding.tvLog.text?.toString().orEmpty()
        binding.tvLog.text = if (old.isBlank()) msg else "$old\n$msg"
        Log.i(TAG, msg)
    }

    companion object {
        private const val TAG = "OcrTest"
        private const val MAX_SIDE = 2048
    }
}
