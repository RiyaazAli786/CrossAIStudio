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
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentPinsLogsBinding
import com.pintoinsta.app.ui.viewer.PostViewerActivity
import com.pintoinsta.app.util.InstagramCaptionUtils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
            },
            onPublishPinClick = { pin, button ->
                publishSpecificPin(pin, button)
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

    private fun publishSpecificPin(
        pin: SavedPin,
        button: com.google.android.material.button.MaterialButton
    ) {
        button.isEnabled = false
        button.text = "Publishing..."

        Toast.makeText(
            requireContext(),
            "Publishing Pin ${pin.pinId} to Instagram...",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            val result = repository.postSinglePin(pin)
            if (result.first) {
                Toast.makeText(
                    requireContext(),
                    "Pin ${pin.pinId} published successfully!",
                    Toast.LENGTH_LONG
                ).show()
                loadData()
            } else {
                button.isEnabled = true
                button.text = "Publish Now"
                Toast.makeText(
                    requireContext(),
                    "Failed to publish: ${result.second}",
                    Toast.LENGTH_LONG
                ).show()
                loadData()
            }
        }
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
        val candidates = pin.getVideoCandidates()
        if (candidates.isEmpty() && pin.mediaUrl.isBlank()) {
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
        val tvStatus = dialog.findViewById<TextView>(R.id.tvVideoStatus)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseVideo)
        val videoView = dialog.findViewById<VideoView>(R.id.videoView)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.pbVideoLoading)

        val rawCaption = if (pin.title.isNotBlank()) pin.title else pin.description.ifEmpty { pin.searchTerm }
        val caption = InstagramCaptionUtils.cleanAndFormatCaption(rawCaption)
        tvTitle.text = caption

        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        var downloadJob: Job? = null

        val stopAndDismiss = {
            downloadJob?.cancel()
            try {
                videoView.stopPlayback()
            } catch (_: Exception) {}
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }

        btnClose.setOnClickListener { stopAndDismiss() }
        dialog.setOnDismissListener {
            downloadJob?.cancel()
            try {
                videoView.stopPlayback()
            } catch (_: Exception) {}
        }

        fun playLocalFile(file: File) {
            progressBar.visibility = View.GONE
            tvStatus.visibility = View.GONE
            videoView.setVideoPath(file.absolutePath)
            videoView.setOnPreparedListener { mp ->
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.GONE
                mp.isLooping = true
                videoView.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.GONE
                Toast.makeText(requireContext(), "Playback error ($what, $extra)", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val cacheDir = File(requireContext().cacheDir, "pin_videos").apply { mkdirs() }
        val localVideoFile = File(cacheDir, "pin_${pin.pinId}.mp4")

        // Fast path: if video is already downloaded in local cache, play instantly!
        if (localVideoFile.exists() && localVideoFile.length() > 1024) {
            playLocalFile(localVideoFile)
            dialog.show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Buffering video..."
        dialog.show()

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            var downloaded = false
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val urlsToTry = if (candidates.isNotEmpty()) candidates else listOf(pin.mediaUrl)

            for (url in urlsToTry) {
                if (!isActive) break
                try {
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Referer", "https://www.pinterest.com/")
                        .header("Accept", "*/*")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val tempFile = File(cacheDir, "temp_${pin.pinId}_${System.currentTimeMillis()}.mp4")
                            body.byteStream().use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }

                            if (tempFile.exists() && tempFile.length() > 1024) {
                                if (localVideoFile.exists()) localVideoFile.delete()
                                tempFile.renameTo(localVideoFile)
                                downloaded = true
                                break
                            } else {
                                tempFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("VideoPlayer", "Failed downloading candidate $url: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                if (!dialog.isShowing) return@withContext

                if (downloaded && localVideoFile.exists() && localVideoFile.length() > 1024) {
                    playLocalFile(localVideoFile)
                } else {
                    // Fallback to streaming directly with proper headers
                    val streamFallback = candidates.firstOrNull() ?: pin.mediaUrl
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                        "Referer" to "https://www.pinterest.com/"
                    )
                    videoView.setVideoURI(Uri.parse(streamFallback), headers)
                    videoView.setOnPreparedListener { mp ->
                        progressBar.visibility = View.GONE
                        tvStatus.visibility = View.GONE
                        mp.isLooping = true
                        videoView.start()
                    }
                    videoView.setOnErrorListener { _, what, extra ->
                        progressBar.visibility = View.GONE
                        tvStatus.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "Cannot play video ($what, $extra). The video stream may have expired.",
                            Toast.LENGTH_LONG
                        ).show()
                        true
                    }
                }
            }
        }
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
