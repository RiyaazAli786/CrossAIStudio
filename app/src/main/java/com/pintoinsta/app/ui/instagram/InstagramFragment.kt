package com.pintoinsta.app.ui.instagram

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
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentInstagramBinding
import com.pintoinsta.app.ui.webview.WebLoginActivity
import kotlinx.coroutines.launch

class InstagramFragment : Fragment() {

    private var _binding: FragmentInstagramBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var adapter: InstagramAccountsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInstagramBinding.inflate(inflater, container, false)
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
        adapter = InstagramAccountsAdapter(
            accounts = emptyList(),
            onCheckStatus = { account -> checkStatus(account) },
            onWebLogin = { account -> openWebLogin(account) },
            onDelete = { account -> confirmDelete(account) }
        )

        binding.rvInstagramAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvInstagramAccounts.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            loadAccounts()
        }
    }

    private fun setupListeners() {
        binding.btnAddInstagram.setOnClickListener {
            val intent = Intent(requireContext(), WebLoginActivity::class.java).apply {
                putExtra(WebLoginActivity.EXTRA_PLATFORM, WebLoginActivity.PLATFORM_INSTAGRAM)
            }
            startActivity(intent)
        }
    }

    private fun loadAccounts() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            val accounts = repository.getAllInstagramAccounts()
            adapter.updateData(accounts)
            binding.tvEmptyState.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun checkStatus(account: InstagramAccount) {
        lifecycleScope.launch {
            val res = repository.checkInstagramAccountStatus(account)
            Toast.makeText(
                requireContext(),
                "@${account.username}: ${res.second}",
                Toast.LENGTH_SHORT
            ).show()
            loadAccounts()
        }
    }

    private fun openWebLogin(account: InstagramAccount) {
        val intent = Intent(requireContext(), WebLoginActivity::class.java).apply {
            putExtra(WebLoginActivity.EXTRA_PLATFORM, WebLoginActivity.PLATFORM_INSTAGRAM)
            putExtra(WebLoginActivity.EXTRA_RE_LOGIN_USERNAME, account.username)
            putExtra(WebLoginActivity.EXTRA_FROM_CARD, true)
        }
        startActivity(intent)
    }

    private fun confirmDelete(account: InstagramAccount) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Account")
            .setMessage("Are you sure you want to remove '@${account.username}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteInstagramAccount(account.id)
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
