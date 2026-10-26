package com.pintoinsta.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var adapter: KeywordsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
        loadKeywords()
    }

    private fun setupRecyclerView() {
        adapter = KeywordsAdapter(emptyList()) { keyword ->
            lifecycleScope.launch {
                repository.deleteKeyword(keyword.id)
                loadKeywords()
            }
        }

        binding.rvKeywords.layoutManager = LinearLayoutManager(requireContext())
        binding.rvKeywords.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnAddKeyword.setOnClickListener {
            val text = binding.etKeyword.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                binding.tilKeyword.error = "Please enter a keyword"
                return@setOnClickListener
            }
            binding.tilKeyword.error = null

            lifecycleScope.launch {
                val id = repository.addKeyword(text)
                if (id != -1L) {
                    binding.etKeyword.text?.clear()
                    Toast.makeText(requireContext(), "Added: $text", Toast.LENGTH_SHORT).show()
                    loadKeywords()
                } else {
                    binding.tilKeyword.error = "Keyword already exists"
                }
            }
        }
    }

    private fun loadKeywords() {
        lifecycleScope.launch {
            val keywords = repository.getAllKeywords()
            adapter.updateData(keywords)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
