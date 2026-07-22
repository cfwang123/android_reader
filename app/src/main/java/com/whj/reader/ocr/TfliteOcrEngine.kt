package com.whj.reader.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Umi-OCR Rapid 同源 PP-OCRv4 mobile（det / cls / rec）TFLite 推理。
 * 优先 GPU → CPU。
 */
class TfliteOcrEngine(
    context: Context,
    preferred: Backend = Backend.AUTO,
) : Closeable {

    enum class Backend { AUTO, GPU, CPU }

    data class LineResult(
        val text: String,
        val score: Float = 1f,
        val box: FloatArray? = null,
    )

    data class OcrResult(
        val lines: List<LineResult>,
        val backend: String,
        val detMs: Long,
        val recMs: Long,
        val totalMs: Long,
        val log: String,
    ) {
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    private val app = context.applicationContext
    private val logBuf = StringBuilder()
    /** 初始化阶段日志（recognize 不会清空） */
    val initLog: String
        get() = initLogBuf.toString()
    private val initLogBuf = StringBuilder()
    var backendName: String = "CPU"
        private set
    private val ownedDelegates = ArrayList<AutoCloseable>()

    private val det: Interpreter
    private val cls: Interpreter
    private val rec: Interpreter
    private val charset: List<String>

    private val detInH: Int
    private val detInW: Int
    private val clsInH: Int
    private val clsInW: Int
    private val recInH: Int
    private val recInW: Int

    init {
        val models = openModels(preferred)
        det = models.det
        cls = models.cls
        rec = models.rec
        backendName = models.backend // GPU / CPU
        charset = loadCharset()

        val dIn = det.getInputTensor(0).shape()
        val cIn = cls.getInputTensor(0).shape()
        val rIn = rec.getInputTensor(0).shape()
        // NHWC [1,H,W,3]
        detInH = dIn[1]; detInW = dIn[2]
        clsInH = cIn[1]; clsInW = cIn[2]
        recInH = rIn[1]; recInW = rIn[2]

        initLine(
            "backend=$backendName det=${dIn.contentToString()} " +
                "cls=${cIn.contentToString()} rec=${rIn.contentToString()} " +
                "charset=${charset.size}",
        )
    }

    private fun initLine(s: String) {
        initLogBuf.append(s).append('\n')
        Log.i(TAG, s)
    }

    private data class Models(
        val det: Interpreter,
        val cls: Interpreter,
        val rec: Interpreter,
        val backend: String,
    )

    private fun openModels(preferred: Backend): Models {
        // AUTO/GPU：全 GPU → 强制全 GPU → det-GPU+cls/rec-CPU 混合 → CPU
        val attempts: List<() -> Opened> = when (preferred) {
            Backend.AUTO -> listOf(
                { tryOpenBackend(Backend.GPU) },
                { tryOpenGpuForce() },
                { tryOpenGpuDetHybrid() },
                { tryOpenBackend(Backend.CPU) },
            )
            Backend.GPU -> listOf(
                { tryOpenBackend(Backend.GPU) },
                { tryOpenGpuForce() },
                { tryOpenGpuDetHybrid() },
                { tryOpenBackend(Backend.CPU) },
            )
            Backend.CPU -> listOf { tryOpenBackend(Backend.CPU) }
        }
        var last: Throwable? = null
        for (attempt in attempts) {
            try {
                val models = attempt()
                ownedDelegates.addAll(models.delegates)
                initLine("opened interpreters with ${models.backend}")
                warmup(models.det, models.cls, models.rec)
                initLine("warmup ok backend=${models.backend}")
                return Models(models.det, models.cls, models.rec, models.backend)
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "backend attempt fail", t)
                initLine("backend fail: ${t.javaClass.simpleName}: ${t.message}")
                t.cause?.let { initLine("  cause: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        throw last ?: IllegalStateException("no OCR backend")
    }

    /** CompatibilityList 报不支持时仍强制试 GpuDelegate（部分机型探测偏保守） */
    private fun tryOpenGpuForce(): Opened {
        initLine("try GPU force (ignore CompatibilityList)")
        val created = ArrayList<AutoCloseable>()
        return try {
            fun gpuOpt(tag: String): Interpreter.Options {
                val o = Interpreter.Options().apply { setNumThreads(4) }
                val d = GpuDelegate()
                created.add(d)
                o.addDelegate(d)
                initLine("  $tag: GpuDelegate() default")
                return o
            }
            val d = Interpreter(loadAsset("ocr/det.tflite"), gpuOpt("det"))
            val c = Interpreter(loadAsset("ocr/cls.tflite"), gpuOpt("cls"))
            val r = Interpreter(loadAsset("ocr/rec.tflite"), gpuOpt("rec"))
            Opened(d, c, r, "GPU", created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
    }

    /**
     * 混合：det 走 GPU（密集卷积），cls/rec 走 CPU。
     * 部分机型全图 GpuDelegate 在 cls/rec 上 apply 失败，但 det 可成功。
     */
    private fun tryOpenGpuDetHybrid(): Opened {
        initLine("try hybrid GPU(det)+CPU(cls/rec)")
        val created = ArrayList<AutoCloseable>()
        return try {
            fun detGpuOpt(force: Boolean): Interpreter.Options {
                val o = Interpreter.Options().apply { setNumThreads(4) }
                val d = if (force) {
                    GpuDelegate()
                } else {
                    val compat = CompatibilityList()
                    if (!compat.isDelegateSupportedOnThisDevice) {
                        error("GPU not supported (CompatibilityList)")
                    }
                    GpuDelegate(compat.bestOptionsForThisDevice)
                }
                created.add(d)
                o.addDelegate(d)
                initLine("  det: GpuDelegate ${if (force) "force" else "bestOptions"}")
                return o
            }
            fun cpuOpt(tag: String): Interpreter.Options {
                initLine("  $tag: CPU threads=4")
                return Interpreter.Options().apply { setNumThreads(4) }
            }
            val d = try {
                Interpreter(loadAsset("ocr/det.tflite"), detGpuOpt(force = false))
            } catch (t: Throwable) {
                initLine("  det bestOptions fail, force: ${t.message}")
                created.forEach { runCatching { it.close() } }
                created.clear()
                Interpreter(loadAsset("ocr/det.tflite"), detGpuOpt(force = true))
            }
            val c = Interpreter(loadAsset("ocr/cls.tflite"), cpuOpt("cls"))
            val r = Interpreter(loadAsset("ocr/rec.tflite"), cpuOpt("rec"))
            Opened(d, c, r, "GPU+CPU", created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
    }

    private data class Opened(
        val det: Interpreter,
        val cls: Interpreter,
        val rec: Interpreter,
        val backend: String,
        val delegates: List<AutoCloseable>,
    )

    /** CPU / GPU（CompatibilityList）路径 */
    private fun tryOpenBackend(b: Backend): Opened {
        val created = ArrayList<AutoCloseable>()
        val label = when (b) {
            Backend.GPU -> "GPU"
            Backend.CPU, Backend.AUTO -> "CPU"
        }
        fun optionsFor(modelTag: String): Interpreter.Options {
            val o = Interpreter.Options().apply { setNumThreads(4) }
            when (b) {
                Backend.GPU -> {
                    val compat = CompatibilityList()
                    if (!compat.isDelegateSupportedOnThisDevice) {
                        error("GPU not supported on this device (CompatibilityList)")
                    }
                    val d = GpuDelegate(compat.bestOptionsForThisDevice)
                    created.add(d)
                    o.addDelegate(d)
                    initLine("  $modelTag: GpuDelegate bestOptions")
                }
                Backend.CPU, Backend.AUTO -> Unit
            }
            return o
        }
        return try {
            val d = Interpreter(loadAsset("ocr/det.tflite"), optionsFor("det"))
            val c = Interpreter(loadAsset("ocr/cls.tflite"), optionsFor("cls"))
            val r = Interpreter(loadAsset("ocr/rec.tflite"), optionsFor("rec"))
            Opened(d, c, r, label, created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
    }

    private fun warmup(d: Interpreter, c: Interpreter, r: Interpreter) {
        fun zeros(shape: IntArray): ByteBuffer {
            var n = 1
            for (s in shape) n *= s.coerceAtLeast(1)
            return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        }
        /** 按输出 tensor shape 分配嵌套 Float 数组（仅 float32 暖机） */
        fun allocOut(shape: IntArray): Any {
            return when (shape.size) {
                1 -> FloatArray(shape[0].coerceAtLeast(1))
                2 -> Array(shape[0].coerceAtLeast(1)) {
                    FloatArray(shape[1].coerceAtLeast(1))
                }
                3 -> Array(shape[0].coerceAtLeast(1)) {
                    Array(shape[1].coerceAtLeast(1)) {
                        FloatArray(shape[2].coerceAtLeast(1))
                    }
                }
                4 -> Array(shape[0].coerceAtLeast(1)) {
                    Array(shape[1].coerceAtLeast(1)) {
                        Array(shape[2].coerceAtLeast(1)) {
                            FloatArray(shape[3].coerceAtLeast(1))
                        }
                    }
                }
                else -> error("unsupported out rank ${shape.size}")
            }
        }
        val din = d.getInputTensor(0).shape()
        val cin = c.getInputTensor(0).shape()
        val rin = r.getInputTensor(0).shape()
        d.run(zeros(din), allocOut(d.getOutputTensor(0).shape()))
        c.run(zeros(cin), allocOut(c.getOutputTensor(0).shape()))
        r.run(zeros(rin), allocOut(r.getOutputTensor(0).shape()))
    }

    /**
     * @param detThr 检测阈值，默认 0.3；暗色/浅灰字可略降
     * @param autoInvert 无字且画面偏暗时自动反色再识（夜间截图）
     */
    fun recognize(
        bitmap: Bitmap,
        detThr: Float = 0.3f,
        autoInvert: Boolean = true,
    ): OcrResult {
        logBuf.clear()
        line("image ${bitmap.width}x${bitmap.height} config=${bitmap.config} thr=$detThr")
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val totalStart = System.currentTimeMillis()
        val mean = sampleMeanLuma(argb)
        line("meanLuma=${String.format(java.util.Locale.US, "%.1f", mean)}")

        // 浅灰底浅灰字：先做对比度拉伸，再 det
        val enhanced = enhanceContrast(argb, mean)
        var work = enhanced ?: argb
        var result = recognizeArgb(work, detThr)
        line("pass0 lines=${result.lines.size}")

        // 夜间/仍空：反色 + 低阈值
        if (autoInvert && result.lines.isEmpty()) {
            val invSrc = work
            val inv = invertArgb(invSrc)
            try {
                line("retry invert thr=$detThr")
                val r2 = recognizeArgb(inv, detThr)
                if (r2.lines.size > result.lines.size) {
                    result = r2
                    line("invert better lines=${r2.lines.size}")
                }
                if (result.lines.isEmpty()) {
                    line("retry invert thr=0.12")
                    val r3 = recognizeArgb(inv, 0.12f)
                    if (r3.lines.isNotEmpty()) {
                        result = r3
                        line("invert+lowThr lines=${r3.lines.size}")
                    }
                }
            } finally {
                if (inv !== invSrc && !inv.isRecycled) inv.recycle()
            }
            if (result.lines.isEmpty()) {
                line("retry enhance thr=0.12")
                val r4 = recognizeArgb(work, 0.12f)
                if (r4.lines.isNotEmpty()) result = r4
            }
        }

        if (enhanced != null && enhanced !== argb && !enhanced.isRecycled) {
            enhanced.recycle()
        }
        if (argb !== bitmap && !argb.isRecycled) argb.recycle()
        val totalMs = System.currentTimeMillis() - totalStart
        line("total ${totalMs}ms finalLines=${result.lines.size}")
        return result.copy(totalMs = totalMs, log = logBuf.toString())
    }

    /** 对比度拉伸：浅字/浅底时提高 det 响应 */
    private fun enhanceContrast(src: Bitmap, meanLuma: Float): Bitmap? {
        // 均值已较正常且对比够时跳过
        if (meanLuma in 90f..170f) return null
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        // 用固定因子把灰度拉开
        val factor = when {
            meanLuma > 200f -> 2.2f // 很浅
            meanLuma > 170f -> 1.8f
            meanLuma < 80f -> 1.6f // 偏暗
            else -> 1.4f
        }
        val mid = meanLuma
        for (i in px.indices) {
            val c = px[i]
            val a = c and 0xFF000000.toInt()
            var r = ((c shr 16) and 0xFF).toFloat()
            var g = ((c shr 8) and 0xFF).toFloat()
            var b = (c and 0xFF).toFloat()
            r = ((r - mid) * factor + mid).coerceIn(0f, 255f)
            g = ((g - mid) * factor + mid).coerceIn(0f, 255f)
            b = ((b - mid) * factor + mid).coerceIn(0f, 255f)
            px[i] = a or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun recognizeArgb(argb: Bitmap, detThr: Float): OcrResult {
        var detMs = 0L
        var recMs = 0L
        val boxes: List<FloatArray>
        val scale: Float

        detMs = measureTimeMillis {
            val packed = prepareDet(argb, detInH, detInW)
            scale = packed.scale
            val map = runDetMap(packed.buffer)
            boxes = boxesFromMap(map, scale, thr = detThr.coerceIn(0.08f, 0.6f), minArea = 12f)
        }
        line("det boxes=${boxes.size} ${detMs}ms thr=$detThr scale=$scale")

        val lines = ArrayList<LineResult>()
        recMs = measureTimeMillis {
            for (box in boxes) {
                val crop = cropBox(argb, box) ?: continue
                var work = crop
                val clsBuf = prepareClsOrRec(work, clsInH, clsInW)
                val clsOut = Array(1) { FloatArray(2) }
                cls.run(clsBuf, clsOut)
                if (clsOut[0][1] > clsOut[0][0]) {
                    work = rotate180(work)
                    if (work !== crop) crop.recycle()
                }
                val recBuf = prepareClsOrRec(work, recInH, recInW)
                val recOutShape = rec.getOutputTensor(0).shape()
                val tLen = recOutShape[1]
                val nCls = recOutShape[2]
                val recOut = Array(1) { Array(tLen) { FloatArray(nCls) } }
                rec.run(recBuf, recOut)
                val text = ctcDecode(recOut[0])
                if (work !== crop && work !== argb) work.recycle()
                else if (crop !== argb && work === crop) crop.recycle()
                if (text.isNotBlank()) {
                    lines += LineResult(text = text, box = box)
                }
            }
        }
        line("cls+rec ${recMs}ms lines=${lines.size}")
        return OcrResult(
            lines = lines,
            backend = backendName,
            detMs = detMs,
            recMs = recMs,
            totalMs = detMs + recMs,
            log = "",
        )
    }

    private fun sampleMeanLuma(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return 255f
        val stepX = max(1, w / 32)
        val stepY = max(1, h / 32)
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += (r * 3 + g * 6 + b) / 10
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n > 0) sum.toFloat() / n else 255f
    }

    private fun invertArgb(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c and 0xFF000000.toInt()
            val r = 255 - ((c shr 16) and 0xFF)
            val g = 255 - ((c shr 8) and 0xFF)
            val b = 255 - (c and 0xFF)
            px[i] = a or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    override fun close() {
        runCatching { det.close() }
        runCatching { cls.close() }
        runCatching { rec.close() }
        ownedDelegates.forEach { runCatching { it.close() } }
        ownedDelegates.clear()
    }

    private fun loadAsset(path: String): MappedByteBuffer {
        val fd = app.assets.openFd(path)
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun loadCharset(): List<String> {
        val raw = app.assets.open("ocr/ppocr_keys.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
            .map { it }
        val chars = raw.toMutableList()
        if (" " !in chars) chars.add(" ")
        // blank @ 0
        return listOf("blank") + chars
    }

    private fun line(s: String) {
        logBuf.append(s).append('\n')
        Log.i(TAG, s)
    }

    // ---------- preprocess ----------

    private data class DetPacked(val buffer: ByteBuffer, val scale: Float)

    private fun prepareDet(src: Bitmap, th: Int, tw: Int): DetPacked {
        val h = src.height
        val w = src.width
        val scale = min(th / h.toFloat(), tw / w.toFloat())
        val nh = max(1, (h * scale).roundToInt())
        val nw = max(1, (w * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        val canvasBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(0xFF000000.toInt())
        canvas.drawBitmap(resized, 0f, 0f, null)
        if (resized !== src) resized.recycle()

        val buf = ByteBuffer.allocateDirect(1 * th * tw * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(tw * th)
        canvasBmp.getPixels(pixels, 0, tw, 0, 0, tw, th)
        canvasBmp.recycle()
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            // 模型从 OpenCV BGR 训练路径导出，但我们用 RGB 图；
            // 导出时 letterbox 用的是 BGR 顺序的 float。
            // Python: cv2 读 BGR → 归一化 → NHWC 转置。这里按 B,G,R 写入以对齐。
            buf.putFloat((b - 0.5f) / 0.5f)
            buf.putFloat((g - 0.5f) / 0.5f)
            buf.putFloat((r - 0.5f) / 0.5f)
            i++
        }
        buf.rewind()
        return DetPacked(buf, scale)
    }

    private fun prepareClsOrRec(src: Bitmap, th: Int, tw: Int): ByteBuffer {
        val h = src.height
        val w = src.width
        val ratio = w / h.toFloat()
        val rw = min(tw, max(1, ceil(th * ratio).toInt()))
        val resized = Bitmap.createScaledBitmap(src, rw, th, true)
        val canvasBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(0xFF000000.toInt())
        canvas.drawBitmap(resized, 0f, 0f, null)
        if (resized !== src) resized.recycle()

        val buf = ByteBuffer.allocateDirect(1 * th * tw * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(tw * th)
        canvasBmp.getPixels(pixels, 0, tw, 0, 0, tw, th)
        canvasBmp.recycle()
        for (c in pixels) {
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            buf.putFloat((b - 0.5f) / 0.5f)
            buf.putFloat((g - 0.5f) / 0.5f)
            buf.putFloat((r - 0.5f) / 0.5f)
        }
        buf.rewind()
        return buf
    }

    // ---------- det postprocess (connected components + AABB) ----------

    /**
     * det 输出两种布局都兼容：
     * - 旧模型 NHWC: [1,H,W,1]
     * - onnx2tf 新模型 NCHW-like: [1,1,H,W]
     * 统一转成 [H][W][1] 供 [boxesFromMap]。
     */
    private fun runDetMap(input: ByteBuffer): Array<Array<FloatArray>> {
        val shape = det.getOutputTensor(0).shape()
        return when {
            // [1, H, W, 1]
            shape.size == 4 && shape[3] == 1 -> {
                val h = shape[1]
                val w = shape[2]
                val out = Array(1) { Array(h) { Array(w) { FloatArray(1) } } }
                det.run(input, out)
                out[0]
            }
            // [1, 1, H, W]
            shape.size == 4 && shape[1] == 1 -> {
                val h = shape[2]
                val w = shape[3]
                val raw = Array(1) { Array(1) { Array(h) { FloatArray(w) } } }
                det.run(input, raw)
                Array(h) { y ->
                    Array(w) { x ->
                        floatArrayOf(raw[0][0][y][x])
                    }
                }
            }
            else -> {
                // 回退：按输入 letterbox 尺寸 [1, detInH, detInW, 1]
                val out = Array(1) { Array(detInH) { Array(detInW) { FloatArray(1) } } }
                det.run(input, out)
                out[0]
            }
        }
    }

    private fun boxesFromMap(
        map: Array<Array<FloatArray>>, // [H][W][1]
        scale: Float,
        thr: Float,
        minArea: Float,
    ): List<FloatArray> {
        val h = map.size
        val w = map[0].size
        val bin = BooleanArray(h * w)
        for (y in 0 until h) {
            val row = map[y]
            for (x in 0 until w) {
                bin[y * w + x] = row[x][0] > thr
            }
        }
        val visited = BooleanArray(h * w)
        val boxes = ArrayList<FloatArray>()
        val qx = IntArray(h * w)
        val qy = IntArray(h * w)
        for (sy in 0 until h) {
            for (sx in 0 until w) {
                val sid = sy * w + sx
                if (!bin[sid] || visited[sid]) continue
                var head = 0
                var tail = 0
                qx[tail] = sx
                qy[tail] = sy
                tail++
                visited[sid] = true
                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var area = 0
                while (head < tail) {
                    val x = qx[head]
                    val y = qy[head]
                    head++
                    area++
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    // 4-neigh
                    val dirs = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)
                    var k = 0
                    while (k < 8) {
                        val nx = x + dirs[k]
                        val ny = y + dirs[k + 1]
                        k += 2
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val nid = ny * w + nx
                        if (!bin[nid] || visited[nid]) continue
                        visited[nid] = true
                        qx[tail] = nx
                        qy[tail] = ny
                        tail++
                    }
                }
                if (area < minArea) continue
                // 映射回原图，略扩展
                val pad = 2f
                val x0 = ((minX - pad) / scale).coerceAtLeast(0f)
                val y0 = ((minY - pad) / scale).coerceAtLeast(0f)
                val x1 = ((maxX + 1 + pad) / scale)
                val y1 = ((maxY + 1 + pad) / scale)
                // box as 4 points TL TR BR BL
                boxes += floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
            }
        }
        // 按 y 再 x 排序
        boxes.sortWith(compareBy({ it[1] }, { it[0] }))
        return boxes
    }

    private fun cropBox(src: Bitmap, box: FloatArray): Bitmap? {
        val xs = floatArrayOf(box[0], box[2], box[4], box[6])
        val ys = floatArrayOf(box[1], box[3], box[5], box[7])
        var minX = xs.min()
        var maxX = xs.max()
        var minY = ys.min()
        var maxY = ys.max()
        minX = minX.coerceIn(0f, (src.width - 1).toFloat())
        maxX = maxX.coerceIn(0f, (src.width - 1).toFloat())
        minY = minY.coerceIn(0f, (src.height - 1).toFloat())
        maxY = maxY.coerceIn(0f, (src.height - 1).toFloat())
        val bw = max(1, (maxX - minX).roundToInt())
        val bh = max(1, (maxY - minY).roundToInt())
        if (bw < 2 || bh < 2) return null
        return try {
            Bitmap.createBitmap(src, minX.roundToInt(), minY.roundToInt(), bw, bh)
        } catch (_: Throwable) {
            null
        }
    }

    private fun rotate180(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun ctcDecode(logits: Array<FloatArray>): String {
        // [T][C]
        val sb = StringBuilder()
        var prev = -1
        for (t in logits.indices) {
            val row = logits[t]
            var best = 0
            var bestV = row[0]
            for (c in 1 until row.size) {
                if (row[c] > bestV) {
                    bestV = row[c]
                    best = c
                }
            }
            if (best == 0 || best == prev) {
                prev = best
                continue
            }
            prev = best
            if (best in charset.indices && charset[best] != "blank") {
                sb.append(charset[best])
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "TfliteOcr"

        fun probeBackends(context: Context): String {
            val sb = StringBuilder()
            sb.append("TFLite PP-OCRv4 mobile (Umi-OCR Rapid 同源)\n")
            sb.append("models: assets/ocr/{det,cls,rec}.tflite\n")
            try {
                val compat = CompatibilityList()
                sb.append("GPU delegate: ${if (compat.isDelegateSupportedOnThisDevice) "支持" else "不支持"}\n")
            } catch (t: Throwable) {
                sb.append("GPU: ${t.message}\n")
            }
            sb.append("优先顺序: GPU → GPU(force) → GPU(det)+CPU → CPU\n")
            // 模型是否在 assets
            try {
                val names = context.assets.list("ocr")?.toList().orEmpty()
                sb.append("assets/ocr: ${names.joinToString()}\n")
            } catch (t: Throwable) {
                sb.append("assets list fail: ${t.message}\n")
            }
            return sb.toString()
        }
    }
}
