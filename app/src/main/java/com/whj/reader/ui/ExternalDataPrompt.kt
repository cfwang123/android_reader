package com.whj.reader.ui

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.whj.reader.R
import com.whj.reader.SettingsActivity
import com.whj.reader.data.AppDataDir
import com.whj.reader.data.AppSettings

/**
 * 已绑定目录在启动后仍然写不了时，提示重新选择。
 * 刚进进程时外部存储常常还没就绪，立刻探测会误报，所以先等一会儿再确认。
 */
object ExternalDataPrompt {

    private const val CONFIRM_DELAY_MS = 1600L

    fun maybeShow(activity: Activity) {
        if (activity.isFinishing) return
        if (AppSettings.externalDataPath(activity).isBlank()) return
        if (AppDataDir.hasFreshOkProbe(activity)) return
        val decor = activity.window?.decorView ?: return
        decor.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (!activity.hasWindowFocus()) return@postDelayed
            if (!AppDataDir.confirmRebindNeeded(activity)) return@postDelayed
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            AlertDialog.Builder(activity)
                .setTitle(R.string.external_data_rebind_title)
                .setMessage(R.string.external_data_rebind_message)
                .setPositiveButton(R.string.external_data_rebind) { _, _ ->
                    activity.startActivity(
                        Intent(activity, SettingsActivity::class.java)
                            .putExtra(AppDataDir.EXTRA_PICK, true),
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, CONFIRM_DELAY_MS)
    }
}
