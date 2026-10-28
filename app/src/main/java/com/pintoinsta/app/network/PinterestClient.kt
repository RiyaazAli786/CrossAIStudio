package com.pintoinsta.app.network

import android.util.Log
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.util.InstagramCaptionUtils
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PinterestClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "PinterestClient"
        private const val BASE_URL = "https://in.pinterest.com/resource/BaseSearchResource/get/"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    data class SearchResult(
        val pins: List<SavedPin>,
        val bookmark: String?,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Requirement 4: Search using template from PinteresetSearch.txt
     */
    fun searchInitial(
        account: PinterestAccount?,
        keyword: String,
        scope: String = "pins"
    ): SearchResult {
        return try {
            val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
            val sourceUrl = if (scope == "videos") {
                "/search/videos/?filter_location=1&q=$encodedQuery&rs=content_type_filter"
            } else {
                "/search/pins/?q=$encodedQuery&rs=typed"
            }
            val encodedSourceUrl = URLEncoder.encode(sourceUrl, "UTF-8")

            val optionsJson = JSONObject().apply {
                put("query", keyword)
                put("scope", scope)
                put("appliedProductFilters", "---")
                put("domains", JSONObject.NULL)
                put("user", JSONObject.NULL)
                put("seoDrawerEnabled", false)
                put("applied_unified_filters", JSONObject.NULL)
                put("auto_correction_disabled", false)
                put("filter_genai", false)
                put("journey_depth", JSONObject.NULL)
                put("source_id", JSONObject.NULL)
                put("source_module_id", JSONObject.NULL)
                put("source_url", sourceUrl)
                put("static_feed", false)
                put("selected_one_bar_modules", JSONObject.NULL)
                put("query_pin_sigs", JSONObject.NULL)
                put("page_size", JSONObject.NULL)
                put("gated", JSONObject.NULL)
                put("price_max", JSONObject.NULL)
                put("price_min", JSONObject.NULL)
                put("query_image_pins", JSONObject.NULL)
                put("request_params", JSONObject.NULL)
                put("top_pin_ids", JSONObject.NULL)
                put("article", JSONObject.NULL)
                put("corpus", JSONObject.NULL)
                put("filters", JSONObject.NULL)
                put("rs", if (scope == "videos") "content_type_filter" else "typed")
            }

            val dataJson = JSONObject().apply {
                put("options", optionsJson)
                put("context", JSONObject())
            }

            val encodedData = URLEncoder.encode(dataJson.toString(), "UTF-8")
            val fullUrl = "$BASE_URL?source_url=$encodedSourceUrl&data=$encodedData&_=${System.currentTimeMillis()}"

            val cookies = account?.cookies?.takeIf { it.isNotBlank() } ?: ""
            val csrf = if (cookies.isNotEmpty()) {
                account?.csrfToken?.ifEmpty { extractCsrfFromCookies(cookies) } ?: extractCsrfFromCookies(cookies)
            } else ""

            val requestBuilder = Request.Builder()
                .url(fullUrl)
                .get()
                .header("Host", "in.pinterest.com")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                .header("x-requested-with", "XMLHttpRequest")
                .header("x-pinterest-appstate", "active")
                .header("x-pinterest-pws-handler", "www/search/[scope].js")
                .header("x-app-version", "472496f")
                .header("Referer", "https://in.pinterest.com/")
                .header("Origin", "https://in.pinterest.com")

            if (cookies.isNotBlank()) {
                requestBuilder.header("Cookie", cookies)
            }
            if (csrf.isNotBlank()) {
                requestBuilder.header("x-csrftoken", csrf)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Search failed HTTP ${response.code}: $body")
                return SearchResult(emptyList(), null, false, "HTTP ${response.code}")
            }

            parseSearchResponse(body, account?.id ?: 0L, keyword)
        } catch (e: Exception) {
            Log.e(TAG, "searchInitial error", e)
            SearchResult(emptyList(), null, false, e.message)
        }
    }

    /**
     * Requirement 4: Pagination Search using template from PinterestSearchWithPagination.txt
     */
    fun searchWithPagination(
        account: PinterestAccount?,
        keyword: String,
        bookmark: String,
        scope: String = "pins"
    ): SearchResult {
        return try {
            val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
            val sourceUrl = if (scope == "videos") {
                "/search/videos/?filter_location=1&q=$encodedQuery&rs=content_type_filter"
            } else {
                "/search/pins/?q=$encodedQuery&rs=typed"
            }

            val optionsJson = JSONObject().apply {
                put("query", keyword)
                put("scope", scope)
                put("appliedProductFilters", "---")
                put("domains", JSONObject.NULL)
                put("user", JSONObject.NULL)
                put("seoDrawerEnabled", false)
                put("applied_unified_filters", JSONObject.NULL)
                put("auto_correction_disabled", false)
                put("filter_genai", false)
                put("journey_depth", JSONObject.NULL)
                put("source_id", JSONObject.NULL)
                put("source_module_id", JSONObject.NULL)
                put("source_url", sourceUrl)
                put("static_feed", false)
                put("selected_one_bar_modules", JSONObject.NULL)
                put("query_pin_sigs", JSONObject.NULL)
                put("page_size", JSONObject.NULL)
                put("gated", JSONObject.NULL)
                put("price_max", JSONObject.NULL)
                put("price_min", JSONObject.NULL)
                put("query_image_pins", JSONObject.NULL)
                put("request_params", JSONObject.NULL)
                put("top_pin_ids", JSONObject.NULL)
                put("article", JSONObject.NULL)
                put("corpus", JSONObject.NULL)
                put("filters", JSONObject.NULL)
                put("rs", if (scope == "videos") "content_type_filter" else "typed")
                put("bookmarks", org.json.JSONArray().put(bookmark))
            }

            val dataJson = JSONObject().apply {
                put("options", optionsJson)
                put("context", JSONObject())
            }

            val formBody = FormBody.Builder()
                .add("source_url", sourceUrl)
                .add("data", dataJson.toString())
                .build()

            val cookies = account?.cookies?.takeIf { it.isNotBlank() } ?: ""
            val csrf = if (cookies.isNotEmpty()) {
                account?.csrfToken?.ifEmpty { extractCsrfFromCookies(cookies) } ?: extractCsrfFromCookies(cookies)
            } else ""

            val requestBuilder = Request.Builder()
                .url(BASE_URL)
                .post(formBody)
                .header("Host", "in.pinterest.com")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                .header("x-requested-with", "XMLHttpRequest")
                .header("x-pinterest-appstate", "active")
                .header("x-pinterest-pws-handler", "www/search/[scope].js")
                .header("x-app-version", "472496f")
                .header("Referer", "https://in.pinterest.com/")
                .header("Origin", "https://in.pinterest.com")

            if (cookies.isNotBlank()) {
                requestBuilder.header("Cookie", cookies)
            }
            if (csrf.isNotBlank()) {
                requestBuilder.header("x-csrftoken", csrf)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Pagination search failed HTTP ${response.code}: $body")
                return SearchResult(emptyList(), null, false, "HTTP ${response.code}")
            }

            parseSearchResponse(body, account?.id ?: 0L, keyword)
        } catch (e: Exception) {
            Log.e(TAG, "searchWithPagination error", e)
            SearchResult(emptyList(), null, false, e.message)
        }
    }

    /**
     * Requirement 6: Check Pinterest account session status
     */
    fun checkAccountStatus(account: PinterestAccount): Pair<Boolean, String> {
        return try {
            var cookies = account.cookies
            if (!cookies.contains("_pinterest_sess=")) {
                return Pair(false, "Session Expired (No Session Cookie)")
            }
            if (!cookies.contains("_auth=")) {
                cookies = "$cookies; _auth=1"
            } else if (cookies.contains("_auth=0")) {
                cookies = cookies.replace("_auth=0", "_auth=1")
            }

            val csrf = account.csrfToken.ifEmpty { extractCsrfFromCookies(cookies) }

            // Method 1: Check UserSettingsResource API with valid query parameters and CSRF
            try {
                val apiUrl = "https://www.pinterest.com/resource/UserSettingsResource/get/?source_url=%2Fsettings%2F&data=%7B%22options%22%3A%7B%7D%2C%22context%22%3A%7B%7D%7D"
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .get()
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("x-csrftoken", csrf)
                    .header("x-pinterest-appstate", "active")
                    .header("Referer", "https://www.pinterest.com/settings/")
                    .header("Cookie", cookies)
                    .build()

                val apiRes = client.newCall(apiReq).execute()
                val apiBody = apiRes.body?.string() ?: ""
                if (apiRes.isSuccessful && (apiBody.contains("\"status\":\"success\"") || apiBody.contains("\"status\": \"success\"") || apiBody.contains(account.username))) {
                    return Pair(true, "Active")
                }
            } catch (_: Exception) {
                // Fallback to Method 2
            }

            // Method 2: Request settings web page and verify no redirect to /login
            val webReq = Request.Builder()
                .url("https://www.pinterest.com/settings/")
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cookie", cookies)
                .build()

            val webRes = client.newCall(webReq).execute()
            val finalUrl = webRes.request.url.toString()
            val webBody = webRes.body?.string() ?: ""

            if (webRes.isSuccessful && !finalUrl.contains("/login") && !webBody.contains("name=\"login_user_form\"")) {
                Pair(true, "Active")
            } else {
                Pair(false, "Session Expired (Logged Out)")
            }
        } catch (e: Exception) {
            Pair(false, "Network error: ${e.message}")
        }
    }

    private fun parseSearchResponse(
        jsonString: String,
        accountId: Long,
        keyword: String
    ): SearchResult {
        val pinsList = mutableListOf<SavedPin>()
        var nextBookmark: String? = null

        try {
            val root = JSONObject(jsonString)
            val resourceResponse = root.optJSONObject("resource_response") ?: return SearchResult(emptyList(), null, false, "Invalid JSON structure")

            nextBookmark = resourceResponse.optString("bookmark", "").takeIf { it.isNotBlank() && it != "-end-" }

            val dataObj = resourceResponse.optJSONObject("data")
            val resultsArr = dataObj?.optJSONArray("results") ?: org.json.JSONArray()

            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.optJSONObject(i) ?: continue

                // Check pin id (must be present)
                val pinId = item.optString("id").takeIf { it.isNotBlank() } ?: continue

                // Title: if empty, grid_title (clean HTML entities & normalize emojis)
                val rawTitle = item.optString("title").ifEmpty { item.optString("grid_title") }.trim()
                val title = InstagramCaptionUtils.cleanAndFormatCaption(rawTitle)
                val rawDescription = item.optString("description").trim()
                val description = InstagramCaptionUtils.cleanAndFormatCaption(rawDescription)

                // Media URL extraction: check videos first, then images
                var mediaUrl = ""
                var mediaType = "IMAGE"

                val videosObj = item.optJSONObject("videos")
                if (videosObj != null) {
                    val videoListObj = videosObj.optJSONObject("video_list")
                    if (videoListObj != null) {
                        // Look for direct MP4 first: V_720P, V_EXP7, V_EXP6, V_EXP5, V_EXP4, V_EXP3
                        val mp4Keys = listOf("V_720P", "V_EXP7", "V_EXP6", "V_EXP5", "V_EXP4", "V_EXP3")
                        for (key in mp4Keys) {
                            val vObj = videoListObj.optJSONObject(key)
                            val vUrl = vObj?.optString("url") ?: ""
                            if (vUrl.isNotBlank() && vUrl.contains(".mp4")) {
                                mediaUrl = vUrl
                                mediaType = "VIDEO"
                                break
                            }
                        }

                        // If not found in primary keys, check any key with .mp4
                        if (mediaUrl.isEmpty()) {
                            val iter = videoListObj.keys()
                            while (iter.hasNext()) {
                                val key = iter.next()
                                val vUrl = videoListObj.optJSONObject(key)?.optString("url") ?: ""
                                if (vUrl.isNotBlank() && vUrl.contains(".mp4")) {
                                    mediaUrl = vUrl
                                    mediaType = "VIDEO"
                                    break
                                }
                            }
                        }

                        // If still empty, check HLS stream
                        if (mediaUrl.isEmpty()) {
                            val hlsKeys = listOf("V_HLSV4", "V_HLSV3_MOBILE")
                            for (key in hlsKeys) {
                                val vObj = videoListObj.optJSONObject(key)
                                val vUrl = vObj?.optString("url") ?: ""
                                if (vUrl.isNotBlank()) {
                                    mediaUrl = vUrl
                                    mediaType = "VIDEO"
                                    break
                                }
                            }
                        }
                    }
                }

                // If mediaUrl is empty or is HLS, also grab highest res original image as fallback or primary
                val imagesObj = item.optJSONObject("images")
                var origImageUrl = ""
                if (imagesObj != null) {
                    val orig = imagesObj.optJSONObject("orig")
                    val img736 = imagesObj.optJSONObject("736x")
                    val img474 = imagesObj.optJSONObject("474x")
                    origImageUrl = orig?.optString("url")
                        ?: img736?.optString("url")
                        ?: img474?.optString("url")
                        ?: ""
                }

                if (mediaUrl.isEmpty()) {
                    mediaUrl = origImageUrl
                    mediaType = "IMAGE"
                }

                if (mediaUrl.isNotEmpty()) {
                    pinsList.add(
                        SavedPin(
                            pinId = pinId,
                            pinterestAccountId = accountId,
                            searchTerm = keyword,
                            title = title,
                            description = description,
                            mediaUrl = mediaUrl,
                            mediaType = mediaType,
                            isPosted = false,
                            postedAt = null,
                            rawJson = item.toString()
                        )
                    )
                }
            }

            return SearchResult(pinsList, nextBookmark, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Pinterest search response", e)
            return SearchResult(emptyList(), null, false, e.message)
        }
    }

    private fun extractCsrfFromCookies(cookies: String): String {
        val parts = cookies.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("csrftoken=")) {
                return trimmed.substringAfter("csrftoken=")
            }
        }
        return ""
    }
}
