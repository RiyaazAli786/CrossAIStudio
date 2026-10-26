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
    var linkedInstagramAccountIds: String = "", // Comma-separated list of Instagram account IDs (e.g. "1,2,3")
    var scheduleIntervalHours: Int = 1, // 1, 2, 4, 6, 12, 24
    var isAutoPostEnabled: Boolean = true,
    var nextRunTime: Long = 0,
    var lastRunTime: Long = 0,
    var lastRunStatus: String = "Idle",
    var lastCheckedTime: Long = 0
) {
    /**
     * Returns the list of linked Instagram account IDs (supporting multiple selected accounts).
     */
    fun getLinkedInstagramIdList(): List<Long> {
        if (linkedInstagramAccountIds.isNotBlank()) {
            return linkedInstagramAccountIds.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .distinct()
        }
        return if (linkedInstagramAccountId != null) listOf(linkedInstagramAccountId!!) else emptyList()
    }

    /**
     * Updates both linkedInstagramAccountIds and linkedInstagramAccountId (primary).
     */
    fun setLinkedInstagramIdList(ids: List<Long>) {
        linkedInstagramAccountIds = ids.joinToString(",")
        linkedInstagramAccountId = ids.firstOrNull()
    }
}
