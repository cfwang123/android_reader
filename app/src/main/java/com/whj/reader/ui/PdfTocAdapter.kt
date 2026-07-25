package com.whj.reader.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.data.PdfOutlineLoader

/**
 * PDF 树形目录：
 * - 点箭头：仅展开/折叠，不跳转、不关目录
 * - 点标题区域：跳转页码
 * - 当前章节高亮 +「当前」角标
 */
class PdfTocAdapter(
    private val roots: List<PdfOutlineLoader.Node>,
    private var expanded: MutableSet<PdfOutlineLoader.Node>,
    private val currentPage: Int,
    private val onOpenPage: (pageIndex: Int) -> Unit,
) : RecyclerView.Adapter<PdfTocAdapter.VH>() {

    data class Row(
        val node: PdfOutlineLoader.Node,
        val depth: Int,
        val expanded: Boolean,
        val isCurrent: Boolean,
        val onPath: Boolean,
    )

    private val currentPath: List<PdfOutlineLoader.Node> =
        PdfOutlineLoader.pathToCurrent(roots, currentPage)
    private val currentPathSet: Set<PdfOutlineLoader.Node> = currentPath.toSet()
    private val currentLeaf: PdfOutlineLoader.Node? = currentPath.lastOrNull()

    private var rows: List<Row> = flatten()

    fun toggle(node: PdfOutlineLoader.Node) {
        if (node.children.isEmpty()) return
        if (node in expanded) expanded.remove(node) else expanded.add(node)
        rows = flatten()
        notifyDataSetChanged()
    }

    private fun flatten(): List<Row> {
        val out = ArrayList<Row>()
        fun walk(nodes: List<PdfOutlineLoader.Node>, depth: Int) {
            for (n in nodes) {
                val isExp = n in expanded && n.children.isNotEmpty()
                val isCurrent = n == currentLeaf
                val onPath = n in currentPathSet
                out.add(Row(n, depth, isExp, isCurrent, onPath))
                if (isExp) walk(n.children, depth + 1)
            }
        }
        walk(roots, 0)
        return out
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_toc, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: LinearLayout = itemView.findViewById(R.id.rowPdfToc)
        private val tvExpand: TextView = itemView.findViewById(R.id.tvExpand)
        private val content: View = itemView.findViewById(R.id.contentPdfToc)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvPage: TextView = itemView.findViewById(R.id.tvPage)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvCurrentBadge)

        fun bind(r: Row) {
            val n = r.node
            val density = itemView.resources.displayMetrics.density
            val padL = ((4 + r.depth * 16) * density).toInt()
            row.setPadding(padL, row.paddingTop, row.paddingRight, row.paddingBottom)

            // —— 箭头：只展开/折叠 ——
            if (n.children.isNotEmpty()) {
                tvExpand.text = if (r.expanded) "▼" else "▶"
                tvExpand.isClickable = true
                tvExpand.isFocusable = true
                tvExpand.contentDescription = itemView.context.getString(
                    if (r.expanded) R.string.pdf_toc_collapse else R.string.pdf_toc_expand,
                )
                tvExpand.setOnClickListener { e ->
                    e.isPressed = true
                    toggle(n)
                }
            } else {
                tvExpand.text = "·"
                tvExpand.isClickable = false
                tvExpand.isFocusable = false
                tvExpand.setOnClickListener(null)
                tvExpand.contentDescription = null
            }

            tvTitle.text = n.title
            tvTitle.setTypeface(null, if (r.isCurrent) Typeface.BOLD else Typeface.NORMAL)
            tvPage.text = if (n.pageIndex >= 0) {
                itemView.context.getString(R.string.pdf_toc_page, n.pageIndex + 1)
            } else {
                ""
            }
            tvBadge.visibility = if (r.isCurrent) View.VISIBLE else View.GONE

            // 当前节：橙字 + 浅底；路径上其它节点略强调
            when {
                r.isCurrent -> {
                    tvTitle.setTextColor(0xFFE67E00.toInt())
                    content.setBackgroundColor(0x22F0A020)
                }
                r.onPath -> {
                    tvTitle.setTextColor(0xFFD4890A.toInt())
                    content.setBackgroundColor(0x00000000)
                }
                else -> {
                    tvTitle.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.text_primary),
                    )
                    content.setBackgroundResource(0)
                    // 恢复 ripple：用 selectable 需设 background
                    content.setBackgroundResource(
                        android.R.attr.selectableItemBackground.let { attr ->
                            val a = itemView.context.obtainStyledAttributes(intArrayOf(attr))
                            val res = a.getResourceId(0, 0)
                            a.recycle()
                            res
                        },
                    )
                }
            }

            // —— 标题区域：跳转（不绑在整行，避免与箭头冲突）——
            content.setOnClickListener {
                if (n.pageIndex >= 0) {
                    onOpenPage(n.pageIndex)
                }
            }
            // 整行不设 click，防止误触
            itemView.setOnClickListener(null)
            itemView.isClickable = false
        }
    }
}
