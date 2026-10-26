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
    var scheduleIntervalHours: Int = 1, // 1, 2, 4, 6, 12, 24 (legacy fallback)
    var isAutoPostEnabled: Boolean = true,
    var nextRunTime: Long = 0,
    var lastRunTime: Long = 0,
    var lastRunStatus: String = "Idle",
    var lastCheckedTime: Long = 0,
    var dailyPostTimes: String = "09:00,14:00,20:00" // Comma-separated "HH:mm" strings
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

    /**
     * Returns the list of configured daily post times (e.g. ["09:00", "14:00", "20:00"]).
     */
    fun getDailyPostTimeList(): List<String> {
        if (dailyPostTimes.isBlank()) {
            return listOf("09:00", "14:00", "20:00")
        }
        return dailyPostTimes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Sets the configured daily post times.
     */
    fun setDailyPostTimeList(times: List<String>) {
        dailyPostTimes = times.filter { it.isNotBlank() }.joinToString(",")
    }

    /**
     * Calculates the exact next scheduled timestamp based on dailyPostTimes.
     * Looks for upcoming slots today; if none remain, picks the earliest slot tomorrow.
     */
    fun calculateNextScheduledTime(nowMs: Long = System.currentTimeMillis()): Long {
        val times = getDailyPostTimeList()
        if (times.isEmpty()) {
            return nowMs + (scheduleIntervalHours.coerceAtLeast(1) * 3600 * 1000L)
        }

        val upcomingToday = mutableListOf<Long>()
        val tomorrowSlots = mutableListOf<Long>()

        for (timeStr in times) {
            val parts = timeStr.split(":")
            if (parts.size >= 2) {
                val hour = parts[0].trim().toIntOrNull() ?: continue
                val minute = parts[1].trim().toIntOrNull() ?: continue

                // Check slot for today
                val todayCal = java.util.Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                if (todayCal.timeInMillis > nowMs) {
                    upcomingToday.add(todayCal.timeInMillis)
                }

                // Check slot for tomorrow
                val tomorrowCal = java.util.Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                tomorrowSlots.add(tomorrowCal.timeInMillis)
            }
        }

        return when {
            upcomingToday.isNotEmpty() -> upcomingToday.minOrNull() ?: (nowMs + 3600_000L)
            tomorrowSlots.isNotEmpty() -> tomorrowSlots.minOrNull() ?: (nowMs + 3600_000L)
            else -> nowMs + (scheduleIntervalHours.coerceAtLeast(1) * 3600 * 1000L)
        }
    }
}
