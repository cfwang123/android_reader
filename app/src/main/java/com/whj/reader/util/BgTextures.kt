package com.whj.reader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.whj.reader.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin
import kotlin.random.Random

/**
 * 阅读背景预设纹理（运行时生成平铺图，不打包大图）。
 */
object BgTextures {

    const val NONE = ""
    const val PAPER = "paper"
    const val KRAFT = "kraft"
    const val LINEN = "linen"
    const val GRID = "grid"
    const val DOTS = "dots"
    const val PARCHMENT = "parchment"
    const val NIGHT_GRAIN = "night_grain"
    /** 用户导入的背景图 */
    const val IMPORT = "import"

    data class Spec(
        val id: String,
        val labelRes: Int,
        /** 主色，用于推导默认字色 */
        val baseColor: Int,
    )

    /** 预设纹理（不含「导入」；导入在 UI 单独按钮） */
    val PRESETS: List<Spec> = listOf(
        Spec(NONE, R.string.bg_texture_none, 0xFFF7F4ED.toInt()),
        Spec(PAPER, R.string.bg_texture_paper, 0xFFF5F0E6.toInt()),
        Spec(KRAFT, R.string.bg_texture_kraft, 0xFFE8D5B5.toInt()),
        Spec(LINEN, R.string.bg_texture_linen, 0xFFEDE8DF.toInt()),
        Spec(GRID, R.string.bg_texture_grid, 0xFFF7F7F4.toInt()),
        Spec(DOTS, R.string.bg_texture_dots, 0xFFF4F1EA.toInt()),
        Spec(PARCHMENT, R.string.bg_texture_parchment, 0xFFF0E4C8.toInt()),
        Spec(NIGHT_GRAIN, R.string.bg_texture_night_grain, 0xFF1C1C1E.toInt()),
    )

    private val tileCache = ConcurrentHashMap<String, Bitmap>()

    fun isKnown(id: String): Boolean =
        id.isBlank() || PRESETS.any { it.id == id }

    fun baseColor(id: String): Int? =
        PRESETS.firstOrNull { it.id == id && it.id.isNotBlank() }?.baseColor

    fun label(ctx: Context, id: String): String {
        val spec = PRESETS.firstOrNull { it.id == id } ?: return ctx.getString(R.string.bg_texture_none)
        return ctx.getString(spec.labelRes)
    }

