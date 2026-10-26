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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.repository.AppRepository
import com.pintoinsta.app.databinding.ActivityWebLoginBinding
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    }

    private lateinit var binding: ActivityWebLoginBinding
    private lateinit var repository: AppRepository
    private var platform = PLATFORM_PINTEREST
    private var reLoginUsername: String? = null
    private var isSessionCaptured = false
    private var isCapturing = false
    private var cleanUserAgent = ""

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
        setupBackNavigation()

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

    /**
     * Resolves Google's "This browser or app may not be secure" error by:
     * 1. Removing '; wv' (embedded WebView identifier) and 'Version/4.0' from the user-agent
     * 2. Preserving the exact device model, Android OS version, and Chrome version
     * 3. Matching Android platform fingerprints so Google detects a genuine Chrome Mobile browser
     */
    private fun resolveCleanMobileUserAgent(settings: WebSettings): String {
        val defaultUa = try {
            WebSettings.getDefaultUserAgent(this)
        } catch (e: Exception) {
            settings.userAgentString
        }

        val clean = defaultUa
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .replace(";wv", "")

        return if (clean.contains("Chrome/")) {
            clean
        } else {
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        cleanUserAgent = resolveCleanMobileUserAgent(settings)
        settings.userAgentString = cleanUserAgent

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Required for Google OAuth and 3rd-party social logins:
        // Enables window.open popups used by Google Sign-In and Facebook Sign-In buttons
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Suppress 'X-Requested-With' header to prevent Google from identifying the client as an embedded app
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        } catch (e: Throwable) {
            // Feature not available on older WebView engines; handled by clean user-agent
        }

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

            // Handle OAuth popup windows (e.g. "Continue with Google" popup) inside a dedicated popup Dialog
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (resultMsg == null) return false

                val context = this@WebLoginActivity
                val popupWebView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = cleanUserAgent
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(popupWebView, true)

                val popupDialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    setContentView(popupWebView)
                    setOnCancelListener {
                        try {
                            popupWebView.destroy()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        try {
                            popupDialog.dismiss()
                            window?.destroy()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: ""
                        if (url.contains("pinterest.com") || url.contains("instagram.com")) {
                            try {
                                popupDialog.dismiss()
                                popupWebView.destroy()
                            } catch (e: Exception) {
                                // Ignore
                            }
                            cookieManager.flush()
                            binding.webView.loadUrl(url)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && (url.contains("pinterest.com") || url.contains("instagram.com"))) {
                            val cookies = getPlatformCookies()
                            if (isUserLoggedIn(url, cookies)) {
                                try {
                                    popupDialog.dismiss()
                                    popupWebView.destroy()
                                } catch (e: Exception) {
                                    // Ignore
                                }
                                cookieManager.flush()
                                checkAndCaptureSession(force = false)
                            }
                        }
                    }
                }

                val transport = resultMsg.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = popupWebView
                    resultMsg.sendToTarget()
                    try {
                        popupDialog.show()
                    } catch (e: Exception) {
                        return false
                    }
                    return true
                }
                return false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Ensure the clean mobile User-Agent is retained across Google auth domains
                if (url != null && (url.contains("accounts.google.com") || url.contains("google.com/signin"))) {
                    view?.settings?.userAgentString = cleanUserAgent
                }
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
                // Allow Google OAuth and all redirect URLs to execute smoothly in the webview
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
            // Must NOT be on login, signup, or Google auth URL
            val onAuthPage = cleanUrl.contains("/login") || cleanUrl.contains("/signup") || cleanUrl.contains("/register") || cleanUrl.contains("accounts.google.com")
            // Pinterest logged-in session MUST have _auth=1 (guests have _auth=0) and _pinterest_sess
            val hasAuth = cookies.contains("_auth=1")
            val hasSess = cookies.contains("_pinterest_sess=")
            !onAuthPage && hasAuth && hasSess
        } else {
            // Instagram: Must NOT be on login or Google auth URL
            val onAuthPage = cleanUrl.contains("/accounts/login") || cleanUrl.contains("/accounts/emailsignup") || cleanUrl.contains("/accounts/onetap") || cleanUrl.contains("accounts.google.com")
            val hasSessionId = cookies.contains("sessionid=")
            val dsUserId = extractCookieValue(cookies, "ds_user_id")
            !onAuthPage && hasSessionId && dsUserId.isNotEmpty() && dsUserId != "0"
        }
    }

    private fun updateStatusMessage(url: String?) {
        val cleanUrl = url?.lowercase() ?: ""
        if (cleanUrl.contains("accounts.google.com")) {
            binding.tvLoginStatus.text = "Google Sign-In: Enter your Google credentials..."
            return
        }

        if (platform == PLATFORM_PINTEREST) {
            if (cleanUrl.contains("/login") || cleanUrl.contains("/signup")) {
                binding.tvLoginStatus.text = "Please log in with email/password or Continue with Google below..."
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

            // Method 1: Fetch UserSettingsResource API with query parameters
            try {
                val client = OkHttpClient()
                val apiUrl = "https://www.pinterest.com/resource/UserSettingsResource/get/?source_url=%2Fsettings%2F&data=%7B%22options%22%3A%7B%7D%2C%22context%22%3A%7B%7D%7D"
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", cleanUserAgent)
                    .header("Cookie", cookies)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("x-csrftoken", csrfToken)
                    .header("x-pinterest-appstate", "active")
                    .header("Referer", "https://www.pinterest.com/settings/")
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val root = JSONObject(body)
                val userObj = root.optJSONObject("resource_response")?.optJSONObject("data")
                if (userObj != null) {
                    val fetchedUser = userObj.optString("username", "").trim()
                    if (fetchedUser.isNotBlank()) {
                        username = fetchedUser
                    }
                    displayName = userObj.optString("full_name", "").trim()
                    profilePic = userObj.optString("image_large_url", "").trim()
                }
            } catch (e: Exception) {
                // Try fallback
            }

            // Method 2: Inspect Pinterest settings HTML page
            if (username.isBlank()) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url("https://www.pinterest.com/settings/")
                        .header("User-Agent", cleanUserAgent)
                        .header("Cookie", cookies)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()

                    val res = client.newCall(req).execute()
                    val html = res.body?.string() ?: ""

                    val userMatch = Regex("\"username\"\\s*:\\s*\"([a-zA-Z0-9_.-]+)\"").find(html)
                    if (userMatch != null && userMatch.groupValues.size > 1) {
                        username = userMatch.groupValues[1]
                    }

                    if (displayName.isBlank()) {
                        val nameMatch = Regex("\"full_name\"\\s*:\\s*\"([^\"]+)\"").find(html)
                        if (nameMatch != null && nameMatch.groupValues.size > 1) {
                            displayName = nameMatch.groupValues[1]
                        }
                    }
                } catch (e: Exception) {
                    // Try fallback
                }
            }

            // Method 3: Evaluate JS in WebView to extract username from DOM or __INITIAL_DATA__
            if (username.isBlank()) {
                val jsUser = evaluateJs(
                    """
                    (function() {
                        try {
                            if (window.__INITIAL_DATA__ && window.__INITIAL_DATA__.user && window.__INITIAL_DATA__.user.username) {
                                return window.__INITIAL_DATA__.user.username;
                            }
                            var el = document.querySelector('header a[href^="/"][aria-label*="Profile"], header a[data-test-id*="profile"], a[href^="/"][aria-label*="profile"]');
                            if (el) {
                                var href = el.getAttribute('href') || '';
                                var m = href.match(/^\/([a-zA-Z0-9_.-]+)/);
                                if (m && m[1]) return m[1];
                            }
                        } catch(e) {}
                        return '';
                    })()
                    """.trimIndent()
                )
                if (jsUser.isNotBlank() && jsUser != "null") {
                    username = jsUser
                }
            }

            // Method 4: Check current WebView URL path
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

            // Guaranteed fallback: never ask user or fail
            if (username.isBlank()) {
                username = "pinterest_user_${System.currentTimeMillis() % 100000}"
            }

            withContext(Dispatchers.Main) {
                savePinterestAccountAndFinish(username, displayName, profilePic, cookies, csrfToken)
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

            // Method 1: Current user API
            try {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                    .header("User-Agent", cleanUserAgent)
                    .header("Cookie", cookies)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-CSRFToken", csrfToken)
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val root = JSONObject(body)
                val user = root.optJSONObject("user")
                if (user != null) {
                    val fetchedUser = user.optString("username", "").trim()
                    if (fetchedUser.isNotBlank()) {
                        username = fetchedUser
                    }
                    displayName = user.optString("full_name", "").trim()
                    profilePic = user.optString("profile_pic_url", "").trim()
                }
            } catch (e: Exception) {
                // Try fallback
            }

            // Method 2: Check user info API by ds_user_id
            if (username.isBlank() && dsUserId.isNotBlank()) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url("https://i.instagram.com/api/v1/users/$dsUserId/info/")
                        .header("User-Agent", cleanUserAgent)
                        .header("Cookie", cookies)
                        .header("X-IG-App-ID", "936619743392459")
                        .header("X-CSRFToken", csrfToken)
                        .build()

                    val res = client.newCall(req).execute()
                    val body = res.body?.string() ?: ""
                    val root = JSONObject(body)
                    val user = root.optJSONObject("user")
                    if (user != null) {
                        val fetchedUser = user.optString("username", "").trim()
                        if (fetchedUser.isNotBlank()) {
                            username = fetchedUser
                        }
                        displayName = user.optString("full_name", "").trim()
                        profilePic = user.optString("profile_pic_url", "").trim()
                    }
                } catch (e: Exception) {
                    // Try fallback
                }
            }

            // Method 3: Evaluate JS in WebView
            if (username.isBlank()) {
                val jsUser = evaluateJs(
                    """
                    (function() {
                        try {
                            var links = document.querySelectorAll('a[href^="/"]');
                            for (var i = 0; i < links.length; i++) {
                                var h = links[i].getAttribute('href') || '';
                                var m = h.match(/^\/([a-zA-Z0-9_.-]+)\/?$/);
                                if (m && !['accounts','explore','reels','direct','stories','tv','about','p'].includes(m[1].toLowerCase())) {
                                    return m[1];
                                }
                            }
                        } catch(e) {}
                        return '';
                    })()
                    """.trimIndent()
                )
                if (jsUser.isNotBlank() && jsUser != "null") {
                    username = jsUser
                }
            }

            // Method 4: Check current URL path
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

            // Guaranteed fallback: never ask user or fail
            if (username.isBlank()) {
                username = if (dsUserId.isNotBlank()) "insta_user_$dsUserId" else "insta_user_${System.currentTimeMillis() % 100000}"
            }

            withContext(Dispatchers.Main) {
                saveInstagramAccountAndFinish(username, displayName, profilePic, cookies, csrfToken, dsUserId)
            }
        }
    }

    private suspend fun evaluateJs(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                binding.webView.evaluateJavascript(script) { result ->
                    val clean = result?.trim('"', ' ', '\\') ?: ""
                    if (clean == "null" || clean == "undefined") {
                        if (cont.isActive) cont.resume("")
                    } else {
                        if (cont.isActive) cont.resume(clean)
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
            }
        }
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
