package com.pintoinsta.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.model.SearchKeyword

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
                schedule_interval_hours INTEGER,
                is_auto_post_enabled INTEGER,
                next_run_time INTEGER,
                last_run_time INTEGER,
                last_run_status TEXT,
                last_checked_time INTEGER
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
                raw_json TEXT
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
                timestamp INTEGER
            )
            """.trimIndent()
        )

        // Seed some starter search keywords
        insertDefaultKeywords(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_POST_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SAVED_PINS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SEARCH_KEYWORDS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_INSTAGRAM_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PINTEREST_ACCOUNTS")
        onCreate(db)
    }

    private fun insertDefaultKeywords(db: SQLiteDatabase) {
        val defaults = listOf(
            "funny cartoon video",
            "animated comedy short",
            "diy crafts art",
            "nature relaxing video",
            "trending 3d animation"
        )
        for (kw in defaults) {
            val cv = ContentValues().apply {
                put("keyword", kw)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_SEARCH_KEYWORDS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
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
            put("schedule_interval_hours", account.scheduleIntervalHours)
            put("is_auto_post_enabled", if (account.isAutoPostEnabled) 1 else 0)
            put("next_run_time", account.nextRunTime)
            put("last_run_time", account.lastRunTime)
            put("last_run_status", account.lastRunStatus)
            put("last_checked_time", account.lastCheckedTime)
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

    fun updatePinterestAccountSchedule(id: Long, linkedInstaId: Long?, intervalHours: Int, isEnabled: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("linked_instagram_account_id", linkedInstaId)
            put("schedule_interval_hours", intervalHours)
            put("is_auto_post_enabled", if (isEnabled) 1 else 0)
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
        return PinterestAccount(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")) ?: "",
            profilePicUrl = cursor.getString(cursor.getColumnIndexOrThrow("profile_pic_url")) ?: "",
            cookies = cursor.getString(cursor.getColumnIndexOrThrow("cookies")),
            csrfToken = cursor.getString(cursor.getColumnIndexOrThrow("csrf_token")) ?: "",
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "UNKNOWN",
            linkedInstagramAccountId = if (cursor.isNull(cursor.getColumnIndexOrThrow("linked_instagram_account_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("linked_instagram_account_id")),
            scheduleIntervalHours = cursor.getInt(cursor.getColumnIndexOrThrow("schedule_interval_hours")),
            isAutoPostEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_auto_post_enabled")) == 1,
            nextRunTime = cursor.getLong(cursor.getColumnIndexOrThrow("next_run_time")),
            lastRunTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_run_time")),
            lastRunStatus = cursor.getString(cursor.getColumnIndexOrThrow("last_run_status")) ?: "Idle",
            lastCheckedTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_checked_time"))
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

    fun markPinAsPosted(pinId: String, instagramAccountId: Long, postedAt: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_posted", 1)
            put("posted_at", postedAt)
            put("instagram_account_id", instagramAccountId)
        }
        db.update(TABLE_SAVED_PINS, cv, "pin_id = ?", arrayOf(pinId))
    }

    fun deleteSavedPin(pinId: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_SAVED_PINS, "pin_id = ?", arrayOf(pinId))
    }

    private fun cursorToSavedPin(cursor: Cursor): SavedPin {
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
            rawJson = cursor.getString(cursor.getColumnIndexOrThrow("raw_json")) ?: ""
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
        }
        return db.insert(TABLE_POST_LOGS, null, cv)
    }

    fun getRecentPostLogs(limit: Int = 50): List<PostLog> {
        val list = mutableListOf<PostLog>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_POST_LOGS ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PostLog(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        pinId = it.getString(it.getColumnIndexOrThrow("pin_id")),
                        instagramAccountId = if (it.isNull(it.getColumnIndexOrThrow("instagram_account_id"))) null else it.getLong(it.getColumnIndexOrThrow("instagram_account_id")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        message = it.getString(it.getColumnIndexOrThrow("message")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return list
    }
}
