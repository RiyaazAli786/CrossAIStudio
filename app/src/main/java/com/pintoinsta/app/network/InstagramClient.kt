package com.pintoinsta.app.network

import android.graphics.BitmapFactory
import android.util.Log
import com.pintoinsta.app.data.model.InstagramAccount
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Implements Instagram posting engine based on GramDominator (D:\SocialX\gramdominator-library)
 */
class InstagramClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "InstagramClient"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val IG_APP_ID = "936619743392459"
    }

    /**
     * Requirement 6: Check Instagram account status
     */
    fun checkAccountStatus(account: InstagramAccount): Pair<Boolean, String> {
        return try {
            val csrf = account.csrfToken.ifEmpty { extractCsrfFromCookies(account.cookies) }

            // Endpoint 1: mobile API account check
            val request = Request.Builder()
                .url("https://i.instagram.com/api/v1/accounts/current_user/?edit=true")
                .get()
                .header("Host", "i.instagram.com")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-CSRFToken", csrf)
                .header("Cookie", account.cookies)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && (body.contains("\"status\":\"ok\"") || body.contains("\"user\":"))) {
                Pair(true, "Active")
            } else if (response.code == 401 || body.contains("checkpoint_required") || body.contains("login_required")) {
                Pair(false, "Session Expired / Re-login Required")
            } else {
                // Secondary check: web profile info
                checkAccountStatusWeb(account)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAccountStatus failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    private fun checkAccountStatusWeb(account: InstagramAccount): Pair<Boolean, String> {
        return try {
            val request = Request.Builder()
                .url("https://www.instagram.com/accounts/edit/")
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Cookie", account.cookies)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful && !response.request.url.toString().contains("/accounts/login/")) {
                Pair(true, "Active")
            } else {
                Pair(false, "Session Expired (Logged Out)")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * Download media (image or video) from URL into bytes
     */
    fun downloadMedia(url: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                Log.e(TAG, "Failed downloading media HTTP ${response.code} from $url")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading media from $url", e)
            null
        }
    }

    /**
     * Requirement 5 & 7: Post Photo to Instagram using GramDominator architecture
     */
    fun postPhoto(
        account: InstagramAccount,
        imageBytes: ByteArray,
        caption: String
    ): Pair<Boolean, String> {
        val uploadId = System.currentTimeMillis().toString()
        val csrf = account.csrfToken.ifEmpty { extractCsrfFromCookies(account.cookies) }

        if (csrf.isEmpty()) {
            return Pair(false, "Missing CSRF token in Instagram account cookies")
        }

        // Measure image dimensions
        val (width, height) = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(ByteArrayInputStream(imageBytes), null, options)
            val w = if (options.outWidth > 0) options.outWidth else 1080
            val h = if (options.outHeight > 0) options.outHeight else 1080
            Pair(w, h)
        } catch (e: Exception) {
            Pair(1080, 1080)
        }

        Log.d(TAG, "Starting Instagram photo upload: uploadId=$uploadId, dim=${width}x${height}, size=${imageBytes.size}")

        // Step 1: Preflight OPTIONS request (as done in GramDominator InstaFunct.cs: UploadImage)
        val uploadUrl = "https://i.instagram.com/rupload_igphoto/fb_uploader_$uploadId"
        try {
            val optionsReq = Request.Builder()
                .url(uploadUrl)
                .method("OPTIONS", null)
                .header("Host", "i.instagram.com")
                .header("Accept", "*/*")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers",
                    "content-type,x-entity-length,x-entity-name,x-entity-type,x-ig-app-id,x-instagram-ajax,x-instagram-rupload-params"
                )
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()

            client.newCall(optionsReq).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "OPTIONS preflight warning (continuing): ${e.message}")
        }

        // Step 2: Upload photo bytes via POST (GramDominator style)
        val ruploadParams = JSONObject().apply {
            put("media_type", 1)
            put("upload_id", uploadId)
            put("upload_media_height", height)
            put("upload_media_width", width)
        }.toString()

        val imageRequestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .post(imageRequestBody)
            .header("Host", "i.instagram.com")
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/")
            .header("X-Instagram-Rupload-Params", ruploadParams)
            .header("X-Instagram-AJAX", "1")
            .header("X-CSRFToken", csrf)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Entity-Type", "image/jpeg")
            .header("X-Entity-Name", "fb_uploader_$uploadId")
            .header("X-Entity-Length", imageBytes.size.toString())
            .header("Offset", "0")
            .header("Cookie", account.cookies)
            .build()

        val uploadRes = client.newCall(uploadReq).execute()
        val uploadBody = uploadRes.body?.string() ?: ""
        Log.d(TAG, "Upload response HTTP ${uploadRes.code}: $uploadBody")

        if (!uploadRes.isSuccessful || !uploadBody.contains("\"status\":\"ok\"")) {
            return Pair(false, "Photo upload failed: $uploadBody")
        }

        // Step 3: Configure post via Instagram Media Configure API (GramDominator InstaFunct.cs: ConfigureWeb)
        val configureUrl = "https://www.instagram.com/api/v1/media/configure/"
        val escapedCaption = URLEncoder.encode(caption, "UTF-8")

        val formBody = FormBody.Builder()
            .add("archive_only", "false")
            .add("caption", caption)
            .add("clips_share_preview_to_feed", "1")
            .add("disable_comments", "0")
            .add("disable_oa_reuse", "false")
            .add("igtv_share_preview_to_feed", "1")
            .add("is_meta_only_post", "0")
            .add("is_unified_video", "1")
            .add("like_and_view_counts_disabled", "0")
            .add("media_share_flow", "creation_flow")
            .add("share_to_facebook", "")
            .add("share_to_fb_destination_type", "USER")
            .add("source_type", "library")
            .add("upload_id", uploadId)
            .add("video_subtitles_enabled", "0")
            .add("geotag_enabled", "false")
            .build()

        val configureReq = Request.Builder()
            .url(configureUrl)
            .post(formBody)
            .header("Host", "www.instagram.com")
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/")
            .header("X-Instagram-AJAX", "1")
            .header("X-CSRFToken", csrf)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", account.cookies)
            .build()

        val configRes = client.newCall(configureReq).execute()
        val configBody = configRes.body?.string() ?: ""
        Log.d(TAG, "Configure response HTTP ${configRes.code}: $configBody")

        return if (configRes.isSuccessful && configBody.contains("\"status\":\"ok\"")) {
            Pair(true, "Successfully posted to Instagram! (UploadId: $uploadId)")
        } else {
            Pair(false, "Configure failed: $configBody")
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
