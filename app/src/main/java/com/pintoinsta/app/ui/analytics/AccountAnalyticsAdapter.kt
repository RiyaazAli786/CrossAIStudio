package com.pintoinsta.app.ui.analytics

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.AccountAnalytics
import com.pintoinsta.app.databinding.ItemAccountAnalyticsRowBinding
import java.util.Locale

class AccountAnalyticsAdapter(
    private var items: List<AccountAnalytics> = emptyList()
) : RecyclerView.Adapter<AccountAnalyticsAdapter.ViewHolder>() {

    fun updateData(newItems: List<AccountAnalytics>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccountAnalyticsRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAccountAnalyticsRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AccountAnalytics, position: Int) {
            val context = binding.root.context

            // Alternating subtle row background for tabular clarity
            if (position % 2 == 1) {
                binding.root.setBackgroundColor(Color.parseColor("#08888888"))
            } else {
                binding.root.setBackgroundColor(Color.TRANSPARENT)
            }

            // Platform icon and badge
            if (item.platform.equals("Pinterest", ignoreCase = true)) {
                binding.ivPlatformSmall.setImageResource(R.drawable.ic_pinterest)
                binding.tvPlatformBadge.text = "Pinterest"
            } else {
                binding.ivPlatformSmall.setImageResource(R.drawable.ic_instagram)
                binding.tvPlatformBadge.text = "Instagram"
            }

            binding.tvUsername.text = "@${item.username}"
            binding.tvPublishedCount.text = "${item.totalPublished}"
            binding.tvFailedCount.text = "${item.totalFailed}"
            binding.tvSuccessRate.text = String.format(Locale.getDefault(), "%.1f%%", item.successRate)

            val isActive = item.status.equals("ACTIVE", ignoreCase = true)
            if (isActive) {
                binding.tvStatusBadge.text = "ACTIVE"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
            } else {
                binding.tvStatusBadge.text = item.status.ifBlank { "INACTIVE" }
                binding.tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_expired)
            }
        }
    }
}
