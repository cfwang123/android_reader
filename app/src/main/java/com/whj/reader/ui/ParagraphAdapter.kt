package com.whj.reader.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.databinding.ItemParagraphBinding
import com.whj.reader.model.Paragraph
import com.whj.reader.model.ReadStyle
import com.whj.reader.model.ReadTheme

class ParagraphAdapter(
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<ParagraphAdapter.VH>() {

    private var items: List<Paragraph> = emptyList()
    private var style: ReadStyle = ReadStyle()
    /** 仅 TTS 播放/暂停时高亮；-1 表示无高亮 */
    private var highlightIndex: Int = -1
    private var textColor: Int = 0xFF2C2C2C.toInt()
    private var highlightColor: Int = 0x33E8A838

    fun submit(list: List<Paragraph>) {
        items = list
        notifyDataSetChanged()
    }

    fun applyStyle(style: ReadStyle) {
        this.style = style
        val (tc, hc) = themeColors(style.theme, style.customBgColor)
        textColor = tc
        highlightColor = hc
        notifyDataSetChanged()
    }

    fun setHighlight(index: Int) {
        val old = highlightIndex
        highlightIndex = index
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    fun clearHighlight() {
        setHighlight(-1)
    }

    fun currentHighlight(): Int = highlightIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemParagraphBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == highlightIndex && highlightIndex >= 0)
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemParagraphBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // 单击交给阅读页分区手势；段落菜单用长按
            binding.root.setOnClickListener(null)
            binding.root.isClickable = false
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
                true
            }
        }

        fun bind(item: Paragraph, highlighted: Boolean) {
            val tv = binding.tvParagraph
            tv.text = item.text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
            tv.setLineSpacing(0f, style.lineSpacingMult)
            tv.letterSpacing = style.letterSpacing
            tv.setTextColor(textColor)
            tv.typeface = if (item.isChapter) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            val padV = (style.paraSpacingDp / 2f)
                .coerceAtLeast(2f)
                .let {
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        it,
                        tv.resources.displayMetrics,
                    ).toInt()
                }
            binding.root.setPadding(
                binding.root.paddingLeft,
                padV,
                binding.root.paddingRight,
                padV,
            )

            if (highlighted) {
                tv.setBackgroundColor(highlightColor)
            } else {
                tv.background = null
            }
        }
    }

    companion object {
        /**
         * @return (正文色, TTS 高亮底)
         * [customBg] 仅 [ReadTheme.CUSTOM] 时有效
         */
        fun themeColors(theme: ReadTheme, customBg: Int = 0xFFFFFFFF.toInt()): Pair<Int, Int> {
            return when (theme) {
                ReadTheme.DEFAULT -> 0xFF2C2C2C.toInt() to 0x66FFE082.toInt()
                ReadTheme.WHITE -> 0xFF222222.toInt() to 0x66FFE082.toInt()
                ReadTheme.GREEN -> 0xFF1E3A24.toInt() to 0x66A8E0B0.toInt()
                ReadTheme.BLUE -> 0xFF1A3344.toInt() to 0x66B8DCF0.toInt()
                ReadTheme.PURPLE -> 0xFF2E2438.toInt() to 0x66D8C8E8.toInt()
                ReadTheme.SEPIA -> 0xFF3E3224.toInt() to 0x66E8D4A8.toInt()
                ReadTheme.NIGHT -> 0xFFC8C8C8.toInt() to 0x883A4A5A.toInt()
                ReadTheme.CUSTOM -> {
                    val text = textColorForBackground(customBg)
                    val hl = if (isLightColor(customBg)) 0x66FFE082.toInt() else 0x884A90C0.toInt()
                    text to hl
                }
            }
        }

        fun backgroundColor(theme: ReadTheme, customBg: Int = 0xFFFFFFFF.toInt()): Int {
            return when (theme) {
                ReadTheme.DEFAULT -> 0xFFF7F4ED.toInt()
                ReadTheme.WHITE -> 0xFFFFFFFF.toInt()
                ReadTheme.GREEN -> 0xFFC7EDCC.toInt()
                ReadTheme.BLUE -> 0xFFDCEEF8.toInt()
                ReadTheme.PURPLE -> 0xFFF0E8F5.toInt()
                ReadTheme.SEPIA -> 0xFFF4ECD8.toInt()
                ReadTheme.NIGHT -> 0xFF1A1A1A.toInt()
                ReadTheme.CUSTOM -> customBg or 0xFF000000.toInt()
            }
        }

        fun isLightColor(color: Int): Boolean {
            val r = android.graphics.Color.red(color)
            val g = android.graphics.Color.green(color)
            val b = android.graphics.Color.blue(color)
            // 感知亮度
            return (0.299 * r + 0.587 * g + 0.114 * b) >= 160
        }

        fun textColorForBackground(bg: Int): Int =
            if (isLightColor(bg)) 0xFF222222.toInt() else 0xFFE0E0E0.toInt()
    }
}
