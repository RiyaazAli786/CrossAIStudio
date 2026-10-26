package com.pintoinsta.app.ui.pins

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.databinding.ItemSavedPinBinding

class SavedPinsAdapter(
    private var pins: List<SavedPin>,
    var onPlayVideoClick: ((SavedPin) -> Unit)? = null,
    var onDeletePinClick: ((SavedPin) -> Unit)? = null,
    var onOpenReelClick: ((SavedPin) -> Unit)? = null
) : RecyclerView.Adapter<SavedPinsAdapter.ViewHolder>() {

    fun updateData(newPins: List<SavedPin>) {
        pins = newPins
        notifyDataSetChanged()
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

            binding.tvPinId.text = "Pin ID: ${pin.pinId}"
            val caption = if (pin.title.isNotBlank()) pin.title else pin.description.ifEmpty { pin.searchTerm }
            binding.tvPinCaption.text = caption
            binding.tvSearchKeyword.text = "Keyword: ${pin.searchTerm} • ${pin.mediaType}"

            if (pin.isPosted) {
                binding.tvPinStatus.text = "Posted"
                binding.tvPinStatus.setBackgroundResource(R.drawable.bg_badge_active)
                binding.tvPinStatus.setTextColor(ContextCompat.getColor(context, R.color.status_green))
            } else {
                binding.tvPinStatus.text = "Pending"
                binding.tvPinStatus.setBackgroundResource(R.drawable.bg_badge_checking)
                binding.tvPinStatus.setTextColor(ContextCompat.getColor(context, R.color.status_orange))
            }

            // Use getEffectiveImageUrl() to display high-res preview thumbnail even for video pins
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
                    onPlayVideoClick?.invoke(pin)
                }
            } else {
                binding.layoutPinThumb.setOnClickListener(null)
            }

            if (pin.isPosted && !pin.postUrl.isNullOrBlank()) {
                binding.btnOpenReel.visibility = View.VISIBLE
                binding.btnOpenReel.setOnClickListener {
                    onOpenReelClick?.invoke(pin)
                }
            } else {
                binding.btnOpenReel.visibility = View.GONE
                binding.btnOpenReel.setOnClickListener(null)
            }

            binding.btnDeletePin.setOnClickListener {
                onDeletePinClick?.invoke(pin)
            }
        }
    }
}