    /**
     * 平铺纹理 Drawable；[NONE] 返回 null（用纯色主题）。
     */
    fun tiledDrawable(ctx: Context, id: String): Drawable? {
        if (id.isBlank() || id == NONE || id == IMPORT) return null
        val tile = tileCache.getOrPut(id) { generateTile(id) }
        return BitmapDrawable(ctx.resources, tile).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            setAntiAlias(true)
        }
    }

    /** 导入背景图：filesDir/bg/[fileName] */
    fun importDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "bg").also { it.mkdirs() }

    fun importFile(ctx: Context, fileName: String): java.io.File? {
        if (fileName.isBlank()) return null
        val f = java.io.File(importDir(ctx), fileName)
        return f.takeIf { it.isFile }
    }

    fun importedDrawable(ctx: Context, fileName: String): Drawable? {
        val f = importFile(ctx, fileName) ?: return null
        val bmp = runCatching {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull() ?: return null
        return BitmapDrawable(ctx.resources, bmp).apply {
            gravity = android.view.Gravity.FILL
        }
    }

    /**
     * 从 content URI 导入并覆盖为 custom_bg.ext
     * @return 保存的文件名，失败 null
     */
    fun importFromUri(ctx: Context, uri: android.net.Uri): String? {
        return try {
            val nameHint = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull()
            val ext = when {
                nameHint?.endsWith(".png", true) == true -> "png"
                nameHint?.endsWith(".webp", true) == true -> "webp"
                else -> "jpg"
            }
            val destName = "custom_bg.$ext"
            val dest = java.io.File(importDir(ctx), destName)
            // 清掉其它扩展名的旧图
            importDir(ctx).listFiles()?.forEach { f ->
                if (f.name.startsWith("custom_bg.")) f.delete()
            }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            if (!dest.isFile || dest.length() <= 0L) {
                dest.delete()
                return null
            }
            destName
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
        tileCache.clear()
    }

    private fun generateTile(id: String): Bitmap {
        val size = 192
        return when (id) {
            PAPER -> genNoise(size, 0xFFF5F0E6.toInt(), 18, seed = 11)
            KRAFT -> genNoise(size, 0xFFE8D5B5.toInt(), 28, seed = 22, warm = true)
            LINEN -> genLinen(size, 0xFFEDE8DF.toInt(), seed = 33)
            GRID -> genGrid(size, 0xFFF7F7F4.toInt(), 0xFFD8D8D0.toInt())
            DOTS -> genDots(size, 0xFFF4F1EA.toInt(), 0xFFC8C0B0.toInt(), seed = 44)
            PARCHMENT -> genParchment(size, seed = 55)
            NIGHT_GRAIN -> genNoise(size, 0xFF1C1C1E.toInt(), 22, seed = 66, dark = true)
            else -> genNoise(size, 0xFFF5F0E6.toInt(), 12, seed = 1)
        }
    }

    private fun genNoise(
        size: Int,
        base: Int,
        variance: Int,
        seed: Int,
        warm: Boolean = false,
        dark: Boolean = false,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = Random(seed)
        val br = Color.red(base)
        val bg = Color.green(base)
        val bb = Color.blue(base)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val n = rnd.nextInt(-variance, variance + 1)
                var r = (br + n).coerceIn(0, 255)
                var g = (bg + n + if (warm) rnd.nextInt(-4, 5) else 0).coerceIn(0, 255)
                var b = (bb + n + if (warm) rnd.nextInt(-6, 3) else 0).coerceIn(0, 255)
                if (dark) {
                    // 轻微颗粒
                    val grain = rnd.nextInt(0, 12)
                    r = (r + grain).coerceIn(0, 255)
                    g = (g + grain).coerceIn(0, 255)
                    b = (b + grain).coerceIn(0, 255)
                }
                pixels[y * size + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun genLinen(size: Int, base: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.2f
            color = 0x22A09080
        }
        val rnd = Random(seed)
        // 横竖细线
        var y = 0f
        while (y < size) {
            paint.color = Color.argb(18 + rnd.nextInt(12), 120, 100, 80)
            c.drawLine(0f, y, size.toFloat(), y + rnd.nextInt(-1, 2), paint)
            y += 3.5f + rnd.nextFloat()
        }
        var x = 0f
        while (x < size) {
            paint.color = Color.argb(14 + rnd.nextInt(10), 100, 90, 70)
            c.drawLine(x, 0f, x + rnd.nextInt(-1, 2), size.toFloat(), paint)
            x += 4f + rnd.nextFloat()
        }
        return bmp
    }

    private fun genGrid(size: Int, base: Int, line: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        val paint = Paint().apply {
            color = line
            strokeWidth = 1f
            alpha = 90
        }
        val step = size / 8f
        var i = 0f
        while (i <= size) {
            c.drawLine(i, 0f, i, size.toFloat(), paint)
            c.drawLine(0f, i, size.toFloat(), i, paint)
            i += step
        }
        return bmp
    }

    private fun genDots(size: Int, base: Int, dot: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dot
            alpha = 55
            style = Paint.Style.FILL
        }
        val rnd = Random(seed)
        val step = 12
        for (y in 0 until size step step) {
            for (x in 0 until size step step) {
                val ox = x + rnd.nextInt(0, 3)
                val oy = y + rnd.nextInt(0, 3)
                c.drawCircle(ox.toFloat(), oy.toFloat(), 1.4f, paint)
            }
        }
        return bmp
    }

    private fun genParchment(size: Int, seed: Int): Bitmap {
        val base = 0xFFF0E4C8.toInt()
        val bmp = genNoise(size, base, 24, seed, warm = true)
        val c = Canvas(bmp)
        // 边缘淡影
        val edge = Paint().apply {
            shader = LinearGradient(
                0f, 0f, size * 0.3f, 0f,
                0x28A08050, 0x00A08050, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), edge)
        val rnd = Random(seed + 9)
        val blot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x18B09050
            style = Paint.Style.FILL
        }
        repeat(12) {
            val cx = rnd.nextFloat() * size
            val cy = rnd.nextFloat() * size
            val r = 8f + rnd.nextFloat() * 22f
            blot.alpha = 10 + rnd.nextInt(18)
            c.drawCircle(cx, cy, r, blot)
        }
        // 细波纹
        val wave = Paint().apply {
            color = 0x14A08040
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        for (i in 0 until 6) {
            val pathY = size * (0.15f + i * 0.14f)
            var x = 0f
            while (x < size) {
                val y2 = pathY + (sin(x * 0.08 + i) * 3).toFloat()
                c.drawPoint(x, y2, wave)
                x += 1.5f
            }
        }
        return bmp
    }
}
