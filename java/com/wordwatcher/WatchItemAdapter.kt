package com.wordwatcher

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.wordwatcher.data.WatchItem

class WatchItemAdapter(
    private val items: List<WatchItem>,
    private val onDelete: (WatchItem) -> Unit
) : RecyclerView.Adapter<WatchItemAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUrl:     TextView = view.findViewById(R.id.tvUrl)
        val tvKeyword: TextView = view.findViewById(R.id.tvKeyword)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watch, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvUrl.text     = item.url
        holder.tvKeyword.text = item.keyword
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}
