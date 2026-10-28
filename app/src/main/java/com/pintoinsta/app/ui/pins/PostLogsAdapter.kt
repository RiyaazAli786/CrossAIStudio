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
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.databinding.ItemPostLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PostLogsAdapter(
    private var logs: List<PostLog>,
    var onOpenPostClick: ((String, Long?) -> Unit)? = null
) : RecyclerView.Adapter<PostLogsAdapter.ViewHolder>() {

    fun updateData(newLogs: List<PostLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }

    fun getCurrentLogs(): List<PostLog> = logs

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPostLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    inner class ViewHolder(private val binding: ItemPostLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: PostLog) {
            val context = binding.root.context

            binding.tvLogMessage.text = log.message
            val timeStr = SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault()).format(Date(log.timestamp))
            val nonPinTags = setOf("SYSTEM", "SEARCH", "ACCOUNT", "BACKUP", "SCHEDULE", "STATUS", "KEYWORD", "THEME", "INFO")
            binding.tvLogTime.text = if (log.pinId.isNotBlank() && !nonPinTags.contains(log.pinId)) {
                "$timeStr • Pin: ${log.pinId}"
            } else if (log.pinId.isNotBlank() && log.pinId != "INFO" && log.pinId != "SYSTEM") {
                "$timeStr • ${log.pinId}"
            } else {
                timeStr
            }

            when (log.status) {
                "SUCCESS" -> {
                    binding.ivLogIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.ivLogIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_green))
                }
                "FAILED" -> {
                    binding.ivLogIcon.setImageResource(R.drawable.ic_error)
                    binding.ivLogIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_red))
                }
                else -> { // INFO, SEARCH, ACCOUNT, BACKUP
                    binding.ivLogIcon.setImageResource(R.drawable.ic_info)
                    binding.ivLogIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_blue))
                }
            }

            val effectiveUrl = log.getEffectivePostUrl()
            if (!effectiveUrl.isNullOrBlank()) {
                binding.tvPostUrl.text = effectiveUrl
                binding.tvPostUrl.visibility = View.VISIBLE
                binding.btnOpenPost.visibility = View.VISIBLE

                val openAction = {
                    onOpenPostClick?.invoke(effectiveUrl, log.instagramAccountId)
                }
                binding.btnOpenPost.setOnClickListener { openAction() }
                binding.tvPostUrl.setOnClickListener { openAction() }
            } else {
                binding.tvPostUrl.visibility = View.GONE
                binding.btnOpenPost.visibility = View.GONE
                binding.btnOpenPost.setOnClickListener(null)
                binding.tvPostUrl.setOnClickListener(null)
            }

            val copyAction = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val logText = if (!effectiveUrl.isNullOrBlank()) {
                    "[$timeStr] Pin ID: ${log.pinId} [${log.status}]\n${log.message}\n$effectiveUrl"
                } else {
                    "[$timeStr] Pin ID: ${log.pinId} [${log.status}]\n${log.message}"
                }
                val clip = ClipData.newPlainText("CrossAIStudio Log", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            }

            binding.btnCopyLog.setOnClickListener {
                copyAction()
            }

            binding.root.setOnClickListener {
                copyAction()
            }
        }
    }
}
