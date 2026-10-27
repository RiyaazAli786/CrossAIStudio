package com.pintoinsta.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pintoinsta.app.data.model.AccountAnalytics
import com.pintoinsta.app.data.model.DailyAnalytics
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.OverallAnalytics
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.model.SearchKeyword
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "pintoinsta.db"
        const val DATABASE_VERSION = 1

        private var instance: AppDatabaseHelper? = null

        @Synchronized
        fun getInstance(context: Context): AppDatabaseHelper {
            if (instance == null) {
                instance = AppDatabaseHelper(context.applicationContext)
            }
            return instance!!
        }

        // Tables
        const val TABLE_PINTEREST_ACCOUNTS = "pinterest_accounts"
        const val TABLE_INSTAGRAM_ACCOUNTS = "instagram_accounts"
        const val TABLE_SEARCH_KEYWORDS = "search_keywords"
        const val TABLE_SAVED_PINS = "saved_pins"
        const val TABLE_POST_LOGS = "post_logs"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PINTEREST_ACCOUNTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                display_name TEXT,
                profile_pic_url TEXT,
                cookies TEXT,
                csrf_token TEXT,
                status TEXT,
                linked_instagram_account_id INTEGER,
                linked_instagram_account_ids TEXT,
                schedule_interval_hours INTEGER,
                is_auto_post_enabled INTEGER,
                next_run_time INTEGER,
                last_run_time INTEGER,
                last_run_status TEXT,
                last_checked_time INTEGER,
                daily_post_times TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_INSTAGRAM_ACCOUNTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                display_name TEXT,
                profile_pic_url TEXT,
                cookies TEXT,
                csrf_token TEXT,
                ds_user_id TEXT,
                status TEXT,
                last_checked_time INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_SEARCH_KEYWORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT UNIQUE,
                created_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_SAVED_PINS (
                pin_id TEXT PRIMARY KEY,
                pinterest_account_id INTEGER,
                search_term TEXT,
                title TEXT,
                description TEXT,
                media_url TEXT,
                media_type TEXT,
                is_posted INTEGER,
                posted_at INTEGER,
                instagram_account_id INTEGER,
                created_at INTEGER,
                raw_json TEXT,
                post_url TEXT,
                published_to TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_POST_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pin_id TEXT,
                instagram_account_id INTEGER,
                status TEXT,
                message TEXT,
                timestamp INTEGER,
                post_url TEXT
            )
            """.trimIndent()
        )

    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.execSQL("ALTER TABLE $TABLE_POST_LOGS ADD COLUMN post_url TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_SAVED_PINS ADD COLUMN post_url TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_PINTEREST_ACCOUNTS ADD COLUMN linked_instagram_account_ids TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_PINTEREST_ACCOUNTS ADD COLUMN daily_post_times TEXT")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE $TABLE_SAVED_PINS ADD COLUMN published_to TEXT")
        } catch (_: Exception) {}

        // Remove any previously seeded default search keywords
        val legacyDefaultKeywords = listOf(
            "funny cartoon video",
            "animated comedy short",
            "diy crafts art",
            "nature relaxing video",
            "trending 3d animation"
        )
        for (kw in legacyDefaultKeywords) {
            try {
                db.delete(TABLE_SEARCH_KEYWORDS, "keyword = ?", arrayOf(kw))
            } catch (_: Exception) {}
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_POST_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SAVED_PINS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SEARCH_KEYWORDS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_INSTAGRAM_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PINTEREST_ACCOUNTS")
        onCreate(db)
    }

    fun getLastPostTimeForInstagramAccount(instagramAccountId: Long): Long {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT MAX(timestamp) FROM $TABLE_POST_LOGS WHERE instagram_account_id = ? AND status = 'SUCCESS'",
            arrayOf(instagramAccountId.toString())
        )
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }

    // --- PINTEREST ACCOUNTS ---
    fun insertOrUpdatePinterestAccount(account: PinterestAccount): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("username", account.username)
            put("display_name", account.displayName)
            put("profile_pic_url", account.profilePicUrl)
            put("cookies", account.cookies)
            put("csrf_token", account.csrfToken)
            put("status", account.status)
            put("linked_instagram_account_id", account.linkedInstagramAccountId)
            put("linked_instagram_account_ids", account.linkedInstagramAccountIds)
            put("schedule_interval_hours", account.scheduleIntervalHours)
            put("is_auto_post_enabled", if (account.isAutoPostEnabled) 1 else 0)
            put("next_run_time", account.nextRunTime)
            put("last_run_time", account.lastRunTime)
            put("last_run_status", account.lastRunStatus)
            put("last_checked_time", account.lastCheckedTime)
            put("daily_post_times", account.dailyPostTimes)
        }
        val existing = getPinterestAccountByUsername(account.username)
        return if (existing != null) {
            db.update(TABLE_PINTEREST_ACCOUNTS, cv, "id = ?", arrayOf(existing.id.toString()))
            existing.id
        } else {
            db.insert(TABLE_PINTEREST_ACCOUNTS, null, cv)
        }
    }

    fun getAllPinterestAccounts(): List<PinterestAccount> {
        val list = mutableListOf<PinterestAccount>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PINTEREST_ACCOUNTS ORDER BY id DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToPinterestAccount(it))
            }
        }
        return list
    }

    fun getPinterestAccountById(id: Long): PinterestAccount? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PINTEREST_ACCOUNTS WHERE id = ?", arrayOf(id.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToPinterestAccount(it)
            }
        }
        return null
    }

    fun getPinterestAccountByUsername(username: String): PinterestAccount? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PINTEREST_ACCOUNTS WHERE username = ?", arrayOf(username))
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToPinterestAccount(it)
            }
        }
        return null
    }

    fun updatePinterestAccountStatus(id: Long, status: String, lastCheckedTime: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
            put("last_checked_time", lastCheckedTime)
        }
        db.update(TABLE_PINTEREST_ACCOUNTS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun updatePinterestAccountSchedule(
        id: Long,
        linkedInstaId: Long?,
        intervalHours: Int,
        isEnabled: Boolean,
        linkedInstaIds: String = "",
        dailyPostTimes: String = "09:00,14:00,20:00"
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("linked_instagram_account_id", linkedInstaId)
            put("linked_instagram_account_ids", linkedInstaIds)
            put("schedule_interval_hours", intervalHours)
            put("is_auto_post_enabled", if (isEnabled) 1 else 0)
            put("daily_post_times", dailyPostTimes)
        }
        db.update(TABLE_PINTEREST_ACCOUNTS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun updatePinterestAccountRunInfo(id: Long, lastRunTime: Long, lastRunStatus: String, nextRunTime: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("last_run_time", lastRunTime)
            put("last_run_status", lastRunStatus)
            put("next_run_time", nextRunTime)
        }
        db.update(TABLE_PINTEREST_ACCOUNTS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun deletePinterestAccount(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_PINTEREST_ACCOUNTS, "id = ?", arrayOf(id.toString()))
    }

    private fun cursorToPinterestAccount(cursor: Cursor): PinterestAccount {
        val multiIdx = cursor.getColumnIndex("linked_instagram_account_ids")
        val linkedAccountIds = if (multiIdx != -1 && !cursor.isNull(multiIdx)) cursor.getString(multiIdx) else ""

        val dailyTimesIdx = cursor.getColumnIndex("daily_post_times")
        val dailyPostTimes = if (dailyTimesIdx != -1 && !cursor.isNull(dailyTimesIdx)) cursor.getString(dailyTimesIdx) else "09:00,14:00,20:00"

        return PinterestAccount(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")) ?: "",
            profilePicUrl = cursor.getString(cursor.getColumnIndexOrThrow("profile_pic_url")) ?: "",
            cookies = cursor.getString(cursor.getColumnIndexOrThrow("cookies")),
            csrfToken = cursor.getString(cursor.getColumnIndexOrThrow("csrf_token")) ?: "",
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "UNKNOWN",
            linkedInstagramAccountId = if (cursor.isNull(cursor.getColumnIndexOrThrow("linked_instagram_account_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("linked_instagram_account_id")),
            linkedInstagramAccountIds = linkedAccountIds,
            scheduleIntervalHours = cursor.getInt(cursor.getColumnIndexOrThrow("schedule_interval_hours")),
            isAutoPostEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_auto_post_enabled")) == 1,
            nextRunTime = cursor.getLong(cursor.getColumnIndexOrThrow("next_run_time")),
            lastRunTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_run_time")),
            lastRunStatus = cursor.getString(cursor.getColumnIndexOrThrow("last_run_status")) ?: "Idle",
            lastCheckedTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_checked_time")),
            dailyPostTimes = dailyPostTimes
        )
    }

    // --- INSTAGRAM ACCOUNTS ---
    fun insertOrUpdateInstagramAccount(account: InstagramAccount): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("username", account.username)
            put("display_name", account.displayName)
            put("profile_pic_url", account.profilePicUrl)
            put("cookies", account.cookies)
            put("csrf_token", account.csrfToken)
            put("ds_user_id", account.dsUserId)
            put("status", account.status)
            put("last_checked_time", account.lastCheckedTime)
        }
        val existing = getInstagramAccountByUsername(account.username)
        return if (existing != null) {
            db.update(TABLE_INSTAGRAM_ACCOUNTS, cv, "id = ?", arrayOf(existing.id.toString()))
            existing.id
        } else {
            db.insert(TABLE_INSTAGRAM_ACCOUNTS, null, cv)
        }
    }

    fun getAllInstagramAccounts(): List<InstagramAccount> {
        val list = mutableListOf<InstagramAccount>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INSTAGRAM_ACCOUNTS ORDER BY id DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToInstagramAccount(it))
            }
        }
        return list
    }

    fun getInstagramAccountById(id: Long): InstagramAccount? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INSTAGRAM_ACCOUNTS WHERE id = ?", arrayOf(id.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToInstagramAccount(it)
            }
        }
        return null
    }

    fun getInstagramAccountByUsername(username: String): InstagramAccount? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INSTAGRAM_ACCOUNTS WHERE username = ?", arrayOf(username))
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToInstagramAccount(it)
            }
        }
        return null
    }

    fun updateInstagramAccountStatus(id: Long, status: String, lastCheckedTime: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status)
            put("last_checked_time", lastCheckedTime)
        }
        db.update(TABLE_INSTAGRAM_ACCOUNTS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteInstagramAccount(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_INSTAGRAM_ACCOUNTS, "id = ?", arrayOf(id.toString()))
    }

    private fun cursorToInstagramAccount(cursor: Cursor): InstagramAccount {
        return InstagramAccount(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")) ?: "",
            profilePicUrl = cursor.getString(cursor.getColumnIndexOrThrow("profile_pic_url")) ?: "",
            cookies = cursor.getString(cursor.getColumnIndexOrThrow("cookies")),
            csrfToken = cursor.getString(cursor.getColumnIndexOrThrow("csrf_token")) ?: "",
            dsUserId = cursor.getString(cursor.getColumnIndexOrThrow("ds_user_id")) ?: "",
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "UNKNOWN",
            lastCheckedTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_checked_time"))
        )
    }

    // --- SEARCH KEYWORDS ---
    fun addSearchKeyword(keyword: String): Long {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return -1
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("keyword", trimmed)
            put("created_at", System.currentTimeMillis())
        }
        return db.insertWithOnConflict(TABLE_SEARCH_KEYWORDS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getAllSearchKeywords(): List<SearchKeyword> {
        val list = mutableListOf<SearchKeyword>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SEARCH_KEYWORDS ORDER BY id ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SearchKeyword(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        keyword = it.getString(it.getColumnIndexOrThrow("keyword")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return list
    }

    fun deleteSearchKeyword(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_SEARCH_KEYWORDS, "id = ?", arrayOf(id.toString()))
    }

    // --- SAVED PINS (KEY = pin_id) ---
    fun insertSavedPin(pin: SavedPin): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("pin_id", pin.pinId)
            put("pinterest_account_id", pin.pinterestAccountId)
            put("search_term", pin.searchTerm)
            put("title", pin.title)
            put("description", pin.description)
            put("media_url", pin.mediaUrl)
            put("media_type", pin.mediaType)
            put("is_posted", if (pin.isPosted) 1 else 0)
            put("posted_at", pin.postedAt)
            put("instagram_account_id", pin.instagramAccountId)
            put("created_at", pin.createdAt)
            put("raw_json", pin.rawJson)
            if (!pin.publishedTo.isNullOrBlank()) {
                put("published_to", pin.publishedTo)
            }
        }
        return db.insertWithOnConflict(TABLE_SAVED_PINS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getSavedPinById(pinId: String): SavedPin? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SAVED_PINS WHERE pin_id = ?", arrayOf(pinId))
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToSavedPin(it)
            }
        }
        return null
    }

    fun getAllSavedPins(): List<SavedPin> {
        val list = mutableListOf<SavedPin>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SAVED_PINS ORDER BY created_at DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSavedPin(it))
            }
        }
        return list
    }

    fun getUnpostedPins(limit: Int = 10): List<SavedPin> {
        val list = mutableListOf<SavedPin>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_SAVED_PINS WHERE is_posted = 0 ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToSavedPin(it))
            }
        }
        return list
    }

    fun markPinAsPosted(
        pinId: String,
        instagramAccountId: Long,
        postedAt: Long = System.currentTimeMillis(),
        postUrl: String? = null,
        publishedToUsername: String? = null
    ) {
        val db = writableDatabase
        val existingPin = getSavedPinById(pinId)
        val currentPublishedTo = existingPin?.publishedTo
        val newPublishedTo = when {
            publishedToUsername.isNullOrBlank() -> currentPublishedTo
            currentPublishedTo.isNullOrBlank() -> "@$publishedToUsername"
            currentPublishedTo.contains("@$publishedToUsername") -> currentPublishedTo
            else -> "$currentPublishedTo, @$publishedToUsername"
        }

        val cv = ContentValues().apply {
            put("is_posted", 1)
            put("posted_at", postedAt)
            put("instagram_account_id", instagramAccountId)
            if (!postUrl.isNullOrBlank()) {
                put("post_url", postUrl)
            }
            if (!newPublishedTo.isNullOrBlank()) {
                put("published_to", newPublishedTo)
            }
        }
        db.update(TABLE_SAVED_PINS, cv, "pin_id = ?", arrayOf(pinId))
    }

    fun deleteSavedPin(pinId: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_SAVED_PINS, "pin_id = ?", arrayOf(pinId))
    }

    fun deleteSavedPins(pinIds: List<String>): Int {
        if (pinIds.isEmpty()) return 0
        val db = writableDatabase
        var deletedCount = 0
        db.beginTransaction()
        try {
            for (id in pinIds) {
                deletedCount += db.delete(TABLE_SAVED_PINS, "pin_id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deletedCount
    }

    private fun cursorToSavedPin(cursor: Cursor): SavedPin {
        val postUrlIdx = cursor.getColumnIndex("post_url")
        val postUrl = if (postUrlIdx != -1 && !cursor.isNull(postUrlIdx)) cursor.getString(postUrlIdx) else null
        val publishedToIdx = cursor.getColumnIndex("published_to")
        val publishedTo = if (publishedToIdx != -1 && !cursor.isNull(publishedToIdx)) cursor.getString(publishedToIdx) else null

        return SavedPin(
            pinId = cursor.getString(cursor.getColumnIndexOrThrow("pin_id")),
            pinterestAccountId = cursor.getLong(cursor.getColumnIndexOrThrow("pinterest_account_id")),
            searchTerm = cursor.getString(cursor.getColumnIndexOrThrow("search_term")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")) ?: "",
            description = cursor.getString(cursor.getColumnIndexOrThrow("description")) ?: "",
            mediaUrl = cursor.getString(cursor.getColumnIndexOrThrow("media_url")) ?: "",
            mediaType = cursor.getString(cursor.getColumnIndexOrThrow("media_type")) ?: "IMAGE",
            isPosted = cursor.getInt(cursor.getColumnIndexOrThrow("is_posted")) == 1,
            postedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("posted_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("posted_at")),
            instagramAccountId = if (cursor.isNull(cursor.getColumnIndexOrThrow("instagram_account_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("instagram_account_id")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            rawJson = cursor.getString(cursor.getColumnIndexOrThrow("raw_json")) ?: "",
            postUrl = postUrl,
            publishedTo = publishedTo
        )
    }

    // --- ANALYTICS ---
    fun getAnalyticsData(): OverallAnalytics {
        val db = readableDatabase
        val instaAccounts = getAllInstagramAccounts()
        val pinAccounts = getAllPinterestAccounts()
        val accountStatsList = mutableListOf<AccountAnalytics>()

        var totalPublishedAll = 0
        var totalFailedAll = 0

        // Analytics for Instagram Accounts
        for (insta in instaAccounts) {
            var published = 0
            var failed = 0
            var lastActive = insta.lastCheckedTime

            val curSuccess = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_POST_LOGS WHERE instagram_account_id = ? AND status = 'SUCCESS'",
                arrayOf(insta.id.toString())
            )
            curSuccess.use { if (it.moveToFirst()) published = it.getInt(0) }

            val curFailed = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_POST_LOGS WHERE instagram_account_id = ? AND status = 'FAILED'",
                arrayOf(insta.id.toString())
            )
            curFailed.use { if (it.moveToFirst()) failed = it.getInt(0) }

            val curLastTime = db.rawQuery(
                "SELECT MAX(timestamp) FROM $TABLE_POST_LOGS WHERE instagram_account_id = ?",
                arrayOf(insta.id.toString())
            )
            curLastTime.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    val t = it.getLong(0)
                    if (t > lastActive) lastActive = t
                }
            }

            totalPublishedAll += published
            totalFailedAll += failed
            val total = published + failed
            val rate = if (total > 0) (published * 100f / total) else 100f

            accountStatsList.add(
                AccountAnalytics(
                    accountId = insta.id,
                    username = insta.username,
                    platform = "Instagram",
                    profilePicUrl = insta.profilePicUrl,
                    totalPublished = published,
                    totalFailed = failed,
                    totalActions = total,
                    successRate = rate,
                    lastActiveTime = lastActive,
                    status = insta.status
                )
            )
        }

        // Analytics for Pinterest Accounts
        for (pinAcc in pinAccounts) {
            var published = 0
            var totalPins = 0
            var failedSearches = 0
            var lastActive = maxOf(pinAcc.lastRunTime, pinAcc.lastCheckedTime)

            val curPins = db.rawQuery(
                "SELECT COUNT(*), SUM(CASE WHEN is_posted = 1 THEN 1 ELSE 0 END) FROM $TABLE_SAVED_PINS WHERE pinterest_account_id = ?",
                arrayOf(pinAcc.id.toString())
            )
            curPins.use {
                if (it.moveToFirst()) {
                    totalPins = it.getInt(0)
                    published = if (!it.isNull(1)) it.getInt(1) else 0
                }
            }

            val curSearchFail = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_POST_LOGS WHERE pin_id = 'SEARCH' AND status = 'FAILED' AND message LIKE ?",
                arrayOf("%${pinAcc.username}%")
            )
            curSearchFail.use { if (it.moveToFirst()) failedSearches = it.getInt(0) }

            val total = published + failedSearches
            val rate = if (total > 0) (published * 100f / total) else 100f

            accountStatsList.add(
                AccountAnalytics(
                    accountId = pinAcc.id,
                    username = pinAcc.username,
                    platform = "Pinterest",
                    profilePicUrl = pinAcc.profilePicUrl,
                    totalPublished = published,
                    totalFailed = failedSearches,
                    totalActions = totalPins,
                    successRate = rate,
                    lastActiveTime = lastActive,
                    status = pinAcc.status
                )
            )
        }

        // Daily Trend (last 7 days)
        val dailyList = mutableListOf<DailyAnalytics>()
        val sdfLabel = SimpleDateFormat("MMM dd", Locale.getDefault())

        for (i in 6 downTo 0) {
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -i)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startMs = cal.timeInMillis
            val endMs = startMs + 24 * 3600 * 1000L
            val label = sdfLabel.format(cal.time)

            var pub = 0
            var fail = 0

            val curDay = db.rawQuery(
                "SELECT " +
                        "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) " +
                        "FROM $TABLE_POST_LOGS WHERE timestamp >= ? AND timestamp < ?",
                arrayOf(startMs.toString(), endMs.toString())
            )
            curDay.use {
                if (it.moveToFirst()) {
                    pub = if (!it.isNull(0)) it.getInt(0) else 0
                    fail = if (!it.isNull(1)) it.getInt(1) else 0
                }
            }

            dailyList.add(DailyAnalytics(dateLabel = label, publishedCount = pub, failedCount = fail))
        }

        val allTotal = totalPublishedAll + totalFailedAll
        val overallRate = if (allTotal > 0) (totalPublishedAll * 100f / allTotal) else 100f

        return OverallAnalytics(
            totalPublished = totalPublishedAll,
            totalFailed = totalFailedAll,
            totalAccounts = instaAccounts.size + pinAccounts.size,
            overallSuccessRate = overallRate,
            accountStats = accountStatsList,
            dailyStats = dailyList
        )
    }

    // --- POST LOGS ---
    fun insertPostLog(log: PostLog): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("pin_id", log.pinId)
            put("instagram_account_id", log.instagramAccountId)
            put("status", log.status)
            put("message", log.message)
            put("timestamp", log.timestamp)
            put("post_url", log.postUrl ?: log.getEffectivePostUrl())
        }
        return db.insert(TABLE_POST_LOGS, null, cv)
    }

    fun getRecentPostLogs(limit: Int = 50): List<PostLog> {
        val list = mutableListOf<PostLog>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_POST_LOGS ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                val postUrlIdx = it.getColumnIndex("post_url")
                val postUrl = if (postUrlIdx != -1 && !it.isNull(postUrlIdx)) it.getString(postUrlIdx) else null

                list.add(
                    PostLog(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        pinId = it.getString(it.getColumnIndexOrThrow("pin_id")),
                        instagramAccountId = if (it.isNull(it.getColumnIndexOrThrow("instagram_account_id"))) null else it.getLong(it.getColumnIndexOrThrow("instagram_account_id")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        postUrl = postUrl
                    )
                )
            }
        }
        return list
    }

    fun clearAllPostLogs(): Int {
        val db = writableDatabase
        return db.delete(TABLE_POST_LOGS, null, null)
    }
}
