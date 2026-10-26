package com.pintoinsta.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.pintoinsta.app.data.model.InstagramAccount
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.pintoinsta.app.util.InstagramCaptionUtils
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
                .header("Referer", "https://www.pinterest.com/")
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
    data class PreparedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * Normalizes raw image bytes for Instagram:
     * 1. Decodes bitmap safely
     * 2. Adjusts aspect ratio within Instagram's allowed range [0.8, 1.91] by center-cropping
     * 3. Scales max width to 1080px
     * 4. Re-encodes to standard clean JPEG
     * This prevents 'Image upload transcode non-retryable failure'.
     */
    fun prepareImageForInstagram(rawBytes: ByteArray): PreparedImage? {
        return try {
            val originalBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            var bmp = originalBitmap

            val origW = bmp.width
            val origH = bmp.height
            if (origW <= 0 || origH <= 0) return null

            val currentRatio = origW.toFloat() / origH.toFloat()
            val minRatio = 0.80f // 4:5 vertical
            val maxRatio = 1.91f // 1.91:1 horizontal

            if (currentRatio < minRatio) {
                // Too tall, crop height to fit 0.8 aspect ratio
                val targetHeight = (origW / minRatio).toInt()
                val yOffset = ((origH - targetHeight) / 2).coerceAtLeast(0)
                val safeHeight = targetHeight.coerceAtMost(origH - yOffset)
                val cropped = Bitmap.createBitmap(bmp, 0, yOffset, origW, safeHeight)
                if (bmp != originalBitmap) bmp.recycle()
                bmp = cropped
            } else if (currentRatio > maxRatio) {
                // Too wide, crop width to fit 1.91 aspect ratio
                val targetWidth = (origH * maxRatio).toInt()
                val xOffset = ((origW - targetWidth) / 2).coerceAtLeast(0)
                val safeWidth = targetWidth.coerceAtMost(origW - xOffset)
                val cropped = Bitmap.createBitmap(bmp, xOffset, 0, safeWidth, origH)
                if (bmp != originalBitmap) bmp.recycle()
                bmp = cropped
            }

            // Scale down if width > 1080
            val finalW: Int
            val finalH: Int
            if (bmp.width > 1080) {
                val scale = 1080f / bmp.width.toFloat()
                finalW = 1080
                finalH = (bmp.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(bmp, finalW, finalH, true)
                if (bmp != originalBitmap) bmp.recycle()
                bmp = scaled
            } else {
                finalW = bmp.width
                finalH = bmp.height
            }

            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            val normalizedBytes = baos.toByteArray()

            if (bmp != originalBitmap) bmp.recycle()
            originalBitmap.recycle()

            PreparedImage(normalizedBytes, finalW, finalH)
        } catch (e: Exception) {
            Log.e(TAG, "prepareImageForInstagram error", e)
            null
        }
    }

    data class InstagramPostResponse(
        val success: Boolean,
        val message: String,
        val postUrl: String? = null
    )

    /**
     * Requirement 5 & 7: Post Photo to Instagram using GramDominator architecture
     */
    fun postPhoto(
        account: InstagramAccount,
        imageBytes: ByteArray,
        caption: String
    ): InstagramPostResponse {
        val uploadId = System.currentTimeMillis().toString()
        val csrf = account.csrfToken.ifEmpty { extractCsrfFromCookies(account.cookies) }

        if (csrf.isEmpty()) {
            return InstagramPostResponse(false, "Missing CSRF token in Instagram account cookies")
        }

        // Normalize image bytes to prevent Instagram transcode non-retryable failure
        val prepared = prepareImageForInstagram(imageBytes)
        if (prepared == null) {
            return InstagramPostResponse(false, "Invalid image format: Failed to transcode/decode image bytes for Instagram upload")
        }

        val uploadBytes = prepared.bytes
        val width = prepared.width
        val height = prepared.height

        Log.d(TAG, "Starting Instagram photo upload: uploadId=$uploadId, dim=${width}x${height}, size=${uploadBytes.size}")

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

        val imageRequestBody = uploadBytes.toRequestBody("image/jpeg".toMediaType())

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
            .header("X-Entity-Length", uploadBytes.size.toString())
            .header("Offset", "0")
            .header("Cookie", account.cookies)
            .build()

        val uploadRes = client.newCall(uploadReq).execute()
        val uploadBody = uploadRes.body?.string() ?: ""
        Log.d(TAG, "Upload response HTTP ${uploadRes.code}: $uploadBody")

        if (!uploadRes.isSuccessful || !uploadBody.contains("\"status\":\"ok\"")) {
            return InstagramPostResponse(false, "Photo upload failed: $uploadBody")
        }

        // Step 3: Configure post via Instagram Media Configure API (GramDominator InstaFunct.cs: ConfigureWeb)
        val configureUrl = "https://www.instagram.com/api/v1/media/configure/"

        // Clean, decode HTML entities, normalize Unicode NFC, and preserve emojis safely
        val cleanCaption = InstagramCaptionUtils.cleanAndFormatCaption(caption)
        Log.d(TAG, "Configuring post: uploadId=$uploadId, cleanCaption='$cleanCaption' (raw: '$caption')")

        // Build configure JSON object with properly handled emojis & special characters
        val jsonPayload = JSONObject().apply {
            put("archive_only", "false")
            put("caption", cleanCaption)
            put("clips_share_preview_to_feed", "1")
            put("disable_comments", "0")
            put("disable_oa_reuse", "false")
            put("igtv_share_preview_to_feed", "1")
            put("is_meta_only_post", "0")
            put("is_unified_video", "1")
            put("like_and_view_counts_disabled", "0")
            put("media_share_flow", "creation_flow")
            put("share_to_facebook", "")
            put("share_to_fb_destination_type", "USER")
            put("source_type", "library")
            put("upload_id", uploadId)
            put("video_subtitles_enabled", "0")
            put("geotag_enabled", "false")
        }

        // Primary Attempt: FormBody with explicit UTF-8 encoding and signed_body fallback
        val formBody = FormBody.Builder(Charsets.UTF_8)
            .add("archive_only", "false")
            .add("caption", cleanCaption)
            .add("signed_body", "SIGNATURE." + jsonPayload.toString())
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

        var configRes = client.newCall(configureReq).execute()
        var configBody = configRes.body?.string() ?: ""
        Log.d(TAG, "Configure (FormBody) response HTTP ${configRes.code}: $configBody")

        // Secondary Attempt: If FormBody fails or is rejected, retry with direct JSON payload (application/json; charset=utf-8)
        if (!configRes.isSuccessful || !configBody.contains("\"status\":\"ok\"")) {
            Log.w(TAG, "Form configure not ok, retrying with application/json UTF-8 payload...")
            try {
                val jsonRequestBody = jsonPayload.toString()
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val jsonReq = Request.Builder()
                    .url(configureUrl)
                    .post(jsonRequestBody)
                    .header("Host", "www.instagram.com")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Instagram-AJAX", "1")
                    .header("X-CSRFToken", csrf)
                    .header("X-IG-App-ID", IG_APP_ID)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", account.cookies)
                    .build()

                val retryRes = client.newCall(jsonReq).execute()
                val retryBody = retryRes.body?.string() ?: ""
                Log.d(TAG, "Configure (JSON) response HTTP ${retryRes.code}: $retryBody")
                if (retryRes.isSuccessful && retryBody.contains("\"status\":\"ok\"")) {
                    configRes = retryRes
                    configBody = retryBody
                }
            } catch (e: Exception) {
                Log.w(TAG, "JSON configure retry error: ${e.message}")
            }
        }

        return if (configRes.isSuccessful && configBody.contains("\"status\":\"ok\"")) {
            var postUrl: String? = null
            try {
                val json = JSONObject(configBody)
                val media = json.optJSONObject("media")
                val code = media?.optString("code")?.takeIf { it.isNotBlank() }
                    ?: json.optString("code").takeIf { it.isNotBlank() }

                if (code != null) {
                    postUrl = "https://www.instagram.com/reel/$code/"
                } else {
                    val pk = media?.optString("pk")?.takeIf { it.isNotBlank() }
                        ?: media?.optString("id")?.takeIf { it.isNotBlank() }
                    if (pk != null) {
                        postUrl = "https://www.instagram.com/p/$pk/"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing post code from configure response", e)
            }

            if (postUrl == null && account.username.isNotBlank()) {
                postUrl = "https://www.instagram.com/${account.username}/"
            }

            val msg = if (postUrl != null) {
                "Successfully posted to Instagram!\n$postUrl"
            } else {
                "Successfully posted to Instagram! (UploadId: $uploadId)"
            }
            InstagramPostResponse(true, msg, postUrl)
        } else {
            InstagramPostResponse(false, "Configure failed: $configBody")
        }
    }

    /**
     * Requirement 5 & 7: Post Video / Reel to Instagram using GramDominator architecture
     */
    fun postVideo(
        account: InstagramAccount,
        videoBytes: ByteArray,
        coverImageBytes: ByteArray?,
        caption: String,
        durationMs: Long = 15000L,
        width: Int = 720,
        height: Int = 1280
    ): InstagramPostResponse {
        val uploadId = System.currentTimeMillis().toString()
        val csrf = account.csrfToken.ifEmpty { extractCsrfFromCookies(account.cookies) }

        if (csrf.isEmpty()) {
            return InstagramPostResponse(false, "Missing CSRF token in Instagram account cookies")
        }

        if (videoBytes.isEmpty()) {
            return InstagramPostResponse(false, "Video bytes are empty")
        }

        Log.d(TAG, "Starting Instagram video/reel upload: uploadId=$uploadId, size=${videoBytes.size} bytes")

        // Step 1: Preflight OPTIONS request for video upload
        val videoUploadUrl = "https://i.instagram.com/rupload_igvideo/fb_uploader_$uploadId"
        try {
            val optionsReq = Request.Builder()
                .url(videoUploadUrl)
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
            Log.w(TAG, "Video OPTIONS preflight warning: ${e.message}")
        }

        // Step 2: Upload video bytes
        val ruploadParams = JSONObject().apply {
            put("media_type", 2)
            put("upload_id", uploadId)
            put("upload_media_height", height)
            put("upload_media_width", width)
            put("upload_media_duration_ms", durationMs)
            put("for_album", false)
            put("direct_v2", false)
        }.toString()

        val videoRequestBody = videoBytes.toRequestBody("video/mp4".toMediaType())

        val videoUploadReq = Request.Builder()
            .url(videoUploadUrl)
            .post(videoRequestBody)
            .header("Host", "i.instagram.com")
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/")
            .header("X-Instagram-Rupload-Params", ruploadParams)
            .header("X-Instagram-AJAX", "1")
            .header("X-CSRFToken", csrf)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Entity-Type", "video/mp4")
            .header("X-Entity-Name", "fb_uploader_$uploadId")
            .header("X-Entity-Length", videoBytes.size.toString())
            .header("Offset", "0")
            .header("Cookie", account.cookies)
            .build()

        val uploadRes = client.newCall(videoUploadReq).execute()
        val uploadBody = uploadRes.body?.string() ?: ""
        Log.d(TAG, "Video upload response HTTP ${uploadRes.code}: $uploadBody")

        if (!uploadRes.isSuccessful || !uploadBody.contains("\"status\":\"ok\"")) {
            return InstagramPostResponse(false, "Video upload failed: $uploadBody")
        }

        // Step 3: Upload cover photo if available
        if (coverImageBytes != null && coverImageBytes.isNotEmpty()) {
            val preparedCover = prepareImageForInstagram(coverImageBytes)
            if (preparedCover != null) {
                val coverUploadUrl = "https://i.instagram.com/rupload_igphoto/fb_uploader_${uploadId}"
                val coverRuploadParams = JSONObject().apply {
                    put("media_type", 2)
                    put("upload_id", uploadId)
                    put("upload_media_height", preparedCover.height)
                    put("upload_media_width", preparedCover.width)
                }.toString()

                val coverRequestBody = preparedCover.bytes.toRequestBody("image/jpeg".toMediaType())
                val coverReq = Request.Builder()
                    .url(coverUploadUrl)
                    .post(coverRequestBody)
                    .header("Host", "i.instagram.com")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/")
                    .header("X-Instagram-Rupload-Params", coverRuploadParams)
                    .header("X-Instagram-AJAX", "1")
                    .header("X-CSRFToken", csrf)
                    .header("X-IG-App-ID", IG_APP_ID)
                    .header("X-Entity-Type", "image/jpeg")
                    .header("X-Entity-Name", "fb_uploader_$uploadId")
                    .header("X-Entity-Length", preparedCover.bytes.size.toString())
                    .header("Offset", "0")
                    .header("Cookie", account.cookies)
                    .build()

                try {
                    val coverRes = client.newCall(coverReq).execute()
                    coverRes.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Cover photo upload warning: ${e.message}")
                }
            }
        }

        // Step 4: Configure Video / Reel
        val cleanCaption = InstagramCaptionUtils.cleanAndFormatCaption(caption)
        val configureClipsUrl = "https://www.instagram.com/api/v1/media/configure_to_clips/"
        val configureGeneralUrl = "https://www.instagram.com/api/v1/media/configure/"

        val clipsJsonPayload = JSONObject().apply {
            put("upload_id", uploadId)
            put("caption", cleanCaption)
            put("clips_share_preview_to_feed", "1")
            put("disable_comments", "0")
            put("disable_oa_reuse", "false")
            put("is_meta_only_post", "0")
            put("is_unified_video", "1")
            put("like_and_view_counts_disabled", "0")
            put("media_share_flow", "creation_flow")
            put("poster_frame_index", 0)
            put("length", (durationMs / 1000).coerceAtLeast(1))
            put("source_type", "4")
            put("video_result", "")
            put("audio_muted", false)
        }

        // Allow Instagram's backend ingestion server time to start processing video transcode
        try {
            Thread.sleep(4000L)
        } catch (_: InterruptedException) {}

        // Step 4: Configure Video / Reel with polling retry for transcode completion
        var configRes: okhttp3.Response? = null
        var configBody = ""
        val maxConfigureAttempts = 5

        for (attempt in 1..maxConfigureAttempts) {
            val configReq = Request.Builder()
                .url(configureClipsUrl)
                .post(clipsJsonPayload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Host", "www.instagram.com")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Instagram-AJAX", "1")
                .header("X-CSRFToken", csrf)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", account.cookies)
                .build()

            try {
                configRes?.close()
                configRes = client.newCall(configReq).execute()
                configBody = configRes?.body?.string() ?: ""
                Log.d(TAG, "Configure to clips (attempt $attempt/$maxConfigureAttempts) HTTP ${configRes?.code}: $configBody")

                if (configRes != null && configRes!!.isSuccessful && configBody.contains("\"status\":\"ok\"")) {
                    break
                }

                // If transcode is still in progress, wait and retry
                val isTranscoding = configBody.contains("transcode", ignoreCase = true) ||
                        configBody.contains("processing", ignoreCase = true) ||
                        configBody.contains("wait", ignoreCase = true) ||
                        configRes?.code == 202

                if (isTranscoding && attempt < maxConfigureAttempts) {
                    Log.d(TAG, "Instagram video transcode in progress. Waiting 3s before retry #$attempt...")
                    Thread.sleep(3000L)
                } else if (!isTranscoding && !configBody.contains("\"status\":\"ok\"")) {
                    // Non-transcode error or rate limit, break out to general configure
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Configure to clips attempt $attempt error: ${e.message}")
                if (attempt < maxConfigureAttempts) {
                    Thread.sleep(3000L)
                }
            }
        }

        // Secondary fallback: media/configure with FormBody if configure_to_clips didn't return ok
        if (configRes == null || !configRes!!.isSuccessful || !configBody.contains("\"status\":\"ok\"")) {
            val formBody = FormBody.Builder(Charsets.UTF_8)
                .add("upload_id", uploadId)
                .add("caption", cleanCaption)
                .add("clips_share_preview_to_feed", "1")
                .add("disable_comments", "0")
                .add("is_unified_video", "1")
                .add("source_type", "4")
                .add("video_result", "")
                .build()

            val generalConfigReq = Request.Builder()
                .url(configureGeneralUrl)
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

            try {
                val fallbackRes = client.newCall(generalConfigReq).execute()
                val fallbackBody = fallbackRes.body?.string() ?: ""
                Log.d(TAG, "Configure general response HTTP ${fallbackRes.code}: $fallbackBody")
                if (fallbackRes.isSuccessful && fallbackBody.contains("\"status\":\"ok\"")) {
                    configRes?.close()
                    configRes = fallbackRes
                    configBody = fallbackBody
                } else {
                    fallbackRes.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "General video configure error: ${e.message}")
            }
        }

        return if (configRes != null && configRes!!.isSuccessful && configBody.contains("\"status\":\"ok\"")) {
            var postUrl: String? = null
            try {
                val json = JSONObject(configBody)
                val media = json.optJSONObject("media")
                val code = media?.optString("code")?.takeIf { it.isNotBlank() }
                    ?: json.optString("code").takeIf { it.isNotBlank() }

                if (code != null) {
                    postUrl = "https://www.instagram.com/reel/$code/"
                } else {
                    val pk = media?.optString("pk")?.takeIf { it.isNotBlank() }
                        ?: media?.optString("id")?.takeIf { it.isNotBlank() }
                    if (pk != null) {
                        postUrl = "https://www.instagram.com/reel/$pk/"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing post code from video configure response", e)
            }

            if (postUrl == null && account.username.isNotBlank()) {
                postUrl = "https://www.instagram.com/${account.username}/"
            }

            val msg = if (postUrl != null) {
                "Successfully posted Reel to Instagram!\n$postUrl"
            } else {
                "Successfully posted Reel to Instagram! (UploadId: $uploadId)"
            }
            InstagramPostResponse(true, msg, postUrl)
        } else {
            InstagramPostResponse(false, "Video configure failed: $configBody")
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
