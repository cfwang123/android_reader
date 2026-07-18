package com.whj.reader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.R
import com.whj.reader.databinding.ItemRecentBinding
import com.whj.reader.model.RecentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentAdapter(
    private val onClick: (RecentFile) -> Unit,
    private val onLongClick: (RecentFile) -> Unit,
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    private var items: List<RecentFile> = emptyList()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<RecentFile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemRecentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(items[pos])
                true
            }
        }

        fun bind(item: RecentFile) {
            binding.tvTitle.text = item.displayName
            binding.tvPath.text = item.pathHint.ifBlank { item.uri }
            binding.tvMeta.text = buildString {
                append(
                    itemView.context.getString(R.string.para_index, item.lastParagraph + 1),
                )
                if (item.lastOpened > 0) {
                    append(" · ")
                    append(fmt.format(Date(item.lastOpened)))
                }
            }
        }
    }
}
