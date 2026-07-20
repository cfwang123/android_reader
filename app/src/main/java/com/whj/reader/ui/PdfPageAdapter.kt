package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.databinding.ItemPdfPageBinding

/**
 * PDF 连续滚动列表：每页一个 [PdfPageSurface]，页底细黑线分隔（最后一页无分隔线）；
 * 左上角半透明页码角标。
 */
class PdfPageAdapter(
    private var pageCount: Int,
    private val onBindPage: (pageIndex: Int, surface: PdfPageSurface, targetWidth: Int) -> Unit,
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    fun setPageCount(count: Int) {
        pageCount = count.coerceAtLeast(0)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.pageDivider.visibility =
            if (position < pageCount - 1) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.tvPageBadge.text = "${position + 1}"
        holder.binding.tvPageBadge.pivotX = 0f
        holder.binding.tvPageBadge.pivotY = 0f
        val w = holder.itemView.width.takeIf { it > 0 }
            ?: holder.itemView.resources.displayMetrics.widthPixels
        onBindPage(position, holder.binding.ivPage, w)
    }

    override fun onViewRecycled(holder: VH) {
        // 由 Activity 在 bind 时 drain；此处仅清状态
        holder.binding.ivPage.clearContent()
        super.onViewRecycled(holder)
    }

    class VH(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root)
}
