package com.pintoinsta.app.ui.pins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.databinding.ItemSavedPinBinding
import com.pintoinsta.app.util.InstagramCaptionUtils

class SavedPinsAdapter(
    private var pins: List<SavedPin>,
    var onPlayVideoClick: ((SavedPin) -> Unit)? = null,
    var onDeletePinClick: ((SavedPin) -> Unit)? = null,
    var onOpenReelClick: ((SavedPin) -> Unit)? = null,
    var onCopyReelUrlClick: ((SavedPin, String) -> Unit)? = null,
    var onPublishPinClick: ((SavedPin, com.google.android.material.button.MaterialButton) -> Unit)? = null,
    var onItemLongClick: ((SavedPin) -> Unit)? = null,
    var onSelectionChanged: ((selectedCount: Int, totalCount: Int) -> Unit)? = null
) : RecyclerView.Adapter<SavedPinsAdapter.ViewHolder>() {

    var isSelectionMode: Boolean = false
        private set

    private val selectedPinIds = mutableSetOf<String>()

    fun updateData(newPins: List<SavedPin>) {
        pins = newPins
        // Remove any selected IDs that are no longer in pins
        val currentIds = pins.map { it.pinId }.toSet()
        selectedPinIds.retainAll(currentIds)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
    }

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedPinIds.clear()
            }
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
        }
    }

    fun selectAll() {
        selectedPinIds.clear()
        selectedPinIds.addAll(pins.map { it.pinId })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
    }

    fun clearSelection() {
        selectedPinIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0, pins.size)
    }

    fun getSelectedPinIds(): List<String> = selectedPinIds.toList()

    fun getSelectedCount(): Int = selectedPinIds.size

    fun toggleSelection(pinId: String) {
        if (selectedPinIds.contains(pinId)) {
            selectedPinIds.remove(pinId)
        } else {
            selectedPinIds.add(pinId)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPinIds.size, pins.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedPinBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(pins[position])
    }

    override fun getItemCount(): Int = pins.size

    inner class ViewHolder(private val binding: ItemSavedPinBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pin: SavedPin) {
            val context = binding.root.context
            val isSelected = selectedPinIds.contains(pin.pinId)

            // Selection mode checkbox and card visual feedback
            if (isSelectionMode) {
                binding.cbSelectPin.visibility = View.VISIBLE
                binding.cbSelectPin.isChecked = isSelected
                binding.root.strokeColor = ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.primary else R.color.divider
                )
                binding.root.strokeWidth = if (isSelected) 4 else 2
            } else {
                binding.cbSelectPin.visibility = View.GONE
                binding.cbSelectPin.isChecked = false
                binding.root.strokeColor = ContextCompat.getColor(context, R.color.divider)
                binding.root.strokeWidth = 2
            }

            // Click handling
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(pin.pinId)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleSelection(pin.pinId)
                    onItemLongClick?.invoke(pin)
                    true
                } else {
                    false
                }
            }

            binding.tvPinId.text = "Pin ID: ${pin.pinId}"
            val rawCaption = if (pin.title.isNotBlank()) pin.title else pin.description.ifEmpty { pin.searchTerm }
            val caption = InstagramCaptionUtils.cleanAndFormatCaption(rawCaption)
            binding.tvPinCaption.text = caption
            binding.tvSearchKeyword.text = "Keyword: ${pin.searchTerm} • ${pin.mediaType}"

            if (pin.isPosted) {
                val publishedLabel = if (!pin.publishedTo.isNullOrBlank()) {
                    "Published on ${pin.publishedTo}"
                } else {
                    "Published"
                }
                binding.tvPinStatus.text = publishedLabel
                binding.tvPinStatus.setBackgroundResource(R.drawable.bg_badge_active)
                binding.tvPinStatus.setTextColor(ContextCompat.getColor(context, R.color.status_green))
                binding.btnPublishPin.visibility = View.GONE
            } else {
                binding.tvPinStatus.text = "Pending"
                binding.tvPinStatus.setBackgroundResource(R.drawable.bg_badge_checking)
                binding.tvPinStatus.setTextColor(ContextCompat.getColor(context, R.color.status_orange))
                binding.btnPublishPin.visibility = View.VISIBLE
                binding.btnPublishPin.isEnabled = true
                binding.btnPublishPin.text = "Publish Now"
                binding.btnPublishPin.setOnClickListener {
                    onPublishPinClick?.invoke(pin, binding.btnPublishPin)
                }
            }

            // Thumbnail
            val thumbUrl = pin.getEffectiveImageUrl()
            if (thumbUrl.isNotEmpty()) {
                binding.ivPinThumb.load(thumbUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_pins)
                    error(R.drawable.ic_pins)
                }
            } else {
                binding.ivPinThumb.setImageResource(R.drawable.ic_pins)
            }

            val hasVideo = pin.mediaType == "VIDEO" || pin.getVideoStreamUrl() != null
            binding.ivPlayOverlay.visibility = if (hasVideo) View.VISIBLE else View.GONE

            if (hasVideo) {
                binding.layoutPinThumb.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(pin.pinId)
                    } else {
                        onPlayVideoClick?.invoke(pin)
                    }
                }
            } else {
                binding.layoutPinThumb.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(pin.pinId)
                    }
                }
            }

            val effectiveReelUrl = pin.getEffectivePostUrl()
            if (pin.isPosted && !effectiveReelUrl.isNullOrBlank()) {
                binding.layoutReelUrl.visibility = View.VISIBLE
                binding.tvReelUrl.text = effectiveReelUrl

                binding.btnCopyReelUrl.setOnClickListener {
                    onCopyReelUrlClick?.invoke(pin, effectiveReelUrl) ?: run {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Reel URL", effectiveReelUrl)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Reel URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }

                val navigateAction = {
                    onOpenReelClick?.invoke(pin)
                }
                binding.btnNavigateReelUrl.setOnClickListener { navigateAction() }
                binding.tvReelUrl.setOnClickListener { navigateAction() }

                binding.btnOpenReel.visibility = View.VISIBLE
                binding.btnOpenReel.setOnClickListener { navigateAction() }
            } else {
                binding.layoutReelUrl.visibility = View.GONE
                binding.btnOpenReel.visibility = View.GONE
                binding.btnOpenReel.setOnClickListener(null)
            }

            binding.btnDeletePin.setOnClickListener {
                onDeletePinClick?.invoke(pin)
            }
        }
    }
}
