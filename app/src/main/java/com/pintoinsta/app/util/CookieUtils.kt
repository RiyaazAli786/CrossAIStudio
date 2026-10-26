package com.pintoinsta.app.util

import android.webkit.CookieManager

object CookieUtils {

    /**
     * Extracts a single cookie value from a semicolon-separated cookie header string.
     * Case-insensitive cookie name matching.
     */
    fun extractCookieValue(cookies: String, key: String): String {
        if (cookies.isBlank()) return ""
        for (part in cookies.split(";")) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                if (k.equals(key, ignoreCase = true)) {
                    return trimmed.substring(eq + 1).trim()
                }
            }
        }
        return ""
    }

    /**
     * Retrieves and merges all cookies from CookieManager across all relevant Instagram domains.
     */
    fun getLiveInstagramCookies(): String {
        return try {
            val cm = CookieManager.getInstance()
            val domains = listOf(
                "https://www.instagram.com",
                "https://instagram.com",
                "https://i.instagram.com"
            )
            mergeCookies(domains.mapNotNull { cm.getCookie(it) })
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Retrieves and merges all cookies from CookieManager across all relevant Pinterest domains.
     */
    fun getLivePinterestCookies(): String {
        return try {
            val cm = CookieManager.getInstance()
            val domains = listOf(
                "https://www.pinterest.com",
                "https://pinterest.com",
                "https://in.pinterest.com"
            )
            mergeCookies(domains.mapNotNull { cm.getCookie(it) })
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Merges multiple cookie strings, keeping the most complete set of key-value pairs.
     */
    fun mergeCookies(cookieStrings: List<String>): String {
        val map = linkedMapOf<String, String>()
        for (str in cookieStrings) {
            if (str.isBlank()) continue
            for (part in str.split(";")) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) {
                        map[k] = v
                    }
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Injects cookies into CookieManager across all Pinterest subdomains (.pinterest.com, www, in, api).
     */
    fun injectPinterestCookies(cookies: String) {
        if (cookies.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val domains = listOf(
                "https://www.pinterest.com",
                "https://pinterest.com",
                "https://in.pinterest.com",
                "https://api.pinterest.com"
            )
            for (part in cookies.split(";")) {
                val clean = part.trim()
                if (clean.isEmpty()) continue
                for (d in domains) {
                    cm.setCookie(d, clean)
                    cm.setCookie(d, "$clean; Domain=.pinterest.com; Path=/; Secure")
                }
            }
            cm.flush()
        } catch (_: Exception) {}
    }

    /**
     * Injects cookies into CookieManager across all Instagram subdomains (.instagram.com, www, i).
     */
    fun injectInstagramCookies(cookies: String) {
        if (cookies.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val domains = listOf(
                "https://www.instagram.com",
                "https://instagram.com",
                "https://i.instagram.com"
            )
            for (part in cookies.split(";")) {
                val clean = part.trim()
                if (clean.isEmpty()) continue
                for (d in domains) {
                    cm.setCookie(d, clean)
                    cm.setCookie(d, "$clean; Domain=.instagram.com; Path=/; Secure")
                }
            }
            cm.flush()
        } catch (_: Exception) {}
    }

    /**
     * Injects cookies into CookieManager for the specified domain URL with domain broadcasting.
     */
    fun injectCookies(url: String, cookies: String) {
        if (cookies.isBlank()) return
        when {
            url.contains("pinterest") -> injectPinterestCookies(cookies)
            url.contains("instagram") -> injectInstagramCookies(cookies)
            else -> {
                try {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    for (part in cookies.split(";")) {
                        val clean = part.trim()
                        if (clean.isNotEmpty()) {
                            cm.setCookie(url, clean)
                            cm.setCookie(url, "$clean; Path=/")
                        }
                    }
                    cm.flush()
                } catch (_: Exception) {}
            }
        }
    }
}
