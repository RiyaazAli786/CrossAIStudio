package com.pintoinsta.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pintoinsta.app.R
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.FragmentSettingsBinding
import com.pintoinsta.app.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: AppRepository
    private lateinit var adapter: KeywordsAdapter

    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                saveExportToUri(uri)
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFromUri(uri)
            }
        }

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
        updateAccountStats()
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
        // Theme capsule toggle setup
        val currentTheme = ThemeManager.getSavedTheme(requireContext())
        if (currentTheme == ThemeManager.THEME_DARK) {
            binding.toggleThemeGroup.check(R.id.btnThemeDark)
        } else {
            binding.toggleThemeGroup.check(R.id.btnThemeLight)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = if (checkedId == R.id.btnThemeDark) {
                    ThemeManager.THEME_DARK
                } else {
                    ThemeManager.THEME_LIGHT
                }
                if (newTheme != ThemeManager.getSavedTheme(requireContext())) {
                    lifecycleScope.launch {
                        repository.insertActivityLog("THEME", "App theme switched to $newTheme mode", "INFO")
                    }
                    ThemeManager.setTheme(requireContext(), newTheme)
                }
            }
        }

        // Account Export button
        binding.btnExportAccounts.setOnClickListener {
            showExportOptionsDialog()
        }

        // Account Import button
        binding.btnImportAccounts.setOnClickListener {
            showImportOptionsDialog()
        }

        // Add Keyword button
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

    private fun updateAccountStats() {
        lifecycleScope.launch {
            val pinAccounts = repository.getAllPinterestAccounts()
            val instaAccounts = repository.getAllInstagramAccounts()
            binding.tvAccountStats.text =
                "Saved Accounts: ${pinAccounts.size} Pinterest • ${instaAccounts.size} Instagram"
        }
    }

    private fun showExportOptionsDialog() {
        lifecycleScope.launch {
            val pinAccounts = repository.getAllPinterestAccounts()
            val instaAccounts = repository.getAllInstagramAccounts()

            if (pinAccounts.isEmpty() && instaAccounts.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No logged-in accounts found to export",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val options = arrayOf(
                "Save as JSON File",
                "Share via Apps (Drive, Gmail, WhatsApp...)",
                "Copy JSON to Clipboard"
            )

            AlertDialog.Builder(requireContext())
                .setTitle("Export All Logged-In Accounts")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val filename = "crossai_accounts_backup_${System.currentTimeMillis()}.json"
                            exportFileLauncher.launch(filename)
                        }
                        1 -> shareExportJson()
                        2 -> copyExportToClipboard()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveExportToUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val jsonString = repository.exportAccountsJson()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                    }
                }
                Toast.makeText(
                    requireContext(),
                    "Accounts backup & session tokens exported successfully to file!",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to export accounts: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareExportJson() {
        lifecycleScope.launch {
            try {
                val jsonString = repository.exportAccountsJson()
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "CrossAIStudio Accounts & Sessions Backup")
                    putExtra(Intent.EXTRA_TEXT, jsonString)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Accounts Backup"))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error generating export: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun copyExportToClipboard() {
        lifecycleScope.launch {
            try {
                val jsonString = repository.exportAccountsJson()
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CrossAI Accounts Backup", jsonString)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    "Accounts backup with session tokens copied to clipboard!",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error copying export: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showImportOptionsDialog() {
        val options = arrayOf(
            "Select JSON Backup File",
            "Paste JSON Content"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Import Accounts")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        importFileLauncher.launch(
                            arrayOf("application/json", "text/*", "*/*")
                        )
                    }
                    1 -> showPasteJsonDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteJsonDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "Paste JSON backup text here..."
            maxLines = 10
            isSingleLine = false
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Paste Backup JSON")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val jsonText = editText.text.toString().trim()
                if (jsonText.isNotEmpty()) {
                    processImportJson(jsonText)
                } else {
                    Toast.makeText(requireContext(), "JSON cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val jsonText = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    }
                }
                if (jsonText.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "Selected file is empty", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                processImportJson(jsonText)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed reading file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun processImportJson(jsonText: String) {
        lifecycleScope.launch {
            val result = repository.importAccountsJson(jsonText)
            updateAccountStats()

            AlertDialog.Builder(requireContext())
                .setTitle("Import Result")
                .setMessage(
                    "Pinterest Accounts: ${result.pinterestImported}\n" +
                    "Instagram Accounts: ${result.instagramImported}\n\n" +
                    result.message
                )
                .setPositiveButton("OK", null)
                .show()
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
