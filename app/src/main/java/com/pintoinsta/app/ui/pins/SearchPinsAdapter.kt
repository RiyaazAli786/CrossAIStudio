package com.pintoinsta.app.ui.pins

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.databinding.ItemSearchFooterBinding
import com.pintoinsta.app.databinding.ItemSearchPinBinding

class SearchPinsAdapter(
    var onSaveSinglePinClick: ((SavedPin, Int) -> Unit)? = null,
    var onLoadMoreClick: (() -> Unit)? = null,
    var onSelectionChanged: ((selectedCount: Int, totalCount: Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PIN = 0
        private const val TYPE_FOOTER = 1
    }

    private val pins = mutableListOf<SavedPin>()
    private val savedPinIds = mutableSetOf<String>()
    private val selectedPinIds = mutableSetOf<String>()

    var hasMore: Boolean = false
        private set
    var isLoadingMore: Boolean = false
        private set
    var hasSearched: Boolean = false
        private set

    fun setData(newPins: List<SavedPin>, hasMoreResults: Boolean, initialSavedIds: Set<String>) {
        hasSearched = true
        pins.clear()
        pins.addAll(newPins)
        savedPinIds.clear()
        savedPinIds.addAll(initialSavedIds)
        selectedPinIds.clear()
        hasMore = hasMoreResults
        isLoadingMore = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, pins.size)
    }

    fun appendData(morePins: List<SavedPin>, hasMoreResults: Boolean) {
        val startPos = pins.size
        pins.addAll(morePins)
        hasMore = hasMoreResults
        isLoadingMore = false
        notifyItemRangeInserted(startPos, morePins.size)
        // Also update footer
        notifyItemChanged(pins.size)
        onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
    }

    fun setLoadingMore(loading: Boolean) {
        isLoadingMore = loading
        if (pins.isNotEmpty()) {
            notifyItemChanged(pins.size)
        }
    }

    fun markPinSaved(pinId: String) {
        savedPinIds.add(pinId)
        val index = pins.indexOfFirst { it.pinId == pinId }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun markPinsSaved(pinIds: Collection<String>) {
        savedPinIds.addAll(pinIds)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedPinIds.clear()
        selectedPinIds.addAll(pins.map { it.pinId })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
    }

    fun deselectAll() {
        selectedPinIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, pins.size)
    }

    fun getSelectedPins(): List<SavedPin> {
        return pins.filter { selectedPinIds.contains(it.pinId) }
    }

    fun getSelectedCount(): Int = selectedPinIds.size

    fun getTotalCount(): Int = pins.size

    fun clearAll() {
        pins.clear()
        selectedPinIds.clear()
        hasMore = false
        isLoadingMore = false
        hasSearched = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, 0)
    }

    override fun getItemCount(): Int {
        return if (hasSearched && pins.isNotEmpty()) pins.size + 1 else pins.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < pins.size) TYPE_PIN else TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_PIN) {
            val binding = ItemSearchPinBinding.inflate(inflater, parent, false)
            PinViewHolder(binding)
        } else {
            val binding = ItemSearchFooterBinding.inflate(inflater, parent, false)
            FooterViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PinViewHolder) {
            holder.bind(pins[position], position)
        } else if (holder is FooterViewHolder) {
            holder.bind()
        }
    }

    inner class PinViewHolder(private val binding: ItemSearchPinBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pin: SavedPin, position: Int) {
            val isSelected = selectedPinIds.contains(pin.pinId)
            val isSaved = savedPinIds.contains(pin.pinId)

            binding.cbSelectPin.isChecked = isSelected

            // Pin Title & Description
            binding.tvPinTitle.text = pin.title.ifBlank { pin.searchTerm.ifBlank { "Pinterest Pin" } }
            if (pin.description.isNotBlank()) {
                binding.tvPinDescription.visibility = View.VISIBLE
                binding.tvPinDescription.text = pin.description
            } else {
                binding.tvPinDescription.visibility = View.GONE
            }

            // Pin ID Badge
            binding.tvPinIdBadge.text = "#${pin.pinId.takeLast(8)}"

            // Video indicator
            binding.layoutVideoBadge.visibility = if (pin.mediaType == "VIDEO") View.VISIBLE else View.GONE

            // Thumbnail Image
            val imageUrl = pin.getEffectiveImageUrl()
            binding.ivPinThumb.load(imageUrl) {
                crossfade(true)
                error(R.drawable.bg_search_chip)
                placeholder(R.drawable.bg_search_chip)
            }

            // Saved vs Save Button state
            if (isSaved) {
                binding.tvSavedBadge.visibility = View.VISIBLE
                binding.btnSaveSinglePin.visibility = View.GONE
            } else {
                binding.tvSavedBadge.visibility = View.GONE
                binding.btnSaveSinglePin.visibility = View.VISIBLE
                binding.btnSaveSinglePin.setOnClickListener {
                    onSaveSinglePinClick?.invoke(pin, bindingAdapterPosition)
                }
            }

            // Tapping card or checkbox toggles selection check
            val toggleSelection = View.OnClickListener {
                if (selectedPinIds.contains(pin.pinId)) {
                    selectedPinIds.remove(pin.pinId)
                } else {
                    selectedPinIds.add(pin.pinId)
                }
                binding.cbSelectPin.isChecked = selectedPinIds.contains(pin.pinId)
                onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
            }

            binding.root.setOnClickListener(toggleSelection)
            binding.cbSelectPin.setOnClickListener(toggleSelection)
        }
    }

    inner class FooterViewHolder(private val binding: ItemSearchFooterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            if (isLoadingMore) {
                binding.pbPagination.visibility = View.VISIBLE
                binding.btnLoadMore.visibility = View.GONE
                binding.tvEndOfResults.visibility = View.GONE
            } else if (hasMore) {
                binding.pbPagination.visibility = View.GONE
                binding.btnLoadMore.visibility = View.VISIBLE
                binding.tvEndOfResults.visibility = View.GONE
                binding.btnLoadMore.setOnClickListener {
                    onLoadMoreClick?.invoke()
                }
            } else {
                binding.pbPagination.visibility = View.GONE
                binding.btnLoadMore.visibility = View.GONE
                binding.tvEndOfResults.visibility = View.VISIBLE
            }
        }
    }
}
