package com.pintoinsta.app.ui.pinterest

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentPinterestBinding
import com.pintoinsta.app.ui.webview.WebLoginActivity
import com.pintoinsta.app.worker.ScheduleManager
import kotlinx.coroutines.launch

class PinterestFragment : Fragment() {

    private var _binding: FragmentPinterestBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var adapter: PinterestAccountsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPinterestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AppRepository.getInstance(requireContext())

        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadAccounts()
    }

    private fun setupRecyclerView() {
        adapter = PinterestAccountsAdapter(
            accounts = emptyList(),
            instagramAccounts = emptyList(),
            onCheckStatus = { account -> checkStatus(account) },
            onWebLogin = { account -> openWebLogin(account) },
            onRunNow = { account -> runNow(account) },
            onDelete = { account -> confirmDelete(account) },
            onScheduleChanged = { account, linkedInstaId, interval, isAuto, linkedIdsStr, dailyPostTimes ->
                updateSchedule(account, linkedInstaId, interval, isAuto, linkedIdsStr, dailyPostTimes)
            }
        )

        binding.rvPinterestAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPinterestAccounts.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            loadAccounts()
        }
    }

    private fun setupListeners() {
        binding.btnAddPinterest.setOnClickListener {
            val intent = Intent(requireContext(), WebLoginActivity::class.java).apply {
                putExtra(WebLoginActivity.EXTRA_PLATFORM, WebLoginActivity.PLATFORM_PINTEREST)
            }
            startActivity(intent)
        }
    }

    private fun loadAccounts() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            val pinterestAccounts = repository.getAllPinterestAccounts()
            val instagramAccounts = repository.getAllInstagramAccounts()

            adapter.updateData(pinterestAccounts, instagramAccounts)
            binding.tvEmptyState.visibility = if (pinterestAccounts.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun checkStatus(account: PinterestAccount) {
        lifecycleScope.launch {
            val res = repository.checkPinterestAccountStatus(account)
            Toast.makeText(
                requireContext(),
                "${account.username}: ${res.second}",
                Toast.LENGTH_SHORT
            ).show()
            loadAccounts()
        }
    }

    private fun openWebLogin(account: PinterestAccount) {
        val intent = Intent(requireContext(), WebLoginActivity::class.java).apply {
            putExtra(WebLoginActivity.EXTRA_PLATFORM, WebLoginActivity.PLATFORM_PINTEREST)
            putExtra(WebLoginActivity.EXTRA_RE_LOGIN_USERNAME, account.username)
            putExtra(WebLoginActivity.EXTRA_FROM_CARD, true)
        }
        startActivity(intent)
    }

    private fun runNow(account: PinterestAccount) {
        Toast.makeText(
            requireContext(),
            "Starting execution for '${account.username}'...",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch {
            val res = repository.executePublishOrSearchFlow(account.id)
            Toast.makeText(requireContext(), res.second, Toast.LENGTH_LONG).show()
            loadAccounts()
        }
    }

    private fun updateSchedule(
        account: PinterestAccount,
        linkedInstaId: Long?,
        interval: Int,
        isAuto: Boolean,
        linkedIdsStr: String = "",
        dailyPostTimes: String = ""
    ) {
        lifecycleScope.launch {
            repository.updatePinterestAccountSchedule(account.id, linkedInstaId, interval, isAuto, linkedIdsStr, dailyPostTimes)
            account.linkedInstagramAccountId = linkedInstaId
            account.linkedInstagramAccountIds = linkedIdsStr
            account.scheduleIntervalHours = interval
            account.isAutoPostEnabled = isAuto
            if (dailyPostTimes.isNotBlank()) {
                account.dailyPostTimes = dailyPostTimes
                account.nextRunTime = account.calculateNextScheduledTime(System.currentTimeMillis())
                repository.updatePinterestAccountRunInfo(account.id, account.lastRunTime, account.lastRunStatus, account.nextRunTime)
            }
            ScheduleManager.updateAccountSchedule(requireContext(), account)
            Toast.makeText(
                requireContext(),
                "Schedule updated for '${account.username}'",
                Toast.LENGTH_SHORT
            ).show()

            loadAccounts()
        }
    }

    private fun confirmDelete(account: PinterestAccount) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to remove '@${account.username}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deletePinterestAccount(account.id)
                    ScheduleManager.updateAccountSchedule(
                        requireContext(),
                        account.copy(isAutoPostEnabled = false)
                    )
                    loadAccounts()
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
