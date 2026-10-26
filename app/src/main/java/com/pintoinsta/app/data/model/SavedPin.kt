package com.pintoinsta.app.data.model

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
    val rawJson: String = ""
)
