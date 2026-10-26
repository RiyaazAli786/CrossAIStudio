package com.pintoinsta.app.ui.pins

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentPinsLogsBinding
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
        pinsAdapter = SavedPinsAdapter(emptyList())
        binding.rvSavedPins.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedPins.adapter = pinsAdapter

        logsAdapter = PostLogsAdapter(emptyList())
        binding.rvPostLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPostLogs.adapter = logsAdapter
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
