package com.whj.reader.ocr

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 超长图 OCR：按近似屏高纵向分块，块间交叠一小段，识别后合并去重。
 *
 * 坐标约定：box 为 8 点 [x0,y0, x1,y1, x2,y2, x3,y3]（像素），
 * 分块识别后需把 y 平移到整页坐标系再 [mergeLines]。
 */
object OcrTileHelper {

    data class TileRange(
        /** 整页像素坐标：条带顶（含） */
        val topPx: Int,
        /** 整页像素坐标：条带底（不含） */
        val bottomPx: Int,
    ) {
        val height: Int get() = (bottomPx - topPx).coerceAtLeast(1)
    }

    /**
     * @param totalH 整页逻辑像素高
     * @param tileH 目标块高（≈0.65 屏高，利于 det）
     * @param overlap 相邻块交叠高度
     */
    fun verticalTiles(totalH: Int, tileH: Int, overlap: Int): List<TileRange> {
        val h = totalH.coerceAtLeast(1)
        val th = tileH.coerceIn(200, h.coerceAtLeast(200))
        val ov = overlap.coerceIn(0, th / 2)
        if (!shouldTile(h, th, totalW = 0)) {
            return listOf(TileRange(0, h))
        }
        val step = (th - ov).coerceAtLeast(1)
        val out = ArrayList<TileRange>()
        var top = 0
        while (top < h) {
            val bottom = min(h, top + th)
            out += TileRange(top, bottom)
            if (bottom >= h) break
            val next = top + step
            // 尾部：始终新开一块贴底，绝不把末块拉成超高条（会再次压扁 det）
            if (next + th >= h) {
                val lastTop = max(0, h - th)
                if (lastTop > top + ov / 2) {
                    out += TileRange(lastTop, h)
                } else {
                    // 与当前块重叠过多：直接延伸当前块到末尾（剩余很短）
                    out[out.lastIndex] = TileRange(top, h)
                }
                break
            }
            top = next
        }
        return out
    }

    /**
     * 是否分块 OCR。
     * 阈值故意偏低：手机截图长页 ≈ 屏高时整页进 det 会被纵向压扁，只认出上部。
     */
    fun shouldTile(totalH: Int, tileH: Int, totalW: Int = 0): Boolean {
        if (totalH <= 0) return false
        // 接近/超过块高的 80% 即分块
        if (totalH > (tileH * 0.80f).toInt()) return true
        // 竖向细长（高/宽 > 1.2）
        if (totalW > 0 && totalH.toFloat() / totalW > 1.20f) return true
        // 绝对高度兜底
        if (totalH > 1200) return true
        return false
    }

    /** 把块内坐标平移到整页 */
    fun offsetLines(
        lines: List<TfliteOcrEngine.LineResult>,
        dy: Float,
        dx: Float = 0f,
    ): List<TfliteOcrEngine.LineResult> {
        if (dy == 0f && dx == 0f) return lines
        return lines.map { line ->
            val box = line.box
            if (box == null || box.size < 8) {
                line
            } else {
                val nb = FloatArray(8) { i ->
                    if (i % 2 == 0) box[i] + dx else box[i] + dy
                }
                line.copy(box = nb)
            }
        }
    }

