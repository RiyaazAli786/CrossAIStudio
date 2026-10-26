package com.pintoinsta.app.data.model

data class AccountAnalytics(
    val accountId: Long,
    val username: String,
    val platform: String, // "Instagram" or "Pinterest"
    val profilePicUrl: String,
    val totalPublished: Int,
    val totalFailed: Int,
    val totalActions: Int,
    val successRate: Float,
    val lastActiveTime: Long,
    val status: String
)

data class DailyAnalytics(
    val dateLabel: String, // e.g. "Oct 24", "Oct 25"
    val publishedCount: Int,
    val failedCount: Int
)

data class OverallAnalytics(
    val totalPublished: Int,
    val totalFailed: Int,
    val totalAccounts: Int,
    val overallSuccessRate: Float,
    val accountStats: List<AccountAnalytics>,
    val dailyStats: List<DailyAnalytics>
)
