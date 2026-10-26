package com.pintoinsta.app.data.model

data class PostLog(
    val id: Long = 0,
    val pinId: String,
    val instagramAccountId: Long? = null,
    val status: String, // SUCCESS, FAILED, INFO
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val postUrl: String? = null
) {
    /**
     * Resolves the Instagram reel or post web URL if available directly or inside the log message.
     */
    fun getEffectivePostUrl(): String? {
        if (!postUrl.isNullOrBlank()) return postUrl
        val regex = Regex("https?://(?:www\\.)?instagram\\.com/(?:p|reel)/[a-zA-Z0-9_-]+/?")
        return regex.find(message)?.value
    }
}
