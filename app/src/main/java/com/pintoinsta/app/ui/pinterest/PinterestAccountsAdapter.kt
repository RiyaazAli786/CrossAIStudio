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
    private val onScheduleChanged: (PinterestAccount, Long?, Int, Boolean, String) -> Unit
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
                    android.widget.Toast.makeText(
                        context,
                        "No Instagram accounts added yet. Please add an Instagram account first.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val currentLinked = account.getLinkedInstagramIdList().toMutableSet()
                val items = instagramAccounts.map { "@${it.username} (${it.status})" }.toTypedArray()
                val checked = BooleanArray(instagramAccounts.size) { i ->
                    currentLinked.contains(instagramAccounts[i].id)
                }

                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("Select Instagram Accounts")
                    .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                        val id = instagramAccounts[which].id
                        if (isChecked) currentLinked.add(id) else currentLinked.remove(id)
                    }
                    .setPositiveButton("Save (${currentLinked.size} selected)") { _, _ ->
                        val selectedList = currentLinked.toList()
                        account.setLinkedInstagramIdList(selectedList)
                        updateLinkedAccountsText()
                        val newInterval = intervals[binding.spinnerInterval.selectedItemPosition]
                        val isAuto = binding.switchAutoPost.isChecked
                        onScheduleChanged(
                            account,
                            account.linkedInstagramAccountId,
                            newInterval,
                            isAuto,
                            account.linkedInstagramAccountIds
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            // Setup Interval dropdown
            val intervalAdapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                intervalLabels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerInterval.adapter = intervalAdapter
            val intervalIndex = intervals.indexOf(account.scheduleIntervalHours).coerceAtLeast(0)
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
            binding.spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val newInterval = intervals[pos]
                    val isAuto = binding.switchAutoPost.isChecked
                    if (newInterval != account.scheduleIntervalHours) {
                        onScheduleChanged(
                            account,
                            account.linkedInstagramAccountId,
                            newInterval,
                            isAuto,
                            account.linkedInstagramAccountIds
                        )
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.switchAutoPost.setOnCheckedChangeListener { _, isChecked ->
                val newInterval = intervals[binding.spinnerInterval.selectedItemPosition]
                onScheduleChanged(
                    account,
                    account.linkedInstagramAccountId,
                    newInterval,
                    isChecked,
                    account.linkedInstagramAccountIds
                )
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
