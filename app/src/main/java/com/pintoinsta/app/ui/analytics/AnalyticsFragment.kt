package com.pintoinsta.app.ui.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.model.OverallAnalytics
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentAnalyticsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private val tableAdapter = AccountAnalyticsAdapter()
    private var currentAnalytics: OverallAnalytics? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AppRepository.getInstance(requireContext())

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadAnalytics()
    }

    private fun setupUI() {
        binding.rvAccountAnalytics.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAccountAnalytics.adapter = tableAdapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            loadAnalytics()
        }

        binding.btnRefresh.setOnClickListener {
            loadAnalytics()
        }

        binding.chartToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                renderChart(checkedId)
            }
        }
    }

    private fun loadAnalytics() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            val data = repository.getAnalyticsData()
            currentAnalytics = data

            // 1. KPI Cards
            binding.tvTotalPublished.text = "${data.totalPublished}"
            binding.tvTotalFailed.text = "${data.totalFailed}"
            binding.tvOverallSuccessRate.text = String.format(Locale.getDefault(), "%.1f%%", data.overallSuccessRate)
            binding.tvTotalAccounts.text = "${data.totalAccounts}"

            // 2. Table
            tableAdapter.updateData(data.accountStats)
            binding.tvTableEmpty.visibility = if (data.accountStats.isEmpty()) View.VISIBLE else View.GONE

            // 3. Chart
            renderChart(binding.chartToggleGroup.checkedButtonId)

            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun renderChart(checkedId: Int) {
        val data = currentAnalytics ?: return

        if (checkedId == R.id.btnChartDaily) {
            // 7 Days Trend
            val barGroups = data.dailyStats.map {
                AnalyticsBarChartView.BarGroup(
                    label = it.dateLabel,
                    published = it.publishedCount,
                    failed = it.failedCount
                )
            }
            binding.barChartView.setData(barGroups)
        } else {
            // By Accounts
            val barGroups = if (data.accountStats.isEmpty()) {
                emptyList()
            } else {
                data.accountStats.map {
                    AnalyticsBarChartView.BarGroup(
                        label = "@${it.username}",
                        published = it.totalPublished,
                        failed = it.totalFailed
                    )
                }
            }
            binding.barChartView.setData(barGroups)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
