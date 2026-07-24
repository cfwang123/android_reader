package com.whj.reader.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.whj.reader.R
import com.whj.reader.model.Highlight
import com.whj.reader.model.HighlightColorPresets
import com.whj.reader.model.HighlightMode
import com.whj.reader.model.HighlightStyle
import com.whj.reader.model.UnderlineShape

object HighlightNotePopup {

    /** 只读查看：仅备注正文；点「修改」进入编辑。 */
    fun showView(
        activity: AppCompatActivity,
        highlight: Highlight,
        onEdit: (Highlight) -> Unit,
        onDelete: (Highlight) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        val dm = activity.resources.displayMetrics
        val viewH = (dm.heightPixels * 0.52f).toInt().coerceAtLeast(220)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_highlight_note_view, null)
        view.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            viewH,
        )
        view.findViewById<TextView>(R.id.tvNote).text =
            highlight.note.ifBlank { activity.getString(R.string.highlight_no_note) }

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.highlight_edit) { _, _ ->
                onEdit(highlight)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.highlight_delete) { _, _ ->
                onDelete(highlight)
            }
            .create()
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (dm.widthPixels * 0.92f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    fun showEdit(
        activity: AppCompatActivity,
        highlight: Highlight,
        onSave: (Highlight) -> Unit,
        onDelete: (Highlight) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_highlight_note, null)
        val tvExcerpt = view.findViewById<TextView>(R.id.tvExcerpt)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
        val rbBackground = view.findViewById<RadioButton>(R.id.rbBackground)
        val rbUnderline = view.findViewById<RadioButton>(R.id.rbUnderline)
        val rgUnderline = view.findViewById<RadioGroup>(R.id.rgUnderline)
        val rbSolid = view.findViewById<RadioButton>(R.id.rbSolid)
        val rbDashed = view.findViewById<RadioButton>(R.id.rbDashed)
        val rbWavy = view.findViewById<RadioButton>(R.id.rbWavy)
        val colorRow = view.findViewById<LinearLayout>(R.id.colorRow)
        val etNote = view.findViewById<EditText>(R.id.etNote)

        var style = highlight.style
        tvExcerpt.text = highlight.selectedText.ifBlank { "—" }
        etNote.setText(highlight.note)

        fun syncModeUi() {
            val underline = style.mode == HighlightMode.UNDERLINE
            rgUnderline.visibility = if (underline) View.VISIBLE else View.GONE
        }

        when (style.mode) {
            HighlightMode.BACKGROUND -> rbBackground.isChecked = true
            HighlightMode.UNDERLINE -> rbUnderline.isChecked = true
        }
        when (style.underlineShape) {
            UnderlineShape.SOLID -> rbSolid.isChecked = true
            UnderlineShape.DASHED -> rbDashed.isChecked = true
            UnderlineShape.WAVY -> rbWavy.isChecked = true
        }
        syncModeUi()

        val colorViews = mutableListOf<View>()
        var customChip: View? = null

        fun refreshColorSelection() {
            val presetIdx = HighlightColorPresets.indexOf(style.colorArgb)
            colorViews.forEachIndexed { i, v ->
                val selected = if (v === customChip) {
                    presetIdx < 0
                } else {
                    i == presetIdx
                }
                v.alpha = if (selected) 1f else 0.45f
            }
        }

        fun selectPreset(index: Int) {
            style = style.copy(colorArgb = HighlightColorPresets.colors[index])
            refreshColorSelection()
        }

        buildColorRow(activity, colorRow, colorViews) { chip ->
            customChip = chip
            refreshColorSelection()
        }
        colorViews.forEachIndexed { index, v ->
            if (v !== customChip) {
                v.setOnClickListener { selectPreset(index) }
            }
        }
        customChip?.setOnClickListener {
            HsvColorPickerDialog.show(
                activity,
                activity.getString(R.string.color_picker_highlight_title),
                style.colorArgb or 0xFF000000.toInt(),
            ) { picked ->
                style = style.copy(colorArgb = picked)
                refreshColorSelection()
            }
        }
        refreshColorSelection()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            style = style.copy(
                mode = when (checkedId) {
                    R.id.rbUnderline -> HighlightMode.UNDERLINE
                    else -> HighlightMode.BACKGROUND
                },
            )
            syncModeUi()
        }
        rgUnderline.setOnCheckedChangeListener { _, checkedId ->
            style = style.copy(
                underlineShape = when (checkedId) {
                    R.id.rbDashed -> UnderlineShape.DASHED
                    R.id.rbWavy -> UnderlineShape.WAVY
                    else -> UnderlineShape.SOLID
                },
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.highlight_note_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(
                    highlight.withUpdates(
                        note = etNote.text?.toString().orEmpty().trim(),
                        style = style,
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.highlight_delete) { _, _ ->
                onDelete(highlight)
            }
            .create()
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
    }

    private fun buildColorRow(
        activity: AppCompatActivity,
        row: LinearLayout,
        outViews: MutableList<View>,
        onCustomChipReady: (View) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val size = (28 * density).toInt()
        val gap = (8 * density).toInt()
        row.removeAllViews()
        outViews.clear()

        HighlightColorPresets.colors.forEach { color ->
            val swatch = makeColorSwatch(activity, size, gap, color)
            row.addView(swatch)
            outViews.add(swatch)
        }
        val custom = makeCustomColorChip(activity, size, gap)
        row.addView(custom)
        outViews.add(custom)
        onCustomChipReady(custom)
    }

    private fun makeColorSwatch(
        activity: AppCompatActivity,
        size: Int,
        marginEnd: Int,
        color: Int,
    ): View {
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.marginEnd = marginEnd
            }
            background = ContextCompat.getDrawable(activity, R.drawable.bg_color_swatch)?.mutate()
            backgroundTintList = android.content.res.ColorStateList.valueOf(color or 0xFF000000.toInt())
            contentDescription = String.format("#%06X", color and 0xFFFFFF)
        }
    }

    private fun makeCustomColorChip(
        activity: AppCompatActivity,
        size: Int,
        marginEnd: Int,
    ): View {
        return MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                size,
            ).also { it.marginEnd = marginEnd }
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = activity.getString(R.string.color_custom)
            gravity = Gravity.CENTER
        }
    }
}
