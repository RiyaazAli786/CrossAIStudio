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
    var postUrl: String? = null,
    var publishedTo: String? = null // Comma-separated usernames where published, e.g. "@user1, @user2"
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
     * Resolves playable MP4 or video stream URL candidate list in priority order.
     * Prefers direct progressive MP4 URLs over HLS (.m3u8) playlists.
     */
    fun getVideoCandidates(): List<String> {
        val candidates = mutableListOf<String>()

        if (rawJson.isNotBlank()) {
            try {
                val json = JSONObject(rawJson)
                val videos = json.optJSONObject("videos")?.optJSONObject("video_list")
                if (videos != null) {
                    // Priority 1: Check standard MP4 keys
                    val mp4PriorityKeys = listOf("V_720P", "V_EXP7", "V_EXP6", "V_EXP5", "V_EXP4", "V_EXP3")
                    for (key in mp4PriorityKeys) {
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && url.contains(".mp4") && !candidates.contains(url)) {
                            candidates.add(url)
                        }
                    }

                    // Priority 2: Check any other key in video_list that has .mp4
                    val iter = videos.keys()
                    while (iter.hasNext()) {
                        val key = iter.next()
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && url.contains(".mp4") && !candidates.contains(url)) {
                            candidates.add(url)
                        }
                    }

                    // Priority 3: HLS streams if no mp4 found yet
                    val hlsKeys = listOf("V_HLSV4", "V_HLSV3_MOBILE")
                    for (key in hlsKeys) {
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && !candidates.contains(url)) {
                            // If it's an HLS url, derive the 720p and exp7 mp4 equivalents first!
                            if (url.contains(".m3u8")) {
                                val derived720p = url.replace("/hls/", "/720p/").replace(".m3u8", ".mp4")
                                if (!candidates.contains(derived720p)) candidates.add(derived720p)
                                val derivedExp7 = url.replace("/hls/", "/exp7/").replace(".m3u8", ".mp4")
                                if (!candidates.contains(derivedExp7)) candidates.add(derivedExp7)
                            }
                            candidates.add(url)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore parsing errors
            }
        }

        // Add mediaUrl if not already present
        if (mediaUrl.isNotBlank() && !candidates.contains(mediaUrl)) {
            if (mediaUrl.contains(".mp4")) {
                candidates.add(0, mediaUrl)
            } else if (mediaUrl.contains(".m3u8")) {
                val derived = mediaUrl.replace("/hls/", "/720p/").replace(".m3u8", ".mp4")
                if (!candidates.contains(derived)) candidates.add(derived)
                candidates.add(mediaUrl)
            } else if (mediaType == "VIDEO") {
                candidates.add(mediaUrl)
            }
        }

        return candidates
    }

    /**
     * Resolves playable video stream URL if pin has video media.
     */
    fun getVideoStreamUrl(): String? {
        val list = getVideoCandidates()
        // Prefer first MP4 candidate
        val mp4 = list.firstOrNull { it.contains(".mp4") }
        if (mp4 != null) return mp4
        return list.firstOrNull()
    }
}
