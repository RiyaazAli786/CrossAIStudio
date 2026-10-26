package com.pintoinsta.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pintoinsta.app.data.model.SearchKeyword
import com.pintoinsta.app.databinding.ItemKeywordBinding

class KeywordsAdapter(
    private var keywords: List<SearchKeyword>,
    private val onDelete: (SearchKeyword) -> Unit
) : RecyclerView.Adapter<KeywordsAdapter.ViewHolder>() {

    fun updateData(newKeywords: List<SearchKeyword>) {
        keywords = newKeywords
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKeywordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(keywords[position])
    }

    override fun getItemCount(): Int = keywords.size

    inner class ViewHolder(private val binding: ItemKeywordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(keyword: SearchKeyword) {
            binding.tvKeywordText.text = keyword.keyword
            binding.btnDeleteKeyword.setOnClickListener {
                onDelete(keyword)
            }
        }
    }
}
