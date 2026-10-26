package com.pintoinsta.app.data.repository

import android.content.Context
import android.util.Log
import com.pintoinsta.app.data.db.AppDatabaseHelper
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.model.SearchKeyword
import com.pintoinsta.app.network.InstagramClient
import com.pintoinsta.app.network.PinterestClient
import com.pintoinsta.app.notification.NotificationHelper
import com.pintoinsta.app.util.InstagramCaptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class AppRepository(context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val pinterestClient = PinterestClient()
    private val instagramClient = InstagramClient()
    private val notificationHelper = NotificationHelper.getInstance(context)

    companion object {
        private const val TAG = "AppRepository"
        private var instance: AppRepository? = null

        @Synchronized
        fun getInstance(context: Context): AppRepository {
            if (instance == null) {
                instance = AppRepository(context.applicationContext)
            }
            return instance!!
        }
    }

    // --- PINTEREST ACCOUNTS ---
    suspend fun getAllPinterestAccounts(): List<PinterestAccount> = withContext(Dispatchers.IO) {
        dbHelper.getAllPinterestAccounts()
    }

    suspend fun getPinterestAccountById(id: Long): PinterestAccount? = withContext(Dispatchers.IO) {
        dbHelper.getPinterestAccountById(id)
    }

    suspend fun savePinterestAccount(account: PinterestAccount): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.insertOrUpdatePinterestAccount(account)
        notificationHelper.notifyStatusAlert(account.username, "Pinterest", "Account Saved & Active")
        id
    }

    suspend fun updatePinterestAccountSchedule(
        id: Long,
        linkedInstaId: Long?,
        intervalHours: Int,
        isEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        dbHelper.updatePinterestAccountSchedule(id, linkedInstaId, intervalHours, isEnabled)
    }

    suspend fun deletePinterestAccount(id: Long): Boolean = withContext(Dispatchers.IO) {
        dbHelper.deletePinterestAccount(id) > 0
    }

    suspend fun checkPinterestAccountStatus(account: PinterestAccount): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val res = pinterestClient.checkAccountStatus(account)
            val newStatus = if (res.first) "ACTIVE" else "EXPIRED"
            dbHelper.updatePinterestAccountStatus(account.id, newStatus)
            notificationHelper.notifyStatusAlert(account.username, "Pinterest", res.second)
            res
        }

    // --- INSTAGRAM ACCOUNTS ---
    suspend fun getAllInstagramAccounts(): List<InstagramAccount> = withContext(Dispatchers.IO) {
        dbHelper.getAllInstagramAccounts()
    }

    suspend fun getInstagramAccountById(id: Long): InstagramAccount? = withContext(Dispatchers.IO) {
        dbHelper.getInstagramAccountById(id)
    }

    suspend fun saveInstagramAccount(account: InstagramAccount): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.insertOrUpdateInstagramAccount(account)
        notificationHelper.notifyStatusAlert(account.username, "Instagram", "Account Saved & Active")
        id
    }

    suspend fun deleteInstagramAccount(id: Long): Boolean = withContext(Dispatchers.IO) {
        dbHelper.deleteInstagramAccount(id) > 0
    }

    suspend fun checkInstagramAccountStatus(account: InstagramAccount): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val res = instagramClient.checkAccountStatus(account)
            val newStatus = if (res.first) "ACTIVE" else "EXPIRED"
            dbHelper.updateInstagramAccountStatus(account.id, newStatus)
            notificationHelper.notifyStatusAlert(account.username, "Instagram", res.second)
            res
        }

    // --- SEARCH KEYWORDS ---
    suspend fun getAllKeywords(): List<SearchKeyword> = withContext(Dispatchers.IO) {
        dbHelper.getAllSearchKeywords()
    }

    suspend fun addKeyword(keyword: String): Long = withContext(Dispatchers.IO) {
        dbHelper.addSearchKeyword(keyword)
    }

    suspend fun deleteKeyword(id: Long): Boolean = withContext(Dispatchers.IO) {
        dbHelper.deleteSearchKeyword(id) > 0
    }

    suspend fun getRandomKeyword(): String = withContext(Dispatchers.IO) {
        val list = dbHelper.getAllSearchKeywords()
        if (list.isNotEmpty()) {
            list[Random.nextInt(list.size)].keyword
        } else {
            "funny cartoon video"
        }
    }

    // --- PINS ---
    suspend fun getAllSavedPins(): List<SavedPin> = withContext(Dispatchers.IO) {
        dbHelper.getAllSavedPins()
    }

    suspend fun getUnpostedPins(limit: Int = 10): List<SavedPin> = withContext(Dispatchers.IO) {
        dbHelper.getUnpostedPins(limit)
    }

    suspend fun deleteSavedPin(pinId: String): Boolean = withContext(Dispatchers.IO) {
        dbHelper.deleteSavedPin(pinId) > 0
    }

    // --- LOGS ---
    suspend fun getRecentLogs(): List<PostLog> = withContext(Dispatchers.IO) {
        dbHelper.getRecentPostLogs(50)
    }

    /**
     * Requirement 4: On scheduled time, search random text saved in settings
     * using PinteresetSearch.txt & PinterestSearchWithPagination.txt, and save results with pinid as key
     */
    suspend fun executePinterestSearch(pinterestAccountId: Long): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val account = dbHelper.getPinterestAccountById(pinterestAccountId)
                ?: return@withContext Pair(0, "Pinterest account not found")

            val keyword = getRandomKeyword()
            Log.d(TAG, "Executing Pinterest Search for account '${account.username}' with keyword: '$keyword'")

            // Push notification: Search started
            notificationHelper.notifySearchStarted(account.username, keyword)

            // Initial Search (PinteresetSearch.txt)
            val initialResult = pinterestClient.searchInitial(account, keyword)
            var totalSaved = 0

            for (pin in initialResult.pins) {
                val rowId = dbHelper.insertSavedPin(pin)
                if (rowId != -1L) totalSaved++
            }

            // Pagination Search (PinterestSearchWithPagination.txt)
            if (!initialResult.bookmark.isNullOrBlank()) {
                Log.d(TAG, "Found bookmark, running pagination search...")
                val paginatedResult = pinterestClient.searchWithPagination(
                    account,
                    keyword,
                    initialResult.bookmark
                )
                for (pin in paginatedResult.pins) {
                    val rowId = dbHelper.insertSavedPin(pin)
                    if (rowId != -1L) totalSaved++
                }
            }

            val statusMsg = "Found and saved $totalSaved pins for '$keyword'"
            val nextRun = System.currentTimeMillis() + (account.scheduleIntervalHours * 3600 * 1000L)
            dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), statusMsg, nextRun)

            // Push notification: Search completed
            notificationHelper.notifySearchCompleted(account.username, keyword, totalSaved)

            Pair(totalSaved, statusMsg)
        }

    /**
     * Requirement 5: Download each pin and post on Instagram with title as caption
     * (or search text as fallback if title is empty).
     */
    suspend fun executeInstagramPost(
        pinterestAccountId: Long,
        limit: Int = 1
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pinAccount = dbHelper.getPinterestAccountById(pinterestAccountId)
            ?: return@withContext Pair(0, "Pinterest account not found")

        val instaAccountId = pinAccount.linkedInstagramAccountId
            ?: return@withContext Pair(0, "No Instagram account linked to Pinterest account '${pinAccount.username}'")

        val instaAccount = dbHelper.getInstagramAccountById(instaAccountId)
            ?: return@withContext Pair(0, "Linked Instagram account not found in database")

        val unpostedPins = dbHelper.getUnpostedPins(limit)
        if (unpostedPins.isEmpty()) {
            return@withContext Pair(0, "No pending pins to post")
        }

        var successCount = 0
        val summaryMessages = mutableListOf<String>()

        for (pin in unpostedPins) {
            val rawCaption = if (pin.title.isNotBlank()) pin.title else pin.description.ifEmpty { pin.searchTerm }
            val caption = InstagramCaptionUtils.cleanAndFormatCaption(rawCaption)
            Log.d(TAG, "Posting pin ${pin.pinId} to Instagram (@${instaAccount.username}) with caption: '$caption' (raw: '$rawCaption')")

            // Push notification: Post started
            notificationHelper.notifyPostStarted(pin.pinId, instaAccount.username)

            val imageUrl = pin.getEffectiveImageUrl()
            if (imageUrl.isBlank()) {
                val err = "No valid image format found for Pin ${pin.pinId} (raw media was ${pin.mediaType})"
                dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
                summaryMessages.add("Pin ${pin.pinId}: No valid image")
                notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, err)
                continue
            }

            val mediaBytes = instagramClient.downloadMedia(imageUrl)
            if (mediaBytes == null || mediaBytes.isEmpty()) {
                val err = "Failed downloading pin image from $imageUrl"
                dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
                summaryMessages.add("Pin ${pin.pinId}: Download failed")
                notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, "Image download failed")
                continue
            }

            val postResult = instagramClient.postPhoto(instaAccount, mediaBytes, caption)
            if (postResult.success) {
                val postUrl = postResult.postUrl
                dbHelper.markPinAsPosted(pin.pinId, instaAccountId, postUrl = postUrl)
                dbHelper.insertPostLog(
                    PostLog(
                        pinId = pin.pinId,
                        instagramAccountId = instaAccountId,
                        status = "SUCCESS",
                        message = postResult.message,
                        postUrl = postUrl
                    )
                )
                successCount++
                summaryMessages.add("Pin ${pin.pinId}: Posted successfully")

                // Push notification: Post succeeded with reel/post URL and account ID
                notificationHelper.notifyPostSuccess(
                    pinId = pin.pinId,
                    igUsername = instaAccount.username,
                    caption = caption,
                    postUrl = postUrl,
                    instagramAccountId = instaAccountId
                )
            } else {
                dbHelper.insertPostLog(
                    PostLog(
                        pinId = pin.pinId,
                        instagramAccountId = instaAccountId,
                        status = "FAILED",
                        message = postResult.message
                    )
                )
                summaryMessages.add("Pin ${pin.pinId}: ${postResult.message}")

                // Push notification: Post failed
                notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, postResult.message)
            }
        }

        Pair(successCount, summaryMessages.joinToString("; "))
    }

    // --- ACCOUNT IMPORT & EXPORT ---
    data class ImportResult(
        val pinterestImported: Int,
        val instagramImported: Int,
        val message: String
    )

    suspend fun exportAccountsJson(): String = withContext(Dispatchers.IO) {
        val pinterestAccounts = dbHelper.getAllPinterestAccounts()
        val instagramAccounts = dbHelper.getAllInstagramAccounts()

        val root = JSONObject().apply {
            put("appName", "CrossAIStudio")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())

            val pinArray = JSONArray()
            for (acc in pinterestAccounts) {
                pinArray.put(JSONObject().apply {
                    put("username", acc.username)
                    put("displayName", acc.displayName)
                    put("profilePicUrl", acc.profilePicUrl)
                    put("cookies", acc.cookies)
                    put("csrfToken", acc.csrfToken)
                    put("status", acc.status)
                    put("scheduleIntervalHours", acc.scheduleIntervalHours)
                    put("isAutoPostEnabled", acc.isAutoPostEnabled)
                })
            }
            put("pinterestAccounts", pinArray)

            val instaArray = JSONArray()
            for (acc in instagramAccounts) {
                instaArray.put(JSONObject().apply {
                    put("username", acc.username)
                    put("displayName", acc.displayName)
                    put("profilePicUrl", acc.profilePicUrl)
                    put("cookies", acc.cookies)
                    put("csrfToken", acc.csrfToken)
                    put("dsUserId", acc.dsUserId)
                    put("status", acc.status)
                })
            }
            put("instagramAccounts", instaArray)
        }

        root.toString(2)
    }

    suspend fun importAccountsJson(jsonString: String): ImportResult = withContext(Dispatchers.IO) {
        var pinCount = 0
        var instaCount = 0
        try {
            val root = JSONObject(jsonString)

            // Import Instagram Accounts first
            val instaArray = root.optJSONArray("instagramAccounts")
            if (instaArray != null) {
                for (i in 0 until instaArray.length()) {
                    val obj = instaArray.optJSONObject(i) ?: continue
                    val username = obj.optString("username").trim()
                    val cookies = obj.optString("cookies").trim()
                    if (username.isBlank() || cookies.isBlank()) continue

                    val account = InstagramAccount(
                        username = username,
                        displayName = obj.optString("displayName", username),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        cookies = cookies,
                        csrfToken = obj.optString("csrfToken", ""),
                        dsUserId = obj.optString("dsUserId", ""),
                        status = obj.optString("status", "ACTIVE")
                    )
                    dbHelper.insertOrUpdateInstagramAccount(account)
                    instaCount++
                }
            }

            // Import Pinterest Accounts
            val pinArray = root.optJSONArray("pinterestAccounts")
            if (pinArray != null) {
                for (i in 0 until pinArray.length()) {
                    val obj = pinArray.optJSONObject(i) ?: continue
                    val username = obj.optString("username").trim()
                    val cookies = obj.optString("cookies").trim()
                    if (username.isBlank() || cookies.isBlank()) continue

                    val account = PinterestAccount(
                        username = username,
                        displayName = obj.optString("displayName", username),
                        profilePicUrl = obj.optString("profilePicUrl", ""),
                        cookies = cookies,
                        csrfToken = obj.optString("csrfToken", ""),
                        status = obj.optString("status", "ACTIVE"),
                        scheduleIntervalHours = obj.optInt("scheduleIntervalHours", 24),
                        isAutoPostEnabled = obj.optBoolean("isAutoPostEnabled", false)
                    )
                    dbHelper.insertOrUpdatePinterestAccount(account)
                    pinCount++
                }
            }

            ImportResult(
                pinterestImported = pinCount,
                instagramImported = instaCount,
                message = "Imported $pinCount Pinterest and $instaCount Instagram account(s) successfully"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import accounts JSON", e)
            ImportResult(
                pinterestImported = 0,
                instagramImported = 0,
                message = "Failed to parse JSON: ${e.message}"
            )
        }
    }
}
