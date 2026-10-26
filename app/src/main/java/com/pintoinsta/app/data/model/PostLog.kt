package com.pintoinsta.app.data.model

data class PostLog(
    val id: Long = 0,
    val pinId: String,
    val instagramAccountId: Long?,
    val status: String, // SUCCESS, FAILED
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
