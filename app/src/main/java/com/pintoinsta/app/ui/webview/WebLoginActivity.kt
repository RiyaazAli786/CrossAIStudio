package com.pintoinsta.app.ui.webview

import android.annotation.SuppressLint
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
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.ActivityWebLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WebLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATFORM = "extra_platform" // "PINTEREST" or "INSTAGRAM"
        const val EXTRA_RE_LOGIN_USERNAME = "extra_re_login_username"
        const val PLATFORM_PINTEREST = "PINTEREST"
        const val PLATFORM_INSTAGRAM = "INSTAGRAM"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityWebLoginBinding
    private lateinit var repository: AppRepository
    private var platform = PLATFORM_PINTEREST
    private var reLoginUsername: String? = null
    private var isSessionCaptured = false
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prevent status bar and navigation bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        repository = AppRepository.getInstance(this)
        platform = intent.getStringExtra(EXTRA_PLATFORM) ?: PLATFORM_PINTEREST
        reLoginUsername = intent.getStringExtra(EXTRA_RE_LOGIN_USERNAME)

        setupToolbar()
        setupWebView()

        binding.btnCaptureSession.setOnClickListener {
            handleManualGrabClick()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = if (platform == PLATFORM_PINTEREST) "Pinterest Login" else "Instagram Login"
        binding.toolbar.subtitle = if (reLoginUsername != null) "Re-logging in: @$reLoginUsername" else "Log in to your account"
        binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = USER_AGENT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Never auto-capture on page start - wait for page to finish loading and user to log in
                updateStatusMessage(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = getPlatformCookies()
                if (isUserLoggedIn(url, cookies)) {
                    // Only auto-capture when we have confirmed the user reached a logged-in page
                    checkAndCaptureSession(force = false)
                } else {
                    updateStatusMessage(url)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        val startUrl = if (platform == PLATFORM_PINTEREST) {
            "https://www.pinterest.com/login/"
        } else {
            "https://www.instagram.com/accounts/login/"
        }

        // When adding a new account, clear old guest/session cookies so the user sees a fresh login form
        if (reLoginUsername == null) {
            cookieManager.removeAllCookies {
                cookieManager.flush()
                webView.clearCache(true)
                webView.loadUrl(startUrl)
            }
        } else {
            webView.loadUrl(startUrl)
        }
    }

    private fun getPlatformCookies(): String {
        val cookieManager = CookieManager.getInstance()
        return if (platform == PLATFORM_PINTEREST) {
            val main = cookieManager.getCookie("https://www.pinterest.com") ?: ""
            val localized = cookieManager.getCookie("https://in.pinterest.com") ?: ""
            if (main.isNotEmpty()) main else localized
        } else {
            cookieManager.getCookie("https://www.instagram.com") ?: ""
        }
    }

    private fun isUserLoggedIn(url: String?, cookies: String): Boolean {
        val cleanUrl = url?.lowercase() ?: ""
        return if (platform == PLATFORM_PINTEREST) {
            // Must NOT be on login or signup URL
            val onAuthPage = cleanUrl.contains("/login") || cleanUrl.contains("/signup") || cleanUrl.contains("/register")
            // Pinterest logged-in session MUST have _auth=1 (guests have _auth=0) and _pinterest_sess
            val hasAuth = cookies.contains("_auth=1")
            val hasSess = cookies.contains("_pinterest_sess=")
            !onAuthPage && hasAuth && hasSess
        } else {
            // Instagram: Must NOT be on login URL
            val onAuthPage = cleanUrl.contains("/accounts/login") || cleanUrl.contains("/accounts/emailsignup") || cleanUrl.contains("/accounts/onetap")
            val hasSessionId = cookies.contains("sessionid=")
            val dsUserId = extractCookieValue(cookies, "ds_user_id")
            !onAuthPage && hasSessionId && dsUserId.isNotEmpty() && dsUserId != "0"
        }
    }

    private fun updateStatusMessage(url: String?) {
        val cleanUrl = url?.lowercase() ?: ""
        if (platform == PLATFORM_PINTEREST) {
            if (cleanUrl.contains("/login") || cleanUrl.contains("/signup")) {
                binding.tvLoginStatus.text = "Please enter your Pinterest credentials and log in below..."
            } else {
                binding.tvLoginStatus.text = "Please log in to your account. Session will be captured once logged in."
            }
        } else {
            if (cleanUrl.contains("/accounts/login") || cleanUrl.contains("/accounts/emailsignup")) {
                binding.tvLoginStatus.text = "Please enter your Instagram credentials and log in below..."
            } else {
                binding.tvLoginStatus.text = "Please log in to your account. Session will be captured once logged in."
            }
        }
    }

    private fun handleManualGrabClick() {
        val currentUrl = binding.webView.url
        val cookies = getPlatformCookies()

        if (!isUserLoggedIn(currentUrl, cookies)) {
            Toast.makeText(
                this,
                "Please complete login in the WebView first! You are not logged in yet.",
                Toast.LENGTH_LONG
            ).show()
            binding.tvLoginStatus.text = "Not logged in yet. Please log into your account first."
            return
        }

        checkAndCaptureSession(force = true)
    }

    private fun checkAndCaptureSession(force: Boolean) {
        if (isSessionCaptured || isCapturing) return

        val cookies = getPlatformCookies()
        val currentUrl = binding.webView.url

        if (!isUserLoggedIn(currentUrl, cookies) && !force) {
            return
        }

        if (platform == PLATFORM_PINTEREST) {
            capturePinterestSession(cookies)
        } else {
            captureInstagramSession(cookies)
        }
    }

    private fun capturePinterestSession(cookies: String) {
        isCapturing = true
        binding.tvLoginStatus.text = "Logged in! Grabbing account details..."
        binding.btnCaptureSession.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            var username = reLoginUsername ?: ""
            var displayName = ""
            var profilePic = ""
            val csrfToken = extractCookieValue(cookies, "csrftoken")

            try {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("https://www.pinterest.com/resource/UserSettingsResource/get/")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookies)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("x-csrftoken", csrfToken)
                    .header("x-pinterest-appstate", "active")
                    .header("referer", "https://www.pinterest.com/")
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val root = JSONObject(body)
                val userObj = root.optJSONObject("resource_response")?.optJSONObject("data")
                if (userObj != null) {
                    val fetchedUser = userObj.optString("username", "")
                    if (fetchedUser.isNotBlank()) {
                        username = fetchedUser
                    }
                    displayName = userObj.optString("full_name", "")
                    profilePic = userObj.optString("image_large_url", "")
                }
            } catch (e: Exception) {
                // Secondary fallback: inspect URL for username
            }

            // Fallback: Check if current WebView URL is on a user profile (/username/)
            if (username.isBlank()) {
                val currentUrl = withContext(Dispatchers.Main) { binding.webView.url ?: "" }
                val uri = Uri.parse(currentUrl)
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    val seg = pathSegments[0]
                    val systemWords = setOf("login", "signup", "today", "ideas", "settings", "notifications", "business", "about", "pin", "search")
                    if (!systemWords.contains(seg.lowercase()) && seg.matches(Regex("^[a-zA-Z0-9_.-]+$"))) {
                        username = seg
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (username.isNotBlank()) {
                    savePinterestAccountAndFinish(username, displayName, profilePic, cookies, csrfToken)
                } else {
                    // Never save a random fake username! Prompt user to enter their username for this session
                    promptUserForUsername(cookies, csrfToken)
                }
            }
        }
    }

    private fun captureInstagramSession(cookies: String) {
        isCapturing = true
        binding.tvLoginStatus.text = "Logged in! Grabbing account details..."
        binding.btnCaptureSession.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val dsUserId = extractCookieValue(cookies, "ds_user_id")
            val csrfToken = extractCookieValue(cookies, "csrftoken")
            var username = reLoginUsername ?: ""
            var displayName = ""
            var profilePic = ""

            try {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookies)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-CSRFToken", csrfToken)
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val root = JSONObject(body)
                val user = root.optJSONObject("user")
                if (user != null) {
                    val fetchedUser = user.optString("username", "")
                    if (fetchedUser.isNotBlank()) {
                        username = fetchedUser
                    }
                    displayName = user.optString("full_name", "")
                    profilePic = user.optString("profile_pic_url", "")
                }
            } catch (e: Exception) {
                // Ignore and try fallback
            }

            // Fallback 1: check user info API by ds_user_id
            if (username.isBlank() && dsUserId.isNotBlank()) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url("https://i.instagram.com/api/v1/users/$dsUserId/info/")
                        .header("User-Agent", USER_AGENT)
                        .header("Cookie", cookies)
                        .header("X-IG-App-ID", "936619743392459")
                        .header("X-CSRFToken", csrfToken)
                        .build()

                    val res = client.newCall(req).execute()
                    val body = res.body?.string() ?: ""
                    val root = JSONObject(body)
                    val user = root.optJSONObject("user")
                    if (user != null) {
                        val fetchedUser = user.optString("username", "")
                        if (fetchedUser.isNotBlank()) {
                            username = fetchedUser
                        }
                        displayName = user.optString("full_name", "")
                        profilePic = user.optString("profile_pic_url", "")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Fallback 2: Check current URL path
            if (username.isBlank()) {
                val currentUrl = withContext(Dispatchers.Main) { binding.webView.url ?: "" }
                val uri = Uri.parse(currentUrl)
                val pathSegments = uri.pathSegments
                if (pathSegments.isNotEmpty()) {
                    val seg = pathSegments[0]
                    val systemWords = setOf("accounts", "explore", "reels", "direct", "stories", "tv", "about")
                    if (!systemWords.contains(seg.lowercase()) && seg.matches(Regex("^[a-zA-Z0-9_.-]+$"))) {
                        username = seg
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (username.isNotBlank()) {
                    saveInstagramAccountAndFinish(username, displayName, profilePic, cookies, csrfToken, dsUserId)
                } else {
                    // Never save a random fake username! Prompt user to enter their username for this session
                    promptUserForUsername(cookies, csrfToken)
                }
            }
        }
    }

    private fun promptUserForUsername(detectedCookies: String, detectedCsrf: String) {
        val input = EditText(this@WebLoginActivity).apply {
            hint = if (platform == PLATFORM_PINTEREST) "Enter your Pinterest username" else "Enter your Instagram username"
            setSingleLine(true)
            if (reLoginUsername != null) {
                setText(reLoginUsername)
            }
        }

        AlertDialog.Builder(this@WebLoginActivity)
            .setTitle("Confirm Account Username")
            .setMessage("Logged in successfully! Please confirm your account username to link with this session:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save Account") { _, _ ->
                val entered = input.text.toString().trim().removePrefix("@")
                if (entered.isNotEmpty()) {
                    if (platform == PLATFORM_PINTEREST) {
                        savePinterestAccountAndFinish(entered, entered, "", detectedCookies, detectedCsrf)
                    } else {
                        val dsUserId = extractCookieValue(detectedCookies, "ds_user_id")
                        saveInstagramAccountAndFinish(entered, entered, "", detectedCookies, detectedCsrf, dsUserId)
                    }
                } else {
                    isCapturing = false
                    binding.btnCaptureSession.isEnabled = true
                    Toast.makeText(this@WebLoginActivity, "Username cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                isCapturing = false
                binding.btnCaptureSession.isEnabled = true
                binding.tvLoginStatus.text = "Please log in to your account below."
            }
            .show()
    }

    private fun savePinterestAccountAndFinish(
        username: String,
        displayName: String,
        profilePic: String,
        cookies: String,
        csrfToken: String
    ) {
        isSessionCaptured = true
        lifecycleScope.launch(Dispatchers.IO) {
            val account = PinterestAccount(
                username = username,
                displayName = displayName.ifEmpty { username },
                profilePicUrl = profilePic,
                cookies = cookies,
                csrfToken = csrfToken,
                status = "ACTIVE",
                lastCheckedTime = System.currentTimeMillis()
            )

            repository.savePinterestAccount(account)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebLoginActivity,
                    "Pinterest account '@$username' successfully added!",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun saveInstagramAccountAndFinish(
        username: String,
        displayName: String,
        profilePic: String,
        cookies: String,
        csrfToken: String,
        dsUserId: String
    ) {
        isSessionCaptured = true
        lifecycleScope.launch(Dispatchers.IO) {
            val account = InstagramAccount(
                username = username,
                displayName = displayName.ifEmpty { username },
                profilePicUrl = profilePic,
                cookies = cookies,
                csrfToken = csrfToken,
                dsUserId = dsUserId,
                status = "ACTIVE",
                lastCheckedTime = System.currentTimeMillis()
            )

            repository.saveInstagramAccount(account)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebLoginActivity,
                    "Instagram account '@$username' successfully added!",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun extractCookieValue(cookies: String, key: String): String {
        for (part in cookies.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$key=")) {
                return trimmed.substringAfter("$key=")
            }
        }
        return ""
    }
}
