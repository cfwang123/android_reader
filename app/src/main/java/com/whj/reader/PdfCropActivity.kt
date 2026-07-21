package com.whj.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AppCompatActivity
import com.whj.reader.ui.AppTheme
import androidx.lifecycle.lifecycleScope
import com.whj.reader.data.AppSettings
import com.whj.reader.databinding.ActivityPdfCropBinding
import com.whj.reader.util.Toasts
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏裁剪页面：预览 + 红框八柄拖动 + 奇偶对称 + 自动采样。
 */
class PdfCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_PAGE = "page"
        const val EXTRA_CROP_L = "cropL"
        const val EXTRA_CROP_T = "cropT"
        const val EXTRA_CROP_R = "cropR"
        const val EXTRA_CROP_B = "cropB"
        const val EXTRA_MIRROR = "mirror"
        /** 还原后关闭阅读页的排版面板与菜单 */
        const val EXTRA_DISMISS_UI = "dismissUi"
        const val RESULT_APPLIED = RESULT_OK
    }

    private lateinit var binding: ActivityPdfCropBinding

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var fullBitmap: Bitmap? = null
    private val renderLock = Any()

    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPdfCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrBlank()) {
            finish()
            return
        }
        pageIndex = intent.getIntExtra(EXTRA_PAGE, 0).coerceAtLeast(0)

        val m = AppSettings.pdfCropMargins(this)
        cropL = m[0]; cropT = m[1]; cropR = m[2]; cropB = m[3]
        binding.switchMirror.isChecked = AppSettings.pdfCropMirrorOddEven(this)
        binding.cropOverlay.setCrop(cropL, cropT, cropR, cropB)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnOk.setOnClickListener { confirmAndFinish() }
        // 还原 = 取消切边（四边 0）并退出，结果回传阅读页
        binding.btnReset.setOnClickListener {
            cropL = 0f; cropT = 0f; cropR = 0f; cropB = 0f
            binding.cropOverlay.setCrop(0f, 0f, 0f, 0f)
            val mirror = binding.switchMirror.isChecked
            AppSettings.setPdfCropMargins(this, 0f, 0f, 0f, 0f)
            AppSettings.setPdfCropMirrorOddEven(this, mirror)
            setResult(
                RESULT_APPLIED,
                Intent()
                    .putExtra(EXTRA_CROP_L, 0f)
                    .putExtra(EXTRA_CROP_T, 0f)
                    .putExtra(EXTRA_CROP_R, 0f)
                    .putExtra(EXTRA_CROP_B, 0f)
                    .putExtra(EXTRA_MIRROR, mirror)
                    .putExtra(EXTRA_DISMISS_UI, true),
            )
            Toasts.show(this, R.string.pdf_crop_reset)
            finish()
        }
        binding.btnAuto.setOnClickListener { runAutoDetect(fromEntry = false) }
        binding.btnPrev.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex--
                loadPage()
            }
        }
        binding.btnNext.setOnClickListener {
            if (pageIndex < pageCount - 1) {
                pageIndex++
                loadPage()
            }
        }
        binding.cropOverlay.onCropChanged = { l, t, r, b ->
            cropL = l; cropT = t; cropR = r; cropB = b
        }

        binding.ivPage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateImageRect()
        }

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val fd = contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
                        ?: error("open failed")
                    pfd = fd
                    val rend = PdfRenderer(fd)
                    renderer = rend
                    pageCount = rend.pageCount
                    pageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                }.isSuccess
            }
            if (!ok || pageCount <= 0) {
                Toasts.show(this@PdfCropActivity, getString(R.string.load_failed, "PDF"))
                finish()
                return@launch
            }
            loadPage()
            // 进入切边：自动采样，矩形包住内容
            runAutoDetect(fromEntry = true)
        }
    }

    override fun onDestroy() {
        synchronized(renderLock) {
            fullBitmap?.recycle()
            fullBitmap = null
            runCatching { renderer?.close() }
            renderer = null
            runCatching { pfd?.close() }
            pfd = null
        }
        super.onDestroy()
    }

    private fun loadPage() {
        val r = renderer ?: return
        binding.tvPage.text = getString(R.string.pdf_page_of, pageIndex + 1, pageCount)
        synchronized(renderLock) {
            try {
                val page = r.openPage(pageIndex.coerceIn(0, r.pageCount - 1))
                val maxW = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtLeast(200)
                val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtLeast(200)
                // 等比缩放：宽高共用同一 scale，避免超高页被非等比压扁
                var scale = minOf(
                    maxW / page.width.toFloat(),
                    maxH / page.height.toFloat(),
                    2.5f,
                ).coerceAtLeast(0.02f)
                if (page.width * scale > 4096) scale = 4096f / page.width
                if (page.height * scale > 4096) scale = 4096f / page.height
                val bw = (page.width * scale).toInt().coerceAtLeast(1)
                val bh = (page.height * scale).toInt().coerceAtLeast(1)
                val old = fullBitmap
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val matrix = Matrix()
                matrix.postScale(scale, scale)
                page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                fullBitmap = bmp
                binding.ivPage.setImageBitmap(bmp)
                if (old != null && old !== bmp && !old.isRecycled) old.recycle()
            } catch (e: Exception) {
                Toasts.show(this, getString(R.string.load_failed, e.message ?: ""))
            }
        }
        binding.ivPage.post { updateImageRect() }
    }

    private fun updateImageRect() {
        val iv = binding.ivPage
        val d = iv.drawable ?: return
        val vw = iv.width.toFloat().coerceAtLeast(1f)
        val vh = iv.height.toFloat().coerceAtLeast(1f)
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(vw / dw, vh / dh)
        val dw2 = dw * scale
        val dh2 = dh * scale
        val left = (vw - dw2) / 2f
        val top = (vh - dh2) / 2f
        // Overlay 与 ImageView 同尺寸，坐标系一致
        binding.cropOverlay.imageRect = RectF(left, top, left + dw2, top + dh2)
        binding.cropOverlay.setCrop(cropL, cropT, cropR, cropB)
    }

    /**
     * @param fromEntry 进入页面时自动调用：仍采样贴合内容，完成提示更轻量
     */
    private fun runAutoDetect(fromEntry: Boolean = false) {
        val r = renderer ?: return
        if (!fromEntry) {
            Toasts.show(this, getString(R.string.pdf_crop_auto))
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                detectMargins(r, pageCount)
            }
            if (isFinishing) return@launch
            cropL = result[0]; cropT = result[1]
            cropR = result[2]; cropB = result[3]
            binding.cropOverlay.setCrop(cropL, cropT, cropR, cropB)
            updateImageRect()
            Toasts.show(
                this@PdfCropActivity,
                getString(R.string.pdf_crop_auto_done, min(5, pageCount)),
            )
        }
    }

    private fun detectMargins(r: PdfRenderer, count: Int): FloatArray {
        val samples = min(5, count)
        var minL = 0.30f
        var minT = 0.30f
        var minR = 0.30f
        var minB = 0.30f
        var any = false
        synchronized(renderLock) {
            for (i in 0 until samples) {
                try {
                    val page = r.openPage(i)
                    val sw = 140
                    val sh = max(1, (page.height * sw / page.width.toFloat()).toInt())
                    val probe = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                    val m = Matrix()
                    m.postScale(sw / page.width.toFloat(), sh / page.height.toFloat())
                    page.render(probe, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val margins = contentMargins(probe)
                    probe.recycle()
                    minL = min(minL, margins[0])
                    minT = min(minT, margins[1])
                    minR = min(minR, margins[2])
                    minB = min(minB, margins[3])
                    any = true
                } catch (_: Exception) {
                }
            }
        }
        if (!any) return floatArrayOf(0f, 0f, 0f, 0f)
        fun pad(v: Float) = (v - 0.008f).coerceIn(0f, 0.30f)
        return floatArrayOf(pad(minL), pad(minT), pad(minR), pad(minB))
    }

    private fun contentMargins(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return floatArrayOf(0f, 0f, 0f, 0f)
        val threshold = 245
        fun ink(x: Int, y: Int): Boolean {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return r < threshold || g < threshold || b < threshold
        }
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        val step = max(1, min(w, h) / 90)
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (ink(x, y)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            (minX / w.toFloat()).coerceIn(0f, 0.30f),
            (minY / h.toFloat()).coerceIn(0f, 0.30f),
            ((w - 1 - maxX) / w.toFloat()).coerceIn(0f, 0.30f),
            ((h - 1 - maxY) / h.toFloat()).coerceIn(0f, 0.30f),
        )
    }

    private fun confirmAndFinish() {
        val arr = binding.cropOverlay.cropArray()
        cropL = arr[0]; cropT = arr[1]; cropR = arr[2]; cropB = arr[3]
        val mirror = binding.switchMirror.isChecked
        AppSettings.setPdfCropMargins(this, cropL, cropT, cropR, cropB)
        AppSettings.setPdfCropMirrorOddEven(this, mirror)
        setResult(
            RESULT_APPLIED,
            Intent()
                .putExtra(EXTRA_CROP_L, cropL)
                .putExtra(EXTRA_CROP_T, cropT)
                .putExtra(EXTRA_CROP_R, cropR)
                .putExtra(EXTRA_CROP_B, cropB)
                .putExtra(EXTRA_MIRROR, mirror),
        )
        finish()
    }
}
