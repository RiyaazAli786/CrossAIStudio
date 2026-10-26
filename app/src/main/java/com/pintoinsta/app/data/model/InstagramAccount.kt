package com.pintoinsta.app.data.model

data class InstagramAccount(
    val id: Long = 0,
    val username: String,
    val displayName: String = "",
    val profilePicUrl: String = "",
    val cookies: String,
    val csrfToken: String = "",
    val dsUserId: String = "",
    var status: String = "ACTIVE", // ACTIVE, EXPIRED, CHECKING, UNKNOWN
    var lastCheckedTime: Long = 0
)
