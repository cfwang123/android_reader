package com.whj.reader.pdf.chrome

import android.content.Intent
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读页底栏时钟 / 电量文案。
 */
object PdfStatusBarHelper {

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun formatClock(now: Date = Date()): String = clockFmt.format(now)

    /**
     * @return 如 "85%"；无法解析返回 null
     */
    fun formatBattery(intent: Intent): String? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val pct = (level * 100f / scale).toInt().coerceIn(0, 100)
        return "$pct%"
    }
}
