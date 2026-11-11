package com.pintoinsta.app.ui.pins

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.PinterestAccount
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
import kotlinx.coroutines.delay
import androidx.viewpager2.widget.ViewPager2
import com.pintoinsta.app.databinding.LayoutPageLogsBinding
import com.pintoinsta.app.databinding.LayoutPageSavedPinsBinding
import com.pintoinsta.app.databinding.LayoutPageSearchBinding

class PinsLogsFragment : Fragment() {

    private var _binding: FragmentPinsLogsBinding? = null
    private val binding get() = _binding!!

    private var _pageSearchBinding: LayoutPageSearchBinding? = null
    private val pageSearchBinding get() = _pageSearchBinding!!

    private var _pagePinsBinding: LayoutPageSavedPinsBinding? = null
    private val pagePinsBinding get() = _pagePinsBinding!!

    private var _pageLogsBinding: LayoutPageLogsBinding? = null
    private val pageLogsBinding get() = _pageLogsBinding!!

    private lateinit var repository: AppRepository
    private lateinit var pinsAdapter: SavedPinsAdapter
    private lateinit var logsAdapter: PostLogsAdapter
    private lateinit var searchAdapter: SearchPinsAdapter

    private var allPins: List<SavedPin> = emptyList()
    private var currentSubTabId: Int = R.id.btnSubSaved

    // Custom Search State
    private var currentSearchQuery: String = ""
    private var currentBookmark: String? = null
    private var currentScope: String = "pins"
    private var isSearching: Boolean = false
    private var searchAccount: PinterestAccount? = null

    // Auto-update logs job
    private var logPollingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinsLogsBinding.inflate(inflater, container, false)
        _pageSearchBinding = LayoutPageSearchBinding.inflate(inflater, binding.viewPager, false)
        _pagePinsBinding = LayoutPageSavedPinsBinding.inflate(inflater, binding.viewPager, false)
        _pageLogsBinding = LayoutPageLogsBinding.inflate(inflater, binding.viewPager, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AppRepository.getInstance(requireContext())

        setupViewPager()
        setupRecyclerViews()
        setupListeners()
        updateSearchAccountIndicator()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        updateSearchAccountIndicator()
        startAutoUpdatingLogs()
    }

    override fun onPause() {
        super.onPause()
        stopAutoUpdatingLogs()
    }