    /**
     * 合并多分块结果：按阅读顺序排序，重叠区近重复行只留一条（更长/更高分优先）。
     */
    fun mergeLines(parts: List<List<TfliteOcrEngine.LineResult>>): List<TfliteOcrEngine.LineResult> {
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1) return parts[0].sortedWith(lineOrder)
        val flat = ArrayList<TfliteOcrEngine.LineResult>(parts.sumOf { it.size })
        for (p in parts) flat.addAll(p)
        return dedupeLines(flat)
    }

    private val lineOrder = Comparator<TfliteOcrEngine.LineResult> { a, b ->
        val ra = rectOf(a.box)
        val rb = rectOf(b.box)
        val ya = ra?.centerY() ?: 0f
        val yb = rb?.centerY() ?: 0f
        val cy = ya.compareTo(yb)
        if (cy != 0) return@Comparator cy
        val xa = ra?.left ?: 0f
        val xb = rb?.left ?: 0f
        xa.compareTo(xb)
    }

    fun dedupeLines(lines: List<TfliteOcrEngine.LineResult>): List<TfliteOcrEngine.LineResult> {
        if (lines.size <= 1) return lines.sortedWith(lineOrder)
        val sorted = lines.sortedWith(lineOrder)
        val kept = ArrayList<TfliteOcrEngine.LineResult>(sorted.size)
        for (cand in sorted) {
            var drop = false
            var replaceAt = -1
            for (i in kept.indices) {
                val exist = kept[i]
                if (!isNearDuplicate(exist, cand)) continue
                val better = prefer(exist, cand)
                if (better === cand) {
                    replaceAt = i
                } else {
                    drop = true
                }
                break
            }
            when {
                drop -> Unit
                replaceAt >= 0 -> kept[replaceAt] = cand
                else -> kept += cand
            }
        }
        return kept.sortedWith(lineOrder)
    }

    private fun prefer(
        a: TfliteOcrEngine.LineResult,
        b: TfliteOcrEngine.LineResult,
    ): TfliteOcrEngine.LineResult {
        val ta = a.text.trim()
        val tb = b.text.trim()
        if (ta.length != tb.length) return if (ta.length >= tb.length) a else b
        if (a.score != b.score) return if (a.score >= b.score) a else b
        val aa = areaOf(a.box)
        val ba = areaOf(b.box)
        return if (aa >= ba) a else b
    }

    /**
     * 近重复：框 IoU 高，或（中心接近 + 文本相同/包含/高前缀重合）。
     */
    fun isNearDuplicate(
        a: TfliteOcrEngine.LineResult,
        b: TfliteOcrEngine.LineResult,
    ): Boolean {
        val ra = rectOf(a.box) ?: return false
        val rb = rectOf(b.box) ?: return false
        val iou = iou(ra, rb)
        if (iou >= 0.40f) return true

        val h = max(ra.height(), rb.height()).coerceAtLeast(1f)
        val w = max(ra.width(), rb.width()).coerceAtLeast(1f)
        if (abs(ra.centerY() - rb.centerY()) > h * 0.65f) return false
        if (abs(ra.centerX() - rb.centerX()) > w * 0.55f) return false
        // 纵向有一定重叠，且水平大致对齐
        val yOverlap = overlap1d(ra.top, ra.bottom, rb.top, rb.bottom)
        if (yOverlap < h * 0.35f) return false

        val ta = a.text.trim()
        val tb = b.text.trim()
        if (ta.isEmpty() || tb.isEmpty()) {
            // 无字时靠框 IoU 已处理；此处仅中心很近也算
            return iou >= 0.25f || (abs(ra.centerY() - rb.centerY()) < h * 0.35f &&
                abs(ra.centerX() - rb.centerX()) < w * 0.35f)
        }
        if (ta == tb) return true
        if (ta.contains(tb) || tb.contains(ta)) return true
        val pref = commonPrefixLen(ta, tb)
        val minLen = min(ta.length, tb.length)
        if (minLen >= 2 && pref >= (minLen * 0.7f).toInt().coerceAtLeast(2)) return true
        // 去空白后比较（OCR 偶发多空格）
        val na = ta.replace("\\s+".toRegex(), "")
        val nb = tb.replace("\\s+".toRegex(), "")
        if (na.isNotEmpty() && na == nb) return true
        return false
    }

    private fun rectOf(box: FloatArray?): RectF? {
        if (box == null || box.size < 8) return null
        val l = min(min(box[0], box[2]), min(box[4], box[6]))
        val r = max(max(box[0], box[2]), max(box[4], box[6]))
        val t = min(min(box[1], box[3]), min(box[5], box[7]))
        val b = max(max(box[1], box[3]), max(box[5], box[7]))
        if (r <= l || b <= t) return null
        return RectF(l, t, r, b)
    }

    private fun areaOf(box: FloatArray?): Float {
        val r = rectOf(box) ?: return 0f
        return r.width() * r.height()
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val btm = min(a.bottom, b.bottom)
        val iw = (r - l).coerceAtLeast(0f)
        val ih = (btm - t).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val ua = a.width() * a.height() + b.width() * b.height() - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    private fun overlap1d(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        return (min(a1, b1) - max(a0, b0)).coerceAtLeast(0f)
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
