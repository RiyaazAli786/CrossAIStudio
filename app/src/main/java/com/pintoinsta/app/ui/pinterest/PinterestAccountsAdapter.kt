package com.pintoinsta.app.ui.pinterest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.databinding.ItemPinterestAccountBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PinterestAccountsAdapter(
    private var accounts: List<PinterestAccount>,
    private var instagramAccounts: List<InstagramAccount>,
    private val onCheckStatus: (PinterestAccount) -> Unit,
    private val onWebLogin: (PinterestAccount) -> Unit,
    private val onRunNow: (PinterestAccount) -> Unit,
    private val onDelete: (PinterestAccount) -> Unit,
    private val onScheduleChanged: (PinterestAccount, Long?, Int, Boolean) -> Unit
) : RecyclerView.Adapter<PinterestAccountsAdapter.ViewHolder>() {

    private val intervals = listOf(1, 2, 4, 6, 12, 24)
    private val intervalLabels = listOf("Every 1 Hour", "Every 2 Hours", "Every 4 Hours", "Every 6 Hours", "Every 12 Hours", "Every 24 Hours (Daily)")

    fun updateData(newAccounts: List<PinterestAccount>, newInstaAccounts: List<InstagramAccount>) {
        accounts = newAccounts
        instagramAccounts = newInstaAccounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPinterestAccountBinding.inflate(
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

    inner class ViewHolder(private val binding: ItemPinterestAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(account: PinterestAccount) {
            val context = binding.root.context

            binding.tvUsername.text = account.username
            binding.tvDisplayName.text = account.displayName.ifEmpty { "Pinterest User" }

            // Avatar
            if (account.profilePicUrl.isNotEmpty()) {
                binding.ivAvatar.load(account.profilePicUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_pinterest)
                    error(R.drawable.ic_pinterest)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_pinterest)
            }

            // Status Badge
            updateStatusBadge(account.status)

            // Setup Instagram dropdown
            val instaItems = mutableListOf("Select Instagram Account")
            val instaIds = mutableListOf<Long?>()
            instaIds.add(null)

            for (insta in instagramAccounts) {
                instaItems.add("@${insta.username} (${insta.status})")
                instaIds.add(insta.id)
            }

            val spinnerAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                instaItems
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerInstagram.adapter = spinnerAdapter

            // Select linked Instagram
            val selectedIndex = instaIds.indexOf(account.linkedInstagramAccountId).coerceAtLeast(0)
            binding.spinnerInstagram.setSelection(selectedIndex, false)

            // Setup Interval dropdown
            val intervalAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                intervalLabels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerInterval.adapter = intervalAdapter
            val intervalIndex = intervals.indexOf(account.scheduleIntervalHours).coerceAtLeast(intervals.size - 1)
            binding.spinnerInterval.setSelection(intervalIndex, false)

            // Switch
            binding.switchAutoPost.setOnCheckedChangeListener(null)
            binding.switchAutoPost.isChecked = account.isAutoPostEnabled

            // Last run info
            val nextRunStr = if (account.nextRunTime > 0) {
                SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(account.nextRunTime))
            } else "Not scheduled"
            binding.tvLastRunInfo.text = "Last: ${account.lastRunStatus} • Next run: $nextRunStr"

            // Listeners for schedule changes
            val scheduleChangeListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val newInstaId = instaIds[binding.spinnerInstagram.selectedItemPosition]
                    val newInterval = intervals[binding.spinnerInterval.selectedItemPosition]
                    val isAuto = binding.switchAutoPost.isChecked
                    if (newInstaId != account.linkedInstagramAccountId ||
                        newInterval != account.scheduleIntervalHours ||
                        isAuto != account.isAutoPostEnabled
                    ) {
                        onScheduleChanged(account, newInstaId, newInterval, isAuto)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.spinnerInstagram.onItemSelectedListener = scheduleChangeListener
            binding.spinnerInterval.onItemSelectedListener = scheduleChangeListener

            binding.switchAutoPost.setOnCheckedChangeListener { _, isChecked ->
                val newInstaId = instaIds[binding.spinnerInstagram.selectedItemPosition]
                val newInterval = intervals[binding.spinnerInterval.selectedItemPosition]
                onScheduleChanged(account, newInstaId, newInterval, isChecked)
            }

            // Buttons
            binding.btnCheckStatus.setOnClickListener {
                updateStatusBadge("CHECKING")
                onCheckStatus(account)
            }

            binding.btnWebLogin.setOnClickListener {
                onWebLogin(account)
            }

            binding.btnRunNow.setOnClickListener {
                onRunNow(account)
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
