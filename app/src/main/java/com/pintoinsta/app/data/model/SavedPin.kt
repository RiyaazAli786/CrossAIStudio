package com.pintoinsta.app.data.model

import org.json.JSONObject

data class SavedPin(
    val pinId: String, // PRIMARY KEY: pinid as key
    val pinterestAccountId: Long,
    val searchTerm: String,
    val title: String,
    val description: String,
    val mediaUrl: String,
    val mediaType: String = "IMAGE", // IMAGE or VIDEO
    var isPosted: Boolean = false,
    var postedAt: Long? = null,
    var instagramAccountId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val rawJson: String = "",
    var postUrl: String? = null
) {
    /**
     * Resolves the actual image URL for thumbnail display or Instagram photo posting.
     * Prevents passing video URLs or m3u8 playlists into image processors.
     */
    fun getEffectiveImageUrl(): String {
        // If mediaUrl is an image (not m3u8 playlist or video file)
        if (mediaType == "IMAGE" && mediaUrl.isNotBlank() && !mediaUrl.contains(".m3u8")) {
            return mediaUrl
        }

        // Try extracting high-res image from rawJson
        if (rawJson.isNotBlank()) {
            try {
                val json = JSONObject(rawJson)
                val images = json.optJSONObject("images")
                if (images != null) {
                    val orig = images.optJSONObject("orig")?.optString("url")
                    if (!orig.isNullOrBlank()) return orig

                    val img736 = images.optJSONObject("736x")?.optString("url")
                    if (!img736.isNullOrBlank()) return img736

                    val img474 = images.optJSONObject("474x")?.optString("url")
                    if (!img474.isNullOrBlank()) return img474
                }
            } catch (_: Exception) {
                // Fallback below
            }
        }

        return if (!mediaUrl.contains(".m3u8")) mediaUrl else ""
    }

    /**
     * Resolves playable video stream URL if pin has video media.
     */
    fun getVideoStreamUrl(): String? {
        if (mediaType == "VIDEO" && mediaUrl.isNotBlank()) {
            return mediaUrl
        }

        if (rawJson.isNotBlank()) {
            try {
                val json = JSONObject(rawJson)
                val videos = json.optJSONObject("videos")?.optJSONObject("video_list")
                if (videos != null) {
                    val keys = listOf("V_720P", "V_HLSV4", "V_HLSV3_MOBILE", "V_EXP7")
                    for (key in keys) {
                        val url = videos.optJSONObject(key)?.optString("url")
                        if (!url.isNullOrBlank()) return url
                    }
                }
            } catch (_: Exception) {
                // Fallback below
            }
        }

        return if (mediaType == "VIDEO" && mediaUrl.isNotBlank()) mediaUrl else null
    }
}