    private fun setupViewPager() {
        val pages = listOf(pageSearchBinding.root, pagePinsBinding.root, pageLogsBinding.root)
        binding.viewPager.adapter = DirectViewsPagerAdapter(pages)
        binding.viewPager.offscreenPageLimit = 2

        // Swipe Left/Right page change callback
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> {
                        if (binding.bottomSubTabsGroup.checkedButtonId != R.id.btnSubTabSearch) {
                            binding.bottomSubTabsGroup.check(R.id.btnSubTabSearch)
                        }
                        binding.tvHeaderTitle.text = "Pinterest Search"
                        binding.btnClearLogs.visibility = View.GONE
                        updateSearchSelectionUI(searchAdapter.getSelectedCount(), searchAdapter.getTotalCount())
                    }
                    1 -> {
                        if (binding.bottomSubTabsGroup.checkedButtonId != R.id.btnSubTabPins) {
                            binding.bottomSubTabsGroup.check(R.id.btnSubTabPins)
                        }
                        binding.tvHeaderTitle.text = "Saved Pins Vault"
                        binding.btnClearLogs.visibility = View.GONE
                        filterAndDisplayPins()
                        updateSelectionUI()
                    }
                    2 -> {
                        if (binding.bottomSubTabsGroup.checkedButtonId != R.id.btnSubTabLogs) {
                            binding.bottomSubTabsGroup.check(R.id.btnSubTabLogs)
                        }
                        binding.tvHeaderTitle.text = "Activity Logs"
                        binding.btnClearLogs.visibility = if (logsAdapter.itemCount > 0) View.VISIBLE else View.GONE
                        loadLogsSilently()
                    }
                }
            }
        })

        // Bottom subtabs click listener
        binding.bottomSubTabsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val targetPage = when (checkedId) {
                    R.id.btnSubTabSearch -> 0
                    R.id.btnSubTabPins -> 1
                    R.id.btnSubTabLogs -> 2
                    else -> 0
                }
                if (binding.viewPager.currentItem != targetPage) {
                    binding.viewPager.setCurrentItem(targetPage, true)
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        // 1. Search Results Adapter
        searchAdapter = SearchPinsAdapter(
            onSaveSinglePinClick = { pin, _ ->
                saveSinglePinFromSearch(pin)
            },
            onLoadMoreClick = {
                loadMoreSearchResults()
            },
            onSelectionChanged = { selectedCount, totalCount ->
                updateSearchSelectionUI(selectedCount, totalCount)
            }
        )
        pageSearchBinding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        pageSearchBinding.rvSearchResults.adapter = searchAdapter

        pageSearchBinding.rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    if (searchAdapter.hasMore && !searchAdapter.isLoadingMore && !isSearching) {
                        loadMoreSearchResults()
                    }
                }
            }
        })

        // 2. Saved Pins Adapter
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
            onSelectionChanged = { _, _ ->
                updateSelectionUI()
            }
        )
        pagePinsBinding.rvSavedPins.layoutManager = LinearLayoutManager(requireContext())
        pagePinsBinding.rvSavedPins.adapter = pinsAdapter

        // 3. Activity Logs Adapter
        logsAdapter = PostLogsAdapter(
            logs = emptyList(),
            onOpenPostClick = { url, accountId ->
                openReel(url, accountId)
            }
        )
        pageLogsBinding.rvPostLogs.layoutManager = LinearLayoutManager(requireContext())
        pageLogsBinding.rvPostLogs.adapter = logsAdapter
    }

    private fun setupListeners() {
        // Search Input listeners
        pageSearchBinding.etSearchQuery.doAfterTextChanged { s ->
            val query = s?.toString()?.trim() ?: ""
            pageSearchBinding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        }

        // Clear Search option
        pageSearchBinding.btnClearSearch.setOnClickListener {
            pageSearchBinding.etSearchQuery.setText("")
            pageSearchBinding.btnClearSearch.visibility = View.GONE
            searchAdapter.clearAll()
            currentSearchQuery = ""
            currentBookmark = null
            pageSearchBinding.layoutSearchInitialState.visibility = View.VISIBLE
            pageSearchBinding.tvSearchEmptyTitle.text = "Search anything on Pinterest"
            pageSearchBinding.tvSearchEmptySub.text = "Type keywords like 'aesthetic wallpaper', 'diy crafts', or 'comedy reels' to find pins and save them to your vault."
            pageSearchBinding.layoutSearchSelectionBar.visibility = View.GONE
        }

        // Search submit button
        pageSearchBinding.btnSearchSubmit.setOnClickListener {
            val query = pageSearchBinding.etSearchQuery.text.toString().trim()
            hideKeyboard()
            performPinterestSearch(query)
        }

        // Keyboard search action
        pageSearchBinding.etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                val query = pageSearchBinding.etSearchQuery.text.toString().trim()
                performPinterestSearch(query)
                true
            } else {
                false
            }
        }

        // Scope toggle (All Pins vs Videos)
        pageSearchBinding.toggleScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentScope = if (checkedId == R.id.btnScopeVideos) "videos" else "pins"
                if (currentSearchQuery.isNotBlank()) {
                    performPinterestSearch(currentSearchQuery)
                }
            }
        }

        // Bulk Selection in Search
        pageSearchBinding.cbSelectAllSearch.setOnClickListener {
            if (pageSearchBinding.cbSelectAllSearch.isChecked) {
                searchAdapter.selectAll()
            } else {
                searchAdapter.deselectAll()
            }
        }

        // Bulk Save Button in Search
        pageSearchBinding.btnSaveSelectedPins.setOnClickListener {
            saveSelectedPinsFromSearch()
        }

        // Sub-tabs in Saved Pins: Saved (Pending) vs Published
        pagePinsBinding.toggleSubTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSubTabId = checkedId
                pinsAdapter.setSelectionMode(false)
                updateSelectionUI()
                filterAndDisplayPins()
            }
        }

        // Select mode toggle button for Saved Pins
        pagePinsBinding.btnSelectMode.setOnClickListener {
            val newMode = !pinsAdapter.isSelectionMode
            pinsAdapter.setSelectionMode(newMode)
            updateSelectionUI()
        }

        // Close selection button for Saved Pins
        pagePinsBinding.btnCloseSelection.setOnClickListener {
            pinsAdapter.setSelectionMode(false)
            updateSelectionUI()
        }

        // Select all checkbox for Saved Pins
        pagePinsBinding.cbSelectAll.setOnClickListener {
            if (pagePinsBinding.cbSelectAll.isChecked) {
                pinsAdapter.selectAll()
            } else {
                pinsAdapter.clearSelection()
            }
        }

        // Bulk publish button for Saved Pins
        pagePinsBinding.btnBulkPublish.setOnClickListener {
            val selectedPins = pinsAdapter.getSelectedPins()
            if (selectedPins.isNotEmpty()) {
                publishSelectedPins(selectedPins)
            }
        }

        // Bulk delete button for Saved Pins
        pagePinsBinding.btnBulkDelete.setOnClickListener {
            val selectedIds = pinsAdapter.getSelectedPinIds()
            if (selectedIds.isNotEmpty()) {
                confirmBulkDelete(selectedIds)
            }
        }

        // Top Header Actions
        binding.btnClearLogs.setOnClickListener {
            confirmClearLogs()
        }

        binding.btnRefresh.setOnClickListener {
            loadData()
            updateSearchAccountIndicator()
            loadLogsSilently()
        }
    }

    /**
     * Requirement 2: Auto-update logs in real-time.
     * Combines reactive flow updates and a background heartbeat to guarantee new logs appear immediately.
     */
    private fun startAutoUpdatingLogs() {
        logPollingJob?.cancel()
        logPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            // 1. Reactive flow collection
            launch {
                repository.logUpdates.collect {
                    loadLogsSilently()
                }
            }

            // 2. Background polling ticker every 2 seconds
            while (isActive) {
                delay(2000L)
                loadLogsSilently()
            }
        }
    }

    private fun stopAutoUpdatingLogs() {
        logPollingJob?.cancel()
        logPollingJob = null
    }

    private fun loadLogsSilently() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val logs = repository.getRecentLogs()
                val currentLogs = logsAdapter.getCurrentLogs()

                val hasChanged = currentLogs.size != logs.size ||
                        (logs.isNotEmpty() && currentLogs.isNotEmpty() && logs[0].id != currentLogs[0].id)

                if (hasChanged) {
                    logsAdapter.updateData(logs)
                    pageLogsBinding.tvEmptyStateLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                    if (binding.viewPager.currentItem == 2) {
                        binding.btnClearLogs.visibility = if (logs.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateSearchAccountIndicator() {
        lifecycleScope.launch {
            val accounts = repository.getAllPinterestAccounts()
            searchAccount = accounts.firstOrNull { it.status == "ACTIVE" } ?: accounts.firstOrNull()
            if (searchAccount != null) {
                pageSearchBinding.tvSearchAccountIndicator.text = "Searching as @${searchAccount?.username}"
                pageSearchBinding.tvSearchAccountIndicator.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            } else {
                pageSearchBinding.tvSearchAccountIndicator.text = "Guest Mode"
                pageSearchBinding.tvSearchAccountIndicator.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            }
        }
    }

    private fun performPinterestSearch(query: String) {
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a search keyword", Toast.LENGTH_SHORT).show()
            return
        }

        currentSearchQuery = query
        currentBookmark = null
        isSearching = true
        pageSearchBinding.pbSearchLoading.visibility = View.VISIBLE
        pageSearchBinding.layoutSearchInitialState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val savedIds = repository.getSavedPinIds()
                val result = repository.searchPinterestCustom(
                    account = searchAccount,
                    query = currentSearchQuery,
                    bookmark = null,
                    scope = currentScope
                )

                isSearching = false
                pageSearchBinding.pbSearchLoading.visibility = View.GONE

                if (result.success) {
                    currentBookmark = result.bookmark
                    if (result.pins.isEmpty()) {
                        searchAdapter.clearAll()
                        pageSearchBinding.layoutSearchInitialState.visibility = View.VISIBLE
                        pageSearchBinding.tvSearchEmptyTitle.text = "No pins found"
                        pageSearchBinding.tvSearchEmptySub.text = "No Pinterest results for '$currentSearchQuery'. Try a different keyword."
                    } else {
                        pageSearchBinding.layoutSearchInitialState.visibility = View.GONE
                        searchAdapter.setData(
                            newPins = result.pins,
                            hasMoreResults = !result.bookmark.isNullOrBlank(),
                            initialSavedIds = savedIds
                        )
                    }
                } else {
                    pageSearchBinding.layoutSearchInitialState.visibility = View.VISIBLE
                    pageSearchBinding.tvSearchEmptyTitle.text = "Search Error"
                    pageSearchBinding.tvSearchEmptySub.text = result.errorMessage ?: "Failed to load pins from Pinterest."
                    Toast.makeText(requireContext(), "Search error: ${result.errorMessage ?: "Network issue"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                isSearching = false
                pageSearchBinding.pbSearchLoading.visibility = View.GONE
                pageSearchBinding.layoutSearchInitialState.visibility = View.VISIBLE
                pageSearchBinding.tvSearchEmptyTitle.text = "Search Error"
                pageSearchBinding.tvSearchEmptySub.text = e.message ?: "An unexpected error occurred."
                Log.e("PinsLogsFragment", "Search error", e)
            }
        }
    }

    private fun loadMoreSearchResults() {
        if (isSearching || currentBookmark.isNullOrBlank() || !searchAdapter.hasMore || searchAdapter.isLoadingMore) {
            return
        }

        searchAdapter.setLoadingMore(true)
        val bookmarkToLoad = currentBookmark!!

        lifecycleScope.launch {
            try {
                val result = repository.searchPinterestCustom(
                    account = searchAccount,
                    query = currentSearchQuery,
                    bookmark = bookmarkToLoad,
                    scope = currentScope
                )

                searchAdapter.setLoadingMore(false)

                if (result.success) {
                    currentBookmark = result.bookmark
                    searchAdapter.appendData(
                        morePins = result.pins,
                        hasMoreResults = !result.bookmark.isNullOrBlank()
                    )
                } else {
                    Toast.makeText(requireContext(), "Failed to load more: ${result.errorMessage ?: "Network error"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                searchAdapter.setLoadingMore(false)
                Log.e("PinsLogsFragment", "Pagination error", e)
            }
        }
    }

    private fun saveSinglePinFromSearch(pin: SavedPin) {
        lifecycleScope.launch {
            val saved = repository.savePin(pin)
            if (saved) {
                searchAdapter.markPinSaved(pin.pinId)
                Toast.makeText(requireContext(), "Pin saved to Vault!", Toast.LENGTH_SHORT).show()
                allPins = repository.getAllSavedPins()
            } else {
                searchAdapter.markPinSaved(pin.pinId)
                Toast.makeText(requireContext(), "Pin already in Vault", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSelectedPinsFromSearch() {
        val selectedPins = searchAdapter.getSelectedPins()
        if (selectedPins.isEmpty()) {
            Toast.makeText(requireContext(), "Please select at least one pin", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val count = repository.savePins(selectedPins)
            searchAdapter.markPinsSaved(selectedPins.map { it.pinId })
            searchAdapter.deselectAll()
            Toast.makeText(requireContext(), "Saved $count pin(s) to Saved Pins!", Toast.LENGTH_SHORT).show()
            allPins = repository.getAllSavedPins()
        }
    }

    private fun updateSearchSelectionUI(selectedCount: Int, totalCount: Int) {
        if (totalCount > 0) {
            pageSearchBinding.layoutSearchSelectionBar.visibility = View.VISIBLE
            pageSearchBinding.tvSearchSelectedCount.text = "$selectedCount of $totalCount selected"
            pageSearchBinding.cbSelectAllSearch.isChecked = selectedCount > 0 && selectedCount == totalCount
            pageSearchBinding.btnSaveSelectedPins.isEnabled = selectedCount > 0
            pageSearchBinding.btnSaveSelectedPins.text = if (selectedCount > 0) "Save Selected ($selectedCount)" else "Save Selected"
        } else {
            pageSearchBinding.layoutSearchSelectionBar.visibility = View.GONE
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun updateSelectionUI() {
        val isPinsPage = binding.viewPager.currentItem == 1
        if (!isPinsPage) {
            pagePinsBinding.layoutSelectionBar.visibility = View.GONE
            return
        }

        val inSelection = pinsAdapter.isSelectionMode
        if (inSelection) {
            pagePinsBinding.layoutSelectionBar.visibility = View.VISIBLE
            pagePinsBinding.btnSelectMode.text = "Cancel"
            val selectedCount = pinsAdapter.getSelectedCount()
            val totalCount = pinsAdapter.itemCount
            pagePinsBinding.tvSelectedCount.text = "$selectedCount of $totalCount selected"
            pagePinsBinding.btnBulkDelete.isEnabled = selectedCount > 0
            pagePinsBinding.btnBulkDelete.text = if (selectedCount > 0) "Delete ($selectedCount)" else "Delete"
            pagePinsBinding.btnBulkPublish.visibility = if (currentSubTabId == R.id.btnSubSaved) View.VISIBLE else View.GONE
            pagePinsBinding.btnBulkPublish.isEnabled = selectedCount > 0
            pagePinsBinding.btnBulkPublish.text = if (selectedCount > 0) "Publish ($selectedCount)" else "Publish"
            pagePinsBinding.cbSelectAll.isChecked = selectedCount > 0 && selectedCount == totalCount
        } else {
            pagePinsBinding.layoutSelectionBar.visibility = View.GONE
            pagePinsBinding.btnSelectMode.text = "Select"
        }
    }

    private fun filterAndDisplayPins() {
        val unpostedList = allPins.filter { !it.isPosted }
        val publishedList = allPins.filter { it.isPosted }

        pagePinsBinding.btnSubSaved.text = "Saved (${unpostedList.size})"
        pagePinsBinding.btnSubPublished.text = "Published (${publishedList.size})"

        val displayedPins = if (currentSubTabId == R.id.btnSubSaved) unpostedList else publishedList
        pinsAdapter.updateData(displayedPins)

        if (displayedPins.isEmpty()) {
            pagePinsBinding.tvEmptyStatePins.visibility = View.VISIBLE
            pagePinsBinding.tvEmptyStatePins.text = if (currentSubTabId == R.id.btnSubSaved) {
                "No pending saved pins.\nScheduled runs or custom searches will save pins here."
            } else {
                "No published pins yet.\nPublished reels will appear here."
            }
        } else {
            pagePinsBinding.tvEmptyStatePins.visibility = View.GONE
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allPins = repository.getAllSavedPins()
            val logs = repository.getRecentLogs()

            logsAdapter.updateData(logs)
            filterAndDisplayPins()

            val currentPos = binding.viewPager.currentItem
            if (currentPos == 0) {
                binding.btnClearLogs.visibility = View.GONE
                val savedIds = allPins.map { it.pinId }.toSet()
                searchAdapter.markPinsSaved(savedIds)
            } else if (currentPos == 1) {
                binding.btnClearLogs.visibility = View.GONE
            } else {
                binding.btnClearLogs.visibility = if (logs.isNotEmpty()) View.VISIBLE else View.GONE
                pageLogsBinding.tvEmptyStateLogs.text = "No activity logs yet.\nLogs will appear automatically in real-time."
                pageLogsBinding.tvEmptyStateLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
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
                .setMessage("Select the accounts to publish this pin to. If multiple accounts are selected, publishing will be staggered with a delay of 2 minutes between accounts.")
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

        val isMultiAccount = selectedAccountIds.size > 1
        var progressDialog: AlertDialog? = null
        var tvProgress: TextView? = null
        var progressBar: ProgressBar? = null

        if (isMultiAccount) {
            val layout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 40)
            }

            tvProgress = TextView(requireContext()).apply {
                text = "Starting publish for Pin ${pin.pinId} across ${selectedAccountIds.size} accounts..."
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 14f
            }

            progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = selectedAccountIds.size
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

            progressDialog = AlertDialog.Builder(requireContext())
                .setTitle("Publishing Pin (2-Min Delay)")
                .setView(layout)
                .setCancelable(false)
                .create()

            progressDialog.show()
        } else {
            Toast.makeText(
                requireContext(),
                "Starting publish for Pin ${pin.pinId}...",
                Toast.LENGTH_SHORT
            ).show()
        }

        lifecycleScope.launch {
            val result = repository.postPinToMultipleAccounts(pin, selectedAccountIds) { current, total, msg ->
                button.text = "Pub $current/$total..."
                tvProgress?.text = msg
                progressBar?.progress = current
            }

            try {
                progressDialog?.dismiss()
            } catch (_: Exception) {}

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

    private fun publishSelectedPins(pins: List<SavedPin>) {
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
                val acc = accounts.first()
                AlertDialog.Builder(requireContext())
                    .setTitle("Publish ${pins.size} Selected Post(s)")
                    .setMessage("Publish ${pins.size} post(s) to @${acc.username}?\n\nIf multiple posts are selected, they will be published with a 2-minute delay between each post to protect account safety.")
                    .setPositiveButton("Publish (${pins.size} Posts)") { _, _ ->
                        executeBulkPublish(pins, listOf(acc.id))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
                .setMessage("Select the accounts to publish ${pins.size} selected post(s) to.\n\nPublishing across accounts will be staggered with a 2-minute delay between each account to protect your accounts.")
                .setPositiveButton("Publish (${pins.size} Posts)") { _, _ ->
                    val selectedIds = accounts.filterIndexed { index, _ -> checkedItems[index] }.map { it.id }
                    if (selectedIds.isEmpty()) {
                        Toast.makeText(requireContext(), "No accounts selected", Toast.LENGTH_SHORT).show()
                    } else {
                        executeBulkPublish(pins, selectedIds)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun executeBulkPublish(
        pins: List<SavedPin>,
        selectedAccountIds: List<Long>
    ) {
        val totalOperations = pins.size * selectedAccountIds.size

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val tvProgress = TextView(requireContext()).apply {
            text = "Preparing to publish ${pins.size} post(s) across ${selectedAccountIds.size} account(s)..."
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
        }

        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalOperations
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
            .setTitle("Publishing Posts (2-Min Delay)")
            .setView(layout)
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = repository.postMultiplePinsToMultipleAccounts(pins, selectedAccountIds) { current, total, msg ->
                tvProgress.text = msg
                progressBar.progress = current
            }

            try {
                progressDialog.dismiss()
            } catch (_: Exception) {}

            pinsAdapter.setSelectionMode(false)
            updateSelectionUI()
            loadData()

            AlertDialog.Builder(requireContext())
                .setTitle("Publish Completed")
                .setMessage("Successfully published ${result.first} of $totalOperations target operations!\n\n${result.second}")
                .setPositiveButton("OK", null)
                .show()
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
        stopAutoUpdatingLogs()
        _pageSearchBinding = null
        _pagePinsBinding = null
        _pageLogsBinding = null
        _binding = null
    }
}
