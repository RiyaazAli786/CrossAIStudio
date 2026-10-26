package com.pintoinsta.app.ui.pins

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.databinding.ItemPostLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PostLogsAdapter(
    private var logs: List<PostLog>
) : RecyclerView.Adapter<PostLogsAdapter.ViewHolder>() {

    fun updateData(newLogs: List<PostLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }

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
            binding.tvLogTime.text = "$timeStr • Pin ID: ${log.pinId}"

            if (log.status == "SUCCESS") {
                binding.ivLogIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivLogIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_green))
            } else {
                binding.ivLogIcon.setImageResource(R.drawable.ic_error)
                binding.ivLogIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_red))
            }
        }
    }
}
