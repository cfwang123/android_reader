package com.whj.reader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.whj.reader.databinding.ActivityOcrBinding
import com.whj.reader.ocr.OcrOverlayView
import com.whj.reader.ocr.TfliteOcrEngine
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 通用 OCR：相册 / 拍照 → 识别 → 文字叠加层 → 长按选中复制 / 一键复制全部。
 */
class OcrActivity : AppCompatActivity(), OcrOverlayView.Listener {

    private lateinit var binding: ActivityOcrBinding
    private var engine: TfliteOcrEngine? = null
    private var sourceBitmap: Bitmap? = null
    private var cameraUri: Uri? = null
    /** 每行在 etFullText 中的起始字符下标（含换行后） */
    private var lineStartOffsets: IntArray = intArrayOf()

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) loadAndRecognize(uri)
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            loadAndRecognize(uri)
        } else {
            setStatus(getString(R.string.ocr_camera_cancel))
        }
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toasts.show(this, getString(R.string.ocr_camera_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCamera.setOnClickListener { ensureCameraThenShoot() }
        binding.btnCopyAll.setOnClickListener { copyAll() }
        binding.btnCopySelected.setOnClickListener { copySelected() }
        binding.btnToggleLayer.setOnClickListener { toggleTextLayer() }
        refreshLayerButton()

        binding.overlay.listener = this
        binding.etFullText.keyListener = null // 只读可选，避免误改识别结果
        binding.etFullText.setTextIsSelectable(true)

        val backendPref = parseBackendExtra(intent?.getStringExtra(EXTRA_BACKEND))
        val autoBench = intent?.getBooleanExtra(EXTRA_AUTO_BENCH, false) == true

        // 预加载模型
        lifecycleScope.launch {
            setStatus(getString(R.string.ocr_loading_model))
            showProgress(true)
            val ok = withContext(Dispatchers.Default) {
                try {
                    Log.i(TAG, "init engine preferred=$backendPref autoBench=$autoBench")
                    engine = TfliteOcrEngine(this@OcrActivity, backendPref)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "engine", t)
                    false
                }
            }
            showProgress(false)
            if (ok) {
                val b = engine?.backendName.orEmpty()
                Log.i(TAG, "OCR_BENCH_BACKEND=$b")
                setStatus(
                    getString(R.string.ocr_status_idle) +
                        if (b.isNotEmpty()) " · $b" else "",
                )
                if (autoBench) {
                    runAutoBench()
                }
            } else {
                setStatus(getString(R.string.ocr_load_fail))
                Log.e(TAG, "OCR_BENCH_FAIL load_engine")
            }
        }

        // 外部传入图片（autoBench 时不抢）
        if (!autoBench) {
            intent?.data?.let { loadAndRecognize(it) }
            intent?.getParcelableExtraCompat<Uri>(IntentKeys.IMAGE_URI)?.let { loadAndRecognize(it) }
        }
    }

    private fun toggleTextLayer() {
        binding.overlay.textLayerVisible = !binding.overlay.textLayerVisible
        refreshLayerButton()
    }

    private fun refreshLayerButton() {
        binding.btnToggleLayer.text = if (binding.overlay.textLayerVisible) {
            getString(R.string.ocr_hide_layer)
        } else {
            getString(R.string.ocr_show_layer)
        }
    }

    /** adb / 命令行：加载内置 bench 图跑一遍，写日志 OCR_BENCH_* */
    private fun runAutoBench() {
        lifecycleScope.launch {
            setStatus("AUTO_BENCH 运行中…")
            showProgress(true)
            val bmp = withContext(Dispatchers.IO) {
                try {
                    assets.open("ocr/bench.jpg").use { BitmapFactory.decodeStream(it) }
                        ?.let { scaleIfNeeded(it) }
                } catch (t: Throwable) {
                    Log.e(TAG, "bench decode", t)
                    null
                }
            }
            if (bmp == null) {
                showProgress(false)
                Log.e(TAG, "OCR_BENCH_FAIL no_bench_image")
                setStatus("AUTO_BENCH 失败：无 bench 图")
                return@launch
            }
            sourceBitmap?.recycle()
            sourceBitmap = bmp
            binding.overlay.setImage(bmp)
            val eng = engine
            if (eng == null) {
                showProgress(false)
                Log.e(TAG, "OCR_BENCH_FAIL no_engine")
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    eng.recognize(copy)
                } finally {
                    if (copy !== bmp) copy.recycle()
                }
            }
            showProgress(false)
            binding.overlay.setLines(result.lines)
            setFullTextFromLines(result.lines)
            setStatus(
                getString(
                    R.string.ocr_status_done,
                    result.lines.size,
                    result.backend,
                    result.totalMs,
                ),
            )
            // 便于 adb logcat 抓取的固定格式
            Log.i(
                TAG,
                "OCR_BENCH_OK backend=${result.backend} detMs=${result.detMs} " +
                    "recMs=${result.recMs} totalMs=${result.totalMs} lines=${result.lines.size}",
            )
            Log.i(TAG, "OCR_BENCH_INIT ${eng.initLog.replace("\n", " | ")}")
            Log.i(TAG, "OCR_BENCH_LOG ${result.log.replace("\n", " | ")}")
            // 写到 app 私有文件，命令行 run-as 可读
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(filesDir, "ocr_bench_last.txt")
                    f.writeText(
                        buildString {
                            appendLine("backend=${result.backend}")
                            appendLine("detMs=${result.detMs}")
                            appendLine("recMs=${result.recMs}")
                            appendLine("totalMs=${result.totalMs}")
                            appendLine("lines=${result.lines.size}")
                            appendLine("---init---")
                            append(eng.initLog)
                            appendLine("---log---")
                            append(result.log)
                            appendLine("---text---")
                            append(result.text)
                        },
                        Charsets.UTF_8,
                    )
                }
            }
        }
    }

    private fun parseBackendExtra(raw: String?): TfliteOcrEngine.Backend {
        return when (raw?.trim()?.uppercase()) {
            "GPU" -> TfliteOcrEngine.Backend.GPU
            "CPU" -> TfliteOcrEngine.Backend.CPU
            else -> TfliteOcrEngine.Backend.AUTO
        }
    }

    override fun onDestroy() {
        sourceBitmap?.recycle()
        sourceBitmap = null
        runCatching { engine?.close() }
        engine = null
        super.onDestroy()
    }

    override fun onSelectionChanged(selected: List<TfliteOcrEngine.LineResult>) {
        binding.btnCopySelected.isEnabled = selected.isNotEmpty()
        syncFullTextSelection()
    }

    override fun onSelectionStarted() {
        // 长按开始选区（与 PDF 一致：再拖动扩展，不自动复制）
        binding.btnCopySelected.isEnabled = true
        syncFullTextSelection()
    }

    /** 图上选区 → 下方全文 EditText 同步选中并滚入视野 */
    private fun syncFullTextSelection() {
        val et = binding.etFullText
        val range = binding.overlay.getSelectionLineRange()
        if (range == null || lineStartOffsets.isEmpty()) {
            et.clearFocus()
            // 取消高亮选区，光标放到开头
            if (et.text.isNotEmpty()) {
                et.setSelection(0, 0)
            }
            return
        }
        val startLine = range.first.coerceIn(0, lineStartOffsets.lastIndex)
        val endLine = range.last.coerceIn(0, lineStartOffsets.lastIndex)
        val start = lineStartOffsets[startLine]
        val endText = binding.overlay.getSelectedText()
        val end = (start + endText.length).coerceAtMost(et.text.length)
        if (start in 0..et.text.length && end in start..et.text.length) {
            et.requestFocus()
            et.setSelection(start, end)
            // 滚到选中起始行（滚动由 NestedScrollView 负责，带惯性）
            et.post {
                val layout = et.layout ?: return@post
                val line = layout.getLineForOffset(start)
                val y = layout.getLineTop(line)
                val scroller = binding.scrollFullText
                val target = (y - scroller.height / 3).coerceAtLeast(0)
                scroller.smoothScrollTo(0, target)
            }
        }
    }

    private fun setFullTextFromLines(lines: List<TfliteOcrEngine.LineResult>) {
        val sb = StringBuilder()
        val offsets = IntArray(lines.size)
        for (i in lines.indices) {
            if (i > 0) sb.append('\n')
            offsets[i] = sb.length
            sb.append(lines[i].text)
        }
        lineStartOffsets = offsets
        binding.etFullText.setText(sb.toString())
        if (sb.isNotEmpty()) {
            binding.etFullText.setSelection(0, 0)
        }
    }

    private fun ensureCameraThenShoot() {
        val perm = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCamera.launch(perm)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "ocr_capture").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            )
            cameraUri = uri
            takePicture.launch(uri)
        } catch (t: Throwable) {
            Log.e(TAG, "camera", t)
            Toasts.show(this, getString(R.string.ocr_camera_fail, t.message ?: ""))
        }
    }

    private fun loadAndRecognize(uri: Uri) {
        lifecycleScope.launch {
            setStatus(getString(R.string.ocr_decoding))
            showProgress(true)
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(uri) }
            if (bmp == null) {
                showProgress(false)
                setStatus(getString(R.string.ocr_decode_fail))
                Toasts.show(this@OcrActivity, getString(R.string.ocr_decode_fail))
                return@launch
            }
            sourceBitmap?.recycle()
            sourceBitmap = bmp
            binding.overlay.setImage(bmp)
            binding.overlay.setLines(emptyList())
            setFullTextFromLines(emptyList())
            onSelectionChanged(emptyList())

            setStatus(getString(R.string.ocr_running))
            val eng = engine ?: withContext(Dispatchers.Default) {
                try {
                    TfliteOcrEngine(this@OcrActivity, TfliteOcrEngine.Backend.AUTO).also { engine = it }
                } catch (t: Throwable) {
                    Log.e(TAG, "engine late", t)
                    null
                }
            }
            if (eng == null) {
                showProgress(false)
                setStatus(getString(R.string.ocr_load_fail))
                return@launch
            }

            val result = withContext(Dispatchers.Default) {
                val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                try {
                    eng.recognize(copy)
                } finally {
                    if (copy !== bmp) copy.recycle()
                }
            }
            showProgress(false)
            binding.overlay.setLines(result.lines)
            setFullTextFromLines(result.lines)
            setStatus(
                getString(
                    R.string.ocr_status_done,
                    result.lines.size,
                    result.backend,
                    result.totalMs,
                ),
            )
            if (result.lines.isEmpty()) {
                Toasts.show(this@OcrActivity, getString(R.string.ocr_result_empty_text))
            }
        }
    }

    private fun copyAll() {
        val text = binding.overlay.getAllText()
        if (text.isBlank()) {
            Toasts.show(this, getString(R.string.ocr_nothing_to_copy))
            return
        }
        copyText(text, getString(R.string.ocr_copied_all))
    }

    private fun copySelected() {
        val text = binding.overlay.getSelectedText()
        if (text.isBlank()) {
            Toasts.show(this, getString(R.string.ocr_nothing_selected))
            return
        }
        copyText(text, getString(R.string.ocr_copied_selected))
    }

    private fun copyText(text: String, toast: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
        Toasts.show(this, toast)
    }

    private fun setStatus(s: String) {
        binding.tvStatus.text = s
    }

    private fun showProgress(show: Boolean) {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnGallery.isEnabled = !show
        binding.btnCamera.isEnabled = !show
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
        while (side / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun scaleIfNeeded(src: Bitmap): Bitmap {
        val side = max(src.width, src.height)
        if (side <= MAX_SIDE) {
            return if (src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, false).also {
                if (it !== src) src.recycle()
            }
        }
        val scale = MAX_SIDE.toFloat() / side
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (scaled !== src) src.recycle()
        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, false).also {
            if (it !== scaled) scaled.recycle()
        }
    }

    private inline fun <reified T> android.content.Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
        }
    }

    object IntentKeys {
        const val IMAGE_URI = "image_uri"
    }

    companion object {
        private const val TAG = "OcrActivity"
        private const val MAX_SIDE = 2048
        /** adb: --es backend GPU|CPU|AUTO */
        const val EXTRA_BACKEND = "backend"
        /** adb: --ez auto_bench true  自动跑 assets/ocr/bench.jpg */
        const val EXTRA_AUTO_BENCH = "auto_bench"
    }
}
