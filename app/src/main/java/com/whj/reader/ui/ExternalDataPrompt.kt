package com.whj.reader.ui

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.whj.reader.R
import com.whj.reader.SettingsActivity
import com.whj.reader.data.AppDataDir

/** 已绑定的外部目录读写失败时，提示重新选择。未绑定不提示。 */
object ExternalDataPrompt {

    fun maybeShow(activity: Activity) {
        if (activity.isFinishing) return
        if (!AppDataDir.consumeRebindPrompt(activity)) return
        if (activity.isFinishing) return
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
    }
}
