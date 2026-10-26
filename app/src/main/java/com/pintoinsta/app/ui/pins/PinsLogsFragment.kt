package com.pintoinsta.app.ui.pins

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentPinsLogsBinding
import com.pintoinsta.app.ui.viewer.PostViewerActivity
import kotlinx.coroutines.launch

class PinsLogsFragment : Fragment() {

    private var _binding: FragmentPinsLogsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var pinsAdapter: SavedPinsAdapter
    private lateinit var logsAdapter: PostLogsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinsLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AppRepository.getInstance(requireContext())

        setupRecyclerViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupRecyclerViews() {
        pinsAdapter = SavedPinsAdapter(
            pins = emptyList(),
            onPlayVideoClick = { pin ->
                showVideoPlayerDialog(pin)
            },
            onDeletePinClick = { pin ->
                confirmDeletePin(pin)
            },
            onOpenReelClick = { pin ->
                val targetUrl = pin.postUrl?.takeIf { it.isNotBlank() } ?: "https://www.instagram.com/"
                openReel(targetUrl, pin.instagramAccountId)
            }
        )
        binding.rvSavedPins.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedPins.adapter = pinsAdapter

        logsAdapter = PostLogsAdapter(
            logs = emptyList(),
            onOpenPostClick = { url, accountId ->
                openReel(url, accountId)
            }
        )
        binding.rvPostLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPostLogs.adapter = logsAdapter
    }

    private fun openReel(url: String, accountId: Long?) {
        val intent = Intent(requireContext(), PostViewerActivity::class.java).apply {
            putExtra(PostViewerActivity.EXTRA_URL, url)
            if (accountId != null && accountId != -1L) {
                putExtra(PostViewerActivity.EXTRA_ACCOUNT_ID, accountId)
            }
        }
        startActivity(intent)
    }

    private fun confirmDeletePin(pin: SavedPin) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Saved Pin")
            .setMessage("Are you sure you want to delete Pin ${pin.pinId}?\nThis will remove it from saved pins and it will not be posted.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = repository.deleteSavedPin(pin.pinId)
                    if (deleted) {
                        Toast.makeText(requireContext(), "Pin ${pin.pinId} deleted", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete pin", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoPlayerDialog(pin: SavedPin) {
        val streamUrl = pin.getVideoStreamUrl() ?: pin.mediaUrl
        if (streamUrl.isBlank()) {
            Toast.makeText(requireContext(), "No playable video URL found for this pin", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_video_player)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvVideoTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseVideo)
        val videoView = dialog.findViewById<VideoView>(R.id.videoView)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.pbVideoLoading)

        val caption = if (pin.title.isNotBlank()) pin.title else pin.searchTerm
        tvTitle.text = caption

        btnClose.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            videoView.stopPlayback()
        }

        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        progressBar.visibility = View.VISIBLE
        videoView.setVideoURI(Uri.parse(streamUrl))

        videoView.setOnPreparedListener {
            progressBar.visibility = View.GONE
            videoView.start()
        }

        videoView.setOnErrorListener { _, what, extra ->
            progressBar.visibility = View.GONE
            Toast.makeText(requireContext(), "Playback error ($what, $extra)", Toast.LENGTH_SHORT).show()
            true
        }

        dialog.show()
    }

    private fun setupListeners() {
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTabPins) {
                    binding.rvSavedPins.visibility = View.VISIBLE
                    binding.rvPostLogs.visibility = View.GONE
                } else {
                    binding.rvSavedPins.visibility = View.GONE
                    binding.rvPostLogs.visibility = View.VISIBLE
                }
                loadData()
            }
        }

        binding.btnRefresh.setOnClickListener {
            loadData()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val pins = repository.getAllSavedPins()
            val logs = repository.getRecentLogs()

            pinsAdapter.updateData(pins)
            logsAdapter.updateData(logs)

            val isPinsTab = binding.toggleGroup.checkedButtonId == R.id.btnTabPins
            if (isPinsTab) {
                binding.tvEmptyState.text = "No pins saved yet.\nScheduled runs will fetch pins automatically."
                binding.tvEmptyState.visibility = if (pins.isEmpty()) View.VISIBLE else View.GONE
            } else {
                binding.tvEmptyState.text = "No activity logs yet.\nLogs will appear after posting runs."
                binding.tvEmptyState.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
