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
     * Resolves the Instagram reel or post web URL for display, copying, or opening.
     */
    fun getEffectivePostUrl(): String? {
        if (!postUrl.isNullOrBlank()) return postUrl
        if (!publishedTo.isNullOrBlank()) {
            val user = publishedTo?.split(",")?.firstOrNull()?.trim()?.removePrefix("@")
            if (!user.isNullOrBlank()) return "https://www.instagram.com/$user/"
        }
        return null
    }

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
     * Resolves the HLS stream URL (.m3u8) for native streaming playback.
     */
    fun getHlsStreamUrl(): String? {
        if (mediaUrl.contains(".m3u8")) return mediaUrl

        if (rawJson.isNotBlank()) {
            try {
                val json = JSONObject(rawJson)
                val videos = json.optJSONObject("videos")?.optJSONObject("video_list")
                if (videos != null) {
                    val hlsKeys = listOf("V_HLSV4", "V_HLSV3_MOBILE")
                    for (key in hlsKeys) {
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && url.contains(".m3u8")) {
                            return url
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Resolves the direct video track file (.cmfv or .mp4) for download & Instagram upload.
     */
    fun getVideoTrackUrl(): String? {
        // Priority 1: Direct MP4 if explicitly present in video_list
        if (rawJson.isNotBlank()) {
            try {
                val json = JSONObject(rawJson)
                val videos = json.optJSONObject("videos")?.optJSONObject("video_list")
                if (videos != null) {
                    val mp4Keys = listOf("V_720P", "V_EXP7", "V_EXP6", "V_EXP5", "V_EXP4", "V_EXP3")
                    for (key in mp4Keys) {
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && url.contains(".mp4")) {
                            return url
                        }
                    }
                    val iter = videos.keys()
                    while (iter.hasNext()) {
                        val key = iter.next()
                        val url = videos.optJSONObject(key)?.optString("url")?.trim()
                        if (!url.isNullOrBlank() && url.contains(".mp4")) {
                            return url
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Priority 2: CMAF 720p/540w video track derived from Pinterest HLS URL
        val hls = getHlsStreamUrl()
        if (hls != null && hls.contains(".m3u8")) {
            return hls.replace(".m3u8", "_720w.cmfv")
        }

        if (mediaUrl.contains(".mp4")) return mediaUrl
        if (mediaUrl.contains(".cmfv")) return mediaUrl

        return null
    }

    /**
     * Resolves all possible video track URL candidates with multi-resolution fallbacks.
     */
    fun getVideoTrackCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        val primary = getVideoTrackUrl()
        if (primary != null) candidates.add(primary)

        val hls = getHlsStreamUrl()
        if (hls != null && hls.contains(".m3u8")) {
            val resList = listOf("_720w.cmfv", "_540w.cmfv", "_360w.cmfv", "_240w.cmfv", "_mobile.cmfv")
            for (res in resList) {
                val candidate = hls.replace(".m3u8", res)
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate)
                }
            }
        }

        if (mediaUrl.contains(".mp4") && !candidates.contains(mediaUrl)) {
            candidates.add(mediaUrl)
        }
        if (mediaUrl.contains(".cmfv") && !candidates.contains(mediaUrl)) {
            candidates.add(mediaUrl)
        }
        return candidates
    }

    /**
     * Resolves the separate audio track file (.cmfa) for Pinterest CMAF video pins.
     */
    fun getAudioTrackUrl(): String? {
        val hls = getHlsStreamUrl()
        if (hls != null && hls.contains(".m3u8")) {
            return hls.replace(".m3u8", "_audio.cmfa")
        }
        return null
    }

    /**
     * Returns whether this pin is a video pin.
     */
    fun isVideoPin(): Boolean {
        return mediaType == "VIDEO" || getHlsStreamUrl() != null || getVideoTrackUrl() != null
    }

    /**
     * Resolves playable video stream URL candidate list in priority order.
     */
    fun getVideoCandidates(): List<String> {
        val candidates = mutableListOf<String>()

        val hls = getHlsStreamUrl()
        if (hls != null && !candidates.contains(hls)) {
            candidates.add(hls)
        }

        val videoTrack = getVideoTrackUrl()
        if (videoTrack != null && !candidates.contains(videoTrack)) {
            candidates.add(videoTrack)
        }

        if (mediaUrl.isNotBlank() && !candidates.contains(mediaUrl)) {
            candidates.add(mediaUrl)
        }

        return candidates
    }

    /**
     * Resolves playable video stream URL if pin has video media.
     */
    fun getVideoStreamUrl(): String? {
        return getHlsStreamUrl() ?: getVideoTrackUrl() ?: if (mediaType == "VIDEO") mediaUrl else null
    }
}
