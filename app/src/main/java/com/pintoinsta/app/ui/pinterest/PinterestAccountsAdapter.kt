package com.pintoinsta.app.ui.pinterest

import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.chip.Chip
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
    private val onScheduleChanged: (PinterestAccount, Long?, Int, Boolean, String, String) -> Unit
) : RecyclerView.Adapter<PinterestAccountsAdapter.ViewHolder>() {

    // Track which accounts are expanded. By default, all are collapsed.
    private val expandedAccountIds = mutableSetOf<Long>()

    private val defaultSlotTimes = listOf(
        "09:00", "14:00", "20:00", "11:00", "17:00", "22:00", "07:30", "12:30", "16:00", "19:00"
    )

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

            // Setup Expander State (Default collapsed)
            val isExpanded = expandedAccountIds.contains(account.id)
            binding.layoutSettingsExpander.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.btnExpandToggle.setImageResource(
                if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )

            // Compact Header summary text
            fun updateHeaderSummary() {
                val times = account.getDailyPostTimeList()
                val countText = "${times.size} post${if (times.size == 1) "" else "s"}/day"
                val autoText = if (account.isAutoPostEnabled) "Auto ON" else "Auto OFF"
                val nextText = if (account.nextRunTime > 0) {
                    "Next: " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(account.nextRunTime))
                } else "Idle"
                binding.tvCompactSummary.text = "$countText • $autoText • $nextText"
            }
            updateHeaderSummary()

            // Header Click & Toggle
            val toggleExpand = View.OnClickListener {
                if (expandedAccountIds.contains(account.id)) {
                    expandedAccountIds.remove(account.id)
                    binding.layoutSettingsExpander.visibility = View.GONE
                    binding.btnExpandToggle.setImageResource(R.drawable.ic_expand_more)
                } else {
                    expandedAccountIds.add(account.id)
                    binding.layoutSettingsExpander.visibility = View.VISIBLE
                    binding.btnExpandToggle.setImageResource(R.drawable.ic_expand_less)
                }
            }
            binding.layoutCardHeader.setOnClickListener(toggleExpand)
            binding.btnExpandToggle.setOnClickListener(toggleExpand)

            // Setup Multi-Select Instagram text
            fun updateLinkedAccountsText() {
                val linkedIds = account.getLinkedInstagramIdList()
                val selectedNames = instagramAccounts.filter { linkedIds.contains(it.id) }.map { "@${it.username}" }
                binding.tvLinkedInstagramAccounts.text = when {
                    selectedNames.isEmpty() -> "Select Instagram Accounts"
                    selectedNames.size == 1 -> selectedNames.first()
                    else -> "${selectedNames.size} Accounts: ${selectedNames.joinToString(", ")}"
                }
            }
            updateLinkedAccountsText()

            binding.layoutSelectInstagramAccounts.setOnClickListener {
                if (instagramAccounts.isEmpty()) {
                    Toast.makeText(
                        context,
                        "No Instagram accounts added yet. Please add an Instagram account first.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val currentLinked = account.getLinkedInstagramIdList().toMutableSet()
                val items = instagramAccounts.map { "@${it.username} (${it.status})" }.toTypedArray()
                val checked = BooleanArray(instagramAccounts.size) { i ->
                    currentLinked.contains(instagramAccounts[i].id)
                }

                AlertDialog.Builder(context)
                    .setTitle("Select Instagram Accounts")
                    .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                        val id = instagramAccounts[which].id
                        if (isChecked) currentLinked.add(id) else currentLinked.remove(id)
                    }
                    .setPositiveButton("Save (${currentLinked.size} selected)") { _, _ ->
                        val selectedList = currentLinked.toList()
                        account.setLinkedInstagramIdList(selectedList)
                        updateLinkedAccountsText()
                        onScheduleChanged(
                            account,
                            account.linkedInstagramAccountId,
                            account.scheduleIntervalHours,
                            binding.switchAutoPost.isChecked,
                            account.linkedInstagramAccountIds,
                            account.dailyPostTimes
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            // Time chips rendering
            fun renderTimeChips() {
                val times = account.getDailyPostTimeList()
                binding.tvPostCount.text = "${times.size} ${if (times.size == 1) "Post" else "Posts"}"
                binding.chipGroupTimeSlots.removeAllViews()

                for (i in times.indices) {
                    val timeStr = times[i]
                    val chip = Chip(context).apply {
                        text = "Post #${i + 1}: ${formatDisplayTime(timeStr)}"
                        isClickable = true
                        isCheckable = false
                        setChipIconResource(R.drawable.ic_schedule)
                        chipIconTint = ContextCompat.getColorStateList(context, R.color.primary)
                        setChipBackgroundColorResource(R.color.surface_card)
                        setChipStrokeColorResource(R.color.primary)
                        chipStrokeWidth = 2f
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        textSize = 12f
                        setOnClickListener {
                            val parts = timeStr.split(":")
                            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
                            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

                            TimePickerDialog(
                                context,
                                { _, selectedHour, selectedMinute ->
                                    val formattedTime = String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute)
                                    val currentTimes = account.getDailyPostTimeList().toMutableList()
                                    if (i < currentTimes.size) {
                                        currentTimes[i] = formattedTime
                                        currentTimes.sort()
                                        account.setDailyPostTimeList(currentTimes)
                                        renderTimeChips()
                                        updateHeaderSummary()
                                        onScheduleChanged(
                                            account,
                                            account.linkedInstagramAccountId,
                                            account.scheduleIntervalHours,
                                            binding.switchAutoPost.isChecked,
                                            account.linkedInstagramAccountIds,
                                            account.dailyPostTimes
                                        )
                                    }
                                },
                                hour,
                                minute,
                                false
                            ).show()
                        }
                    }
                    binding.chipGroupTimeSlots.addView(chip)
                }
            }
            renderTimeChips()

            // Post Count Stepper [-] / [+]
            binding.btnDecPostCount.setOnClickListener {
                val currentTimes = account.getDailyPostTimeList().toMutableList()
                if (currentTimes.size > 1) {
                    currentTimes.removeAt(currentTimes.size - 1)
                    account.setDailyPostTimeList(currentTimes)
                    renderTimeChips()
                    updateHeaderSummary()
                    onScheduleChanged(
                        account,
                        account.linkedInstagramAccountId,
                        account.scheduleIntervalHours,
                        binding.switchAutoPost.isChecked,
                        account.linkedInstagramAccountIds,
                        account.dailyPostTimes
                    )
                } else {
                    Toast.makeText(context, "Minimum 1 post per day", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnIncPostCount.setOnClickListener {
                val currentTimes = account.getDailyPostTimeList().toMutableList()
                if (currentTimes.size < 10) {
                    val candidate = defaultSlotTimes.firstOrNull { !currentTimes.contains(it) }
                        ?: String.format(Locale.US, "%02d:00", (currentTimes.size * 2 + 8) % 24)
                    currentTimes.add(candidate)
                    currentTimes.sort()
                    account.setDailyPostTimeList(currentTimes)
                    renderTimeChips()
                    updateHeaderSummary()
                    onScheduleChanged(
                        account,
                        account.linkedInstagramAccountId,
                        account.scheduleIntervalHours,
                        binding.switchAutoPost.isChecked,
                        account.linkedInstagramAccountIds,
                        account.dailyPostTimes
                    )
                } else {
                    Toast.makeText(context, "Maximum 10 posts per day", Toast.LENGTH_SHORT).show()
                }
            }

            // Auto-post switch
            binding.switchAutoPost.setOnCheckedChangeListener(null)
            binding.switchAutoPost.isChecked = account.isAutoPostEnabled
            binding.switchAutoPost.setOnCheckedChangeListener { _, isChecked ->
                account.isAutoPostEnabled = isChecked
                updateHeaderSummary()
                onScheduleChanged(
                    account,
                    account.linkedInstagramAccountId,
                    account.scheduleIntervalHours,
                    isChecked,
                    account.linkedInstagramAccountIds,
                    account.dailyPostTimes
                )
            }

            // Last run info
            val nextRunStr = if (account.nextRunTime > 0) {
                SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(account.nextRunTime))
            } else "Not scheduled"
            binding.tvLastRunInfo.text = "Last: ${account.lastRunStatus} • Next run: $nextRunStr"

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

        private fun formatDisplayTime(timeStr: String): String {
            return try {
                val parser = SimpleDateFormat("HH:mm", Locale.US)
                val formatter = SimpleDateFormat("hh:mm a", Locale.US)
                val date = parser.parse(timeStr)
                if (date != null) formatter.format(date) else timeStr
            } catch (_: Exception) {
                timeStr
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
