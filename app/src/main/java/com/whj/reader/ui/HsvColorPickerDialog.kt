package com.whj.reader.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.whj.reader.R

/**
 * 专业 HSV 选色对话框。
 */
object HsvColorPickerDialog {

    fun show(
        context: Context,
        title: String,
        initialColor: Int,
        onPicked: (Int) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_hsv_color_picker, null)
        val titleView = view.findViewById<TextView>(R.id.tvHsvTitle)
        val svPanel = view.findViewById<HsvSvPanelView>(R.id.hsvSvPanel)
        val hueBar = view.findViewById<HsvHueBarView>(R.id.hsvHueBar)
        val preview = view.findViewById<View>(R.id.viewHsvPreview)
        val hexView = view.findViewById<TextView>(R.id.tvHsvHex)
        titleView.text = title

        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor or 0xFF000000.toInt(), hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var value = hsv[2]

        fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

        fun refreshPreview() {
            val c = currentColor()
            preview.backgroundTintList = android.content.res.ColorStateList.valueOf(c)
            hexView.text = String.format("#%06X", c and 0xFFFFFF)
        }

        svPanel.setHue(hue)
        svPanel.setSv(sat, value)
        hueBar.setHue(hue)
        refreshPreview()

        svPanel.onSvChanged = { s, v ->
            sat = s
            value = v
            refreshPreview()
        }
        hueBar.onHueChanged = { h ->
            hue = h
            svPanel.setHue(h)
            refreshPreview()
        }

        AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                onPicked(currentColor() or 0xFF000000.toInt())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
