package com.pintoinsta.app.ui.pins

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentPinsLogsBinding
import com.pintoinsta.app.notification.NotificationHelper
import com.pintoinsta.app.ui.viewer.PostViewerActivity
import com.pintoinsta.app.util.InstagramCaptionUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PinsLogsFragment : Fragment() {

    private var _binding: FragmentPinsLogsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var pinsAdapter: SavedPinsAdapter
    private lateinit var logsAdapter: PostLogsAdapter

    private var allPins: List<SavedPin> = emptyList()
    private var currentSubTabId: Int = R.id.btnSubSaved

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
                val targetUrl = pin.getEffectivePostUrl() ?: "https://www.instagram.com/"
                openReel(targetUrl, pin.instagramAccountId)
            },
            onPublishPinClick = { pin, button ->
                publishSpecificPin(pin, button)
            },
            onItemLongClick = {
                updateSelectionUI()
            },
            onSelectionChanged = { selectedCount, totalCount ->
                updateSelectionUI()
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

    private fun setupListeners() {
        // Top-level tabs: Saved Pins vs Activity Logs
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTabPins) {
                    binding.rvSavedPins.visibility = View.VISIBLE
                    binding.rvPostLogs.visibility = View.GONE
                    binding.layoutSubTabs.visibility = View.VISIBLE
                    binding.btnClearLogs.visibility = View.GONE
                    updateSelectionUI()
                } else {
                    binding.rvSavedPins.visibility = View.GONE
                    binding.rvPostLogs.visibility = View.VISIBLE
                    binding.layoutSubTabs.visibility = View.GONE
                    binding.layoutSelectionBar.visibility = View.GONE
                }
                loadData()
            }
        }

        // Sub-tabs: Saved (Pending) vs Published
        binding.toggleSubTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSubTabId = checkedId
                pinsAdapter.setSelectionMode(false)
                updateSelectionUI()
                filterAndDisplayPins()
            }
        }

        // Select mode toggle button
        binding.btnSelectMode.setOnClickListener {
            val newMode = !pinsAdapter.isSelectionMode
            pinsAdapter.setSelectionMode(newMode)
            updateSelectionUI()
        }

        // Close selection button
        binding.btnCloseSelection.setOnClickListener {
            pinsAdapter.setSelectionMode(false)
            updateSelectionUI()
        }

        // Select all checkbox
        binding.cbSelectAll.setOnClickListener {
            if (binding.cbSelectAll.isChecked) {
                pinsAdapter.selectAll()
            } else {
                pinsAdapter.clearSelection()
            }
        }

        // Bulk delete button
        binding.btnBulkDelete.setOnClickListener {
            val selectedIds = pinsAdapter.getSelectedPinIds()
            if (selectedIds.isNotEmpty()) {
                confirmBulkDelete(selectedIds)
            }
        }

        binding.btnClearLogs.setOnClickListener {
            confirmClearLogs()
        }

        binding.btnRefresh.setOnClickListener {
            loadData()
        }
    }

    private fun updateSelectionUI() {
        val isPinsTab = binding.toggleGroup.checkedButtonId == R.id.btnTabPins
        if (!isPinsTab) {
            binding.layoutSelectionBar.visibility = View.GONE
            return
        }

        val inSelection = pinsAdapter.isSelectionMode
        if (inSelection) {
            binding.layoutSelectionBar.visibility = View.VISIBLE
            binding.btnSelectMode.text = "Cancel"
            val selectedCount = pinsAdapter.getSelectedCount()
            val totalCount = pinsAdapter.itemCount
            binding.tvSelectedCount.text = "$selectedCount of $totalCount selected"
            binding.btnBulkDelete.isEnabled = selectedCount > 0
            binding.btnBulkDelete.text = if (selectedCount > 0) "Delete ($selectedCount)" else "Delete"
            binding.cbSelectAll.isChecked = selectedCount > 0 && selectedCount == totalCount
        } else {
            binding.layoutSelectionBar.visibility = View.GONE
            binding.btnSelectMode.text = "Select"
        }
    }

    private fun filterAndDisplayPins() {
        val unpostedList = allPins.filter { !it.isPosted }
        val publishedList = allPins.filter { it.isPosted }

        binding.btnSubSaved.text = "Saved (${unpostedList.size})"
        binding.btnSubPublished.text = "Published (${publishedList.size})"

        val displayedPins = if (currentSubTabId == R.id.btnSubSaved) unpostedList else publishedList
        pinsAdapter.updateData(displayedPins)

        val isPinsTab = binding.toggleGroup.checkedButtonId == R.id.btnTabPins
        if (isPinsTab) {
            if (displayedPins.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = if (currentSubTabId == R.id.btnSubSaved) {
                    "No pending saved pins.\nScheduled runs will fetch pins automatically."
                } else {
                    "No published pins yet.\nPublished reels will appear here."
                }
            } else {
                binding.tvEmptyState.visibility = View.GONE
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allPins = repository.getAllSavedPins()
            val logs = repository.getRecentLogs()

            logsAdapter.updateData(logs)
            filterAndDisplayPins()

            val isPinsTab = binding.toggleGroup.checkedButtonId == R.id.btnTabPins
            if (isPinsTab) {
                binding.btnClearLogs.visibility = View.GONE
            } else {
                binding.btnClearLogs.visibility = if (logs.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvEmptyState.text = "No activity logs yet.\nLogs will appear after posting runs."
                binding.tvEmptyState.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
        lifecycleScope.launch {
            val accounts = repository.getAllInstagramAccounts()
            if (accounts.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No Instagram accounts added yet. Please add an Instagram account first.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            if (accounts.size == 1) {
                executePublish(pin, listOf(accounts.first().id), button)
                return@launch
            }

            val accountNames = accounts.map { "@${it.username} (${it.status})" }.toTypedArray()
            val checkedItems = BooleanArray(accounts.size) { false }
            checkedItems[0] = true

            AlertDialog.Builder(requireContext())
                .setTitle("Select Instagram Accounts")
                .setMultiChoiceItems(accountNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setMessage("Select the accounts to publish this pin to. If multiple accounts are selected, publishing will be staggered with a random delay of 5 to 10 minutes between accounts.")
                .setPositiveButton("Publish") { _, _ ->
                    val selectedIds = accounts.filterIndexed { index, _ -> checkedItems[index] }.map { it.id }
                    if (selectedIds.isEmpty()) {
                        Toast.makeText(requireContext(), "No accounts selected", Toast.LENGTH_SHORT).show()
                    } else {
                        executePublish(pin, selectedIds, button)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun executePublish(
        pin: SavedPin,
        selectedAccountIds: List<Long>,
        button: com.google.android.material.button.MaterialButton
    ) {
        button.isEnabled = false
        button.text = "Publishing..."

        Toast.makeText(
            requireContext(),
            "Starting publish for Pin ${pin.pinId} (${selectedAccountIds.size} accounts selected)...",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            val result = repository.postPinToMultipleAccounts(pin, selectedAccountIds) { current, total, msg ->
                button.text = "Pub $current/$total..."
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }

            if (result.first > 0) {
                // Requirement 3: Move immediately to published as soon as publish video completes!
                pin.isPosted = true
                Toast.makeText(
                    requireContext(),
                    "Published ${result.first}/${selectedAccountIds.size} account(s)!\n${result.second}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Publish failed: ${result.second}",
                    Toast.LENGTH_LONG
                ).show()
                button.isEnabled = true
                button.text = "Publish Now"
            }
            // Reload and update immediately
            loadData()
        }
    }

    private fun confirmDeletePin(pin: SavedPin) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Saved Pin")
            .setMessage("Are you sure you want to delete Pin ${pin.pinId}?\nThis will remove it from saved pins and it will not be posted.")
            .setIcon(R.drawable.ic_delete)
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

    private fun confirmBulkDelete(pinIds: List<String>) {
        val count = pinIds.size
        AlertDialog.Builder(requireContext())
            .setTitle("Delete $count Selected Pins")
            .setMessage("Are you sure you want to delete $count selected pins? This action cannot be undone.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Delete All") { _, _ ->
                executeBulkDelete(pinIds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeBulkDelete(pinIds: List<String>) {
        val count = pinIds.size

        // Build customized Progress Dialog with horizontal progress bar
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val tvProgress = TextView(requireContext()).apply {
            text = "Deleting 0 of $count pins..."
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
        }

        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = count
            progress = 0
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        }

        layout.addView(tvProgress)
        layout.addView(progressBar)

        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Deleting Pins")
            .setView(layout)
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val deletedCount = repository.deleteSavedPins(pinIds) { current, total ->
                tvProgress.text = "Deleting $current of $total pins..."
                progressBar.progress = current
            }

            try {
                progressDialog.dismiss()
            } catch (_: Exception) {}

            pinsAdapter.setSelectionMode(false)
            updateSelectionUI()
            Toast.makeText(requireContext(), "Bulk deleted $deletedCount pins successfully", Toast.LENGTH_SHORT).show()
            loadData()
        }
    }

    private fun showVideoPlayerDialog(pin: SavedPin) {
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

        val stopAndDismiss = {
            try {
                videoView.stopPlayback()
            } catch (_: Exception) {}
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }

        btnClose.setOnClickListener { stopAndDismiss() }

        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Loading video..."
        dialog.show()

        val cacheDir = File(requireContext().cacheDir, "pin_videos").apply { mkdirs() }
        val localVideoFile = File(cacheDir, "pin_${pin.pinId}.mp4")

        fun isRealMp4(file: File): Boolean {
            if (!file.exists() || file.length() < 1000) return false
            return try {
                file.inputStream().use { stream ->
                    val header = ByteArray(12)
                    val read = stream.read(header)
                    if (read >= 8) {
                        val magic = String(header, 4, 4, Charsets.US_ASCII)
                        magic == "ftyp"
                    } else false
                }
            } catch (_: Exception) {
                false
            }
        }

        // Clean out any corrupt fake MP4s
        if (localVideoFile.exists() && !isRealMp4(localVideoFile)) {
            localVideoFile.delete()
        }

        if (isRealMp4(localVideoFile)) {
            // Already downloaded: play immediately without network latency
            progressBar.visibility = View.GONE
            tvStatus.visibility = View.GONE
            videoView.setVideoPath(localVideoFile.absolutePath)
            videoView.setOnPreparedListener { mp ->
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.GONE
                mp.isLooping = true
                videoView.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                progressBar.visibility = View.GONE
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Playback error ($what, $extra)"
                localVideoFile.delete()
                true
            }
        } else {
            // Download & prepare real MP4 in background instead of streaming raw CMAF HLS (which causes 1, -1007)
            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Preparing & buffering video..."

            val prepareJob = viewLifecycleOwner.lifecycleScope.launch {
                val preparedFile = repository.downloadVideoForPin(pin)
                if (preparedFile != null && preparedFile.exists() && isRealMp4(preparedFile)) {
                    if (dialog.isShowing) {
                        progressBar.visibility = View.GONE
                        tvStatus.visibility = View.GONE
                        videoView.setVideoPath(preparedFile.absolutePath)
                        videoView.setOnPreparedListener { mp ->
                            progressBar.visibility = View.GONE
                            tvStatus.visibility = View.GONE
                            mp.isLooping = true
                            videoView.start()
                        }
                        videoView.setOnErrorListener { _, what, extra ->
                            progressBar.visibility = View.GONE
                            tvStatus.visibility = View.VISIBLE
                            tvStatus.text = "Playback error ($what, $extra)"
                            preparedFile.delete()
                            true
                        }
                    }
                } else {
                    if (dialog.isShowing) {
                        progressBar.visibility = View.GONE
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = "Cannot load video"
                        Toast.makeText(requireContext(), "Video preparation failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            dialog.setOnDismissListener {
                prepareJob.cancel()
                try {
                    videoView.stopPlayback()
                } catch (_: Exception) {}
            }
        }
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Activity Logs")
            .setMessage("Are you sure you want to clear all activity logs? This action cannot be undone.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    val success = repository.clearAllLogs()
                    if (success) {
                        NotificationHelper.getInstance(requireContext()).clearAllActivityNotifications()
                        Toast.makeText(requireContext(), "All activity logs cleared", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(requireContext(), "Failed to clear logs", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
