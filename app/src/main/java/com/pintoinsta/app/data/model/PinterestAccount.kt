package com.pintoinsta.app.data.model

data class PinterestAccount(
    val id: Long = 0,
    val username: String,
    val displayName: String = "",
    val profilePicUrl: String = "",
    val cookies: String,
    val csrfToken: String = "",
    var status: String = "ACTIVE", // ACTIVE, EXPIRED, CHECKING, UNKNOWN
    var linkedInstagramAccountId: Long? = null,
    var scheduleIntervalHours: Int = 24, // 1, 2, 4, 6, 12, 24
    var isAutoPostEnabled: Boolean = false,
    var nextRunTime: Long = 0,
    var lastRunTime: Long = 0,
    var lastRunStatus: String = "Idle",
    var lastCheckedTime: Long = 0
)
