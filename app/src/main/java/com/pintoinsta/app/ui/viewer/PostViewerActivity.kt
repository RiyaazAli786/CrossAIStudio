package com.pintoinsta.app.ui.viewer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.ActivityPostViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PostViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_post_url"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val EXTRA_COOKIES = "extra_cookies"

        private const val USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var binding: ActivityPostViewerBinding
    private lateinit var repository: AppRepository

    private var targetUrl: String = ""
    private var accountId: Long = -1L
    private var cookies: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository.getInstance(this)

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
        cookies = intent.getStringExtra(EXTRA_COOKIES) ?: ""

        if (targetUrl.isBlank()) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupWebView()
        setupBackNavigation()

        loadPostWithSession()
    }

    private fun setupToolbar() {
        binding.tvViewerUrl.text = targetUrl
        if (targetUrl.contains("/reel/")) {
            binding.tvViewerTitle.text = "Instagram Reel"
        } else if (targetUrl.contains("/p/")) {
            binding.tvViewerTitle.text = "Instagram Post"
        } else {
            binding.tvViewerTitle.text = "Instagram Preview"
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCopyUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Instagram Post URL", targetUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Post URL copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenExternal.setOnClickListener {
            try {
                // Attempt to open in official Instagram app first
                val instaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    setPackage("com.instagram.android")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(instaIntent)
            } catch (e: Exception) {
                // Fallback to default browser
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (err: Exception) {
                    Toast.makeText(this, "Could not open external app", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = USER_AGENT_MOBILE

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.pbLoading.visibility = View.VISIBLE
                    binding.pbLoading.progress = newProgress
                } else {
                    binding.pbLoading.visibility = View.GONE
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.pbLoading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.pbLoading.visibility = View.GONE
                if (!url.isNullOrBlank()) {
                    binding.tvViewerUrl.text = url
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun loadPostWithSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            var activeCookies = cookies

            // If cookies were not passed directly, fetch from database by accountId
            if (activeCookies.isBlank() && accountId != -1L) {
                val account = repository.getInstagramAccountById(accountId)
                if (account != null && account.cookies.isNotBlank()) {
                    activeCookies = account.cookies
                }
            }

            withContext(Dispatchers.Main) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

                if (activeCookies.isNotBlank()) {
                    // Inject published account's session cookies so the user is authenticated in the WebView
                    for (part in activeCookies.split(";")) {
                        val trimmed = part.trim()
                        if (trimmed.isNotEmpty()) {
                            cookieManager.setCookie("https://www.instagram.com", trimmed)
                            cookieManager.setCookie("https://instagram.com", trimmed)
                            cookieManager.setCookie(".instagram.com", trimmed)
                        }
                    }
                    cookieManager.flush()
                }

                // Load the published reel/post URL
                binding.webView.loadUrl(targetUrl)
            }
        }
    }

    override fun onDestroy() {
        try {
            binding.webView.stopLoading()
            binding.webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
