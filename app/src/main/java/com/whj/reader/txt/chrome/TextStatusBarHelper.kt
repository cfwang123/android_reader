package com.whj.reader.txt.chrome

import android.content.Intent
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TextStatusBarHelper {

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun formatClock(now: Date = Date()): String = clockFmt.format(now)

  /**
     * @return e.g. "85%" or "âš?5%"; null if unparseable
     */
    fun formatBattery(intent: Intent, chargingPrefix: Boolean = true): String? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return null
        val pct = (level * 100 / scale)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return when {
            charging && chargingPrefix -> "âš?pct%"
            else -> "$pct%"
        }
    }
}
