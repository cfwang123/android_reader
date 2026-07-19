package com.whj.reader.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
 * 优先 NNAPI（高通 NPU/DSP）→ GPU → CPU。
 */
class TfliteOcrEngine(
    context: Context,
    preferred: Backend = Backend.AUTO,
) : Closeable {

    enum class Backend { AUTO, NNAPI, GPU, CPU }

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
        backendName = models.backend // NNAPI / GPU / CPU
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
        // AUTO：NNAPI(setUseNNAPI) → NNAPI(NnApiDelegate) → 混合 → GPU → CPU
        val attempts: List<() -> Opened> = when (preferred) {
            Backend.AUTO -> listOf(
                { tryOpenNnapi(mode = NnapiMode.USE_NNAPI_FLAG) },
                { tryOpenNnapi(mode = NnapiMode.NNAPI_DELEGATE) },
                { tryOpenHybridNnapi() },
                { tryOpenBackend(Backend.GPU) },
                { tryOpenBackend(Backend.CPU) },
            )
            Backend.NNAPI -> listOf(
                { tryOpenNnapi(mode = NnapiMode.USE_NNAPI_FLAG) },
                { tryOpenNnapi(mode = NnapiMode.NNAPI_DELEGATE) },
                { tryOpenHybridNnapi() },
                // 混合也试 NnApiDelegate
                { tryOpenHybridNnapiDelegate() },
                { tryOpenBackend(Backend.CPU) },
            )
            Backend.GPU -> listOf(
                { tryOpenBackend(Backend.GPU) },
                { tryOpenGpuForce() },
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

    private enum class NnapiMode { USE_NNAPI_FLAG, NNAPI_DELEGATE }

    private fun nnapiOptions(
        tag: String,
        mode: NnapiMode,
        created: MutableList<AutoCloseable>,
    ): Interpreter.Options {
        val o = Interpreter.Options().apply { setNumThreads(4) }
        when (mode) {
            NnapiMode.USE_NNAPI_FLAG -> {
                @Suppress("DEPRECATION")
                o.setUseNNAPI(true)
                initLine("  $tag: setUseNNAPI(true)")
            }
            NnapiMode.NNAPI_DELEGATE -> {
                val nnOpt = NnApiDelegate.Options().apply {
                    setAllowFp16(true)
                    setExecutionPreference(
                        NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED,
                    )
                    // 允许部分算子回落 CPU，提高 apply 成功率
                    setUseNnapiCpu(true)
                }
                val d = NnApiDelegate(nnOpt)
                created.add(d)
                o.addDelegate(d)
                initLine("  $tag: NnApiDelegate(allowFp16, useNnapiCpu)")
            }
        }
        return o
    }

    /** 全量 det/cls/rec 走 NNAPI（两种绑定方式分开尝试） */
    private fun tryOpenNnapi(mode: NnapiMode): Opened {
        val label = when (mode) {
            NnapiMode.USE_NNAPI_FLAG -> "NNAPI"
            NnapiMode.NNAPI_DELEGATE -> "NNAPI-DLG"
        }
        initLine("try full $label")
        val created = ArrayList<AutoCloseable>()
        return try {
            val d = Interpreter(loadAsset("ocr/det.tflite"), nnapiOptions("det", mode, created))
            val c = Interpreter(loadAsset("ocr/cls.tflite"), nnapiOptions("cls", mode, created))
            val r = Interpreter(loadAsset("ocr/rec.tflite"), nnapiOptions("rec", mode, created))
            // 立刻跑一次，尽早暴露 apply/allocate 失败
            warmup(d, c, r)
            Opened(d, c, r, label, created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
    }

    /**
     * det/cls 用 setUseNNAPI，rec 走 CPU。
     */
    private fun tryOpenHybridNnapi(): Opened {
        initLine("try hybrid: det/cls=setUseNNAPI, rec=CPU")
        val created = ArrayList<AutoCloseable>()
        val cpu = Interpreter.Options().apply { setNumThreads(4) }
        return try {
            val d = Interpreter(
                loadAsset("ocr/det.tflite"),
                nnapiOptions("det", NnapiMode.USE_NNAPI_FLAG, created),
            )
            val c = Interpreter(
                loadAsset("ocr/cls.tflite"),
                nnapiOptions("cls", NnapiMode.USE_NNAPI_FLAG, created),
            )
            val r = Interpreter(loadAsset("ocr/rec.tflite"), cpu)
            initLine("  hybrid rec: CPU")
            warmup(d, c, r)
            Opened(d, c, r, "NNAPI+CPU", created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
    }

    /** det/cls 用 NnApiDelegate，rec 走 CPU */
    private fun tryOpenHybridNnapiDelegate(): Opened {
        initLine("try hybrid: det/cls=NnApiDelegate, rec=CPU")
        val created = ArrayList<AutoCloseable>()
        val cpu = Interpreter.Options().apply { setNumThreads(4) }
        return try {
            val d = Interpreter(
                loadAsset("ocr/det.tflite"),
                nnapiOptions("det", NnapiMode.NNAPI_DELEGATE, created),
            )
            val c = Interpreter(
                loadAsset("ocr/cls.tflite"),
                nnapiOptions("cls", NnapiMode.NNAPI_DELEGATE, created),
            )
            val r = Interpreter(loadAsset("ocr/rec.tflite"), cpu)
            initLine("  hybrid rec: CPU")
            warmup(d, c, r)
            Opened(d, c, r, "NNAPI-DLG+CPU", created)
        } catch (t: Throwable) {
            created.forEach { runCatching { it.close() } }
            throw t
        }
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
            warmup(d, c, r)
            Opened(d, c, r, "GPU", created)
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
            Backend.NNAPI -> "NNAPI"
            Backend.GPU -> "GPU"
            Backend.CPU, Backend.AUTO -> "CPU"
        }
        fun optionsFor(modelTag: String): Interpreter.Options {
            val o = Interpreter.Options().apply { setNumThreads(4) }
            when (b) {
                Backend.NNAPI -> {
                    // 走 tryOpenNnapi，这里不应再进
                    @Suppress("DEPRECATION")
                    o.setUseNNAPI(true)
                    initLine("  $modelTag: setUseNNAPI(true)")
                }
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
        val din = d.getInputTensor(0).shape()
        val cin = c.getInputTensor(0).shape()
        val rin = r.getInputTensor(0).shape()
        d.run(zeros(din), Array(1) { Array(din[1]) { Array(din[2]) { FloatArray(1) } } })
        c.run(zeros(cin), Array(1) { FloatArray(2) })
        val t = r.getOutputTensor(0).shape()[1]
        val nc = r.getOutputTensor(0).shape()[2]
        r.run(zeros(rin), Array(1) { Array(t) { FloatArray(nc) } })
    }

    fun recognize(bitmap: Bitmap): OcrResult {
        logBuf.clear()
        line("image ${bitmap.width}x${bitmap.height} config=${bitmap.config}")
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val totalStart = System.currentTimeMillis()
        var detMs = 0L
        var recMs = 0L
        val boxes: List<FloatArray>
        val scale: Float

        detMs = measureTimeMillis {
            val packed = prepareDet(argb, detInH, detInW)
            scale = packed.scale
            val out = Array(1) { Array(detInH) { Array(detInW) { FloatArray(1) } } }
            det.run(packed.buffer, out)
            boxes = boxesFromMap(out[0], scale, thr = 0.3f, minArea = 16f)
        }
        line("det boxes=${boxes.size} ${detMs}ms scale=$scale")

        val lines = ArrayList<LineResult>()
        recMs = measureTimeMillis {
            for (box in boxes) {
                val crop = cropBox(argb, box) ?: continue
                var work = crop
                // cls: 0=0°, 1=180°
                val clsBuf = prepareClsOrRec(work, clsInH, clsInW)
                val clsOut = Array(1) { FloatArray(2) }
                cls.run(clsBuf, clsOut)
                if (clsOut[0][1] > clsOut[0][0]) {
                    work = rotate180(work)
                    if (work !== crop) crop.recycle()
                }
                val recBuf = prepareClsOrRec(work, recInH, recInW)
                val recOutShape = rec.getOutputTensor(0).shape() // [1,T,C]
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

        if (argb !== bitmap) argb.recycle()
        val totalMs = System.currentTimeMillis() - totalStart
        line("total ${totalMs}ms")
        return OcrResult(
            lines = lines,
            backend = backendName,
            detMs = detMs,
            recMs = recMs,
            totalMs = totalMs,
            log = logBuf.toString(),
        )
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
            sb.append("NNAPI: 可尝试（高通 NPU/Hexagon）\n")
            sb.append("优先顺序: NNAPI → GPU → CPU\n")
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
