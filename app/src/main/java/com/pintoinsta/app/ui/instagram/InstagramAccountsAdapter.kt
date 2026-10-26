package com.pintoinsta.app.ui.instagram

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.databinding.ItemInstagramAccountBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstagramAccountsAdapter(
    private var accounts: List<InstagramAccount>,
    private val onCheckStatus: (InstagramAccount) -> Unit,
    private val onWebLogin: (InstagramAccount) -> Unit,
    private val onDelete: (InstagramAccount) -> Unit
) : RecyclerView.Adapter<InstagramAccountsAdapter.ViewHolder>() {

    fun updateData(newAccounts: List<InstagramAccount>) {
        accounts = newAccounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemInstagramAccountBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(accounts[position])
    }

    override fun getItemCount(): Int = accounts.size

    inner class ViewHolder(private val binding: ItemInstagramAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(account: InstagramAccount) {
            val context = binding.root.context

            binding.tvUsername.text = "@${account.username}"
            binding.tvDisplayName.text = account.displayName.ifEmpty { "Instagram User" }

            if (account.profilePicUrl.isNotEmpty()) {
                binding.ivAvatar.load(account.profilePicUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_instagram)
                    error(R.drawable.ic_instagram)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_instagram)
            }

            updateStatusBadge(account.status)

            val lastCheckStr = if (account.lastCheckedTime > 0) {
                SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(account.lastCheckedTime))
            } else "Not checked yet"
            binding.tvLastChecked.text = "Last checked: $lastCheckStr"

            binding.btnCheckStatus.setOnClickListener {
                updateStatusBadge("CHECKING")
                onCheckStatus(account)
            }

            binding.btnWebLogin.setOnClickListener {
                onWebLogin(account)
            }

            binding.btnDeleteAccount.setOnClickListener {
                onDelete(account)
            }
        }

        private fun updateStatusBadge(status: String) {
            val context = binding.root.context
            when (status) {
                "ACTIVE" -> {
                    binding.tvStatusBadge.text = "Active"
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
                }
                "EXPIRED" -> {
                    binding.tvStatusBadge.text = "Expired"
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_expired)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_red))
                }
                "CHECKING" -> {
                    binding.tvStatusBadge.text = "Checking..."
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_checking)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_orange))
                }
                else -> {
                    binding.tvStatusBadge.text = status
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_checking)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_orange))
                }
            }
        }
    }
}
