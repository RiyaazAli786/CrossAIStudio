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
        dbHelper.insertPostLog(
            PostLog(
                pinId = "ACCOUNT",
                instagramAccountId = null,
                status = "SUCCESS",
                message = "Pinterest account '@${account.username}' saved (Auto Post: ${if (account.isAutoPostEnabled) "Enabled" else "Disabled"})"
            )
        )
        id
    }

    suspend fun updatePinterestAccount(account: PinterestAccount): Long = withContext(Dispatchers.IO) {
        dbHelper.insertOrUpdatePinterestAccount(account)
    }

    suspend fun updatePinterestAccountSchedule(
        id: Long,
        linkedInstaId: Long?,
        intervalHours: Int,
        isEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        dbHelper.updatePinterestAccountSchedule(id, linkedInstaId, intervalHours, isEnabled)
        val acc = dbHelper.getPinterestAccountById(id)
        if (acc != null) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "SCHEDULE",
                    instagramAccountId = linkedInstaId,
                    status = "INFO",
                    message = "Updated schedule for '@${acc.username}': Interval ${intervalHours}h, Auto Post ${if (isEnabled) "ON" else "OFF"}"
                )
            )
        }
    }

    suspend fun deletePinterestAccount(id: Long): Boolean = withContext(Dispatchers.IO) {
        val acc = dbHelper.getPinterestAccountById(id)
        val success = dbHelper.deletePinterestAccount(id) > 0
        if (success && acc != null) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "ACCOUNT",
                    instagramAccountId = null,
                    status = "INFO",
                    message = "Removed Pinterest account '@${acc.username}'"
                )
            )
        }
        success
    }

    suspend fun checkPinterestAccountStatus(account: PinterestAccount): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val res = pinterestClient.checkAccountStatus(account)
            val newStatus = if (res.first) "ACTIVE" else "EXPIRED"
            dbHelper.updatePinterestAccountStatus(account.id, newStatus)
            notificationHelper.notifyStatusAlert(account.username, "Pinterest", res.second)
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "STATUS",
                    instagramAccountId = null,
                    status = if (res.first) "SUCCESS" else "FAILED",
                    message = "Checked Pinterest status for '@${account.username}': ${res.second}"
                )
            )
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
        dbHelper.insertPostLog(
            PostLog(
                pinId = "ACCOUNT",
                instagramAccountId = id,
                status = "SUCCESS",
                message = "Instagram account '@${account.username}' session active & saved"
            )
        )
        id
    }

    suspend fun deleteInstagramAccount(id: Long): Boolean = withContext(Dispatchers.IO) {
        val acc = dbHelper.getInstagramAccountById(id)
        val success = dbHelper.deleteInstagramAccount(id) > 0
        if (success && acc != null) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "ACCOUNT",
                    instagramAccountId = id,
                    status = "INFO",
                    message = "Removed Instagram account '@${acc.username}'"
                )
            )
        }
        success
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
        val id = dbHelper.addSearchKeyword(keyword)
        if (id != -1L) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "KEYWORD",
                    status = "INFO",
                    message = "Added search keyword '$keyword'"
                )
            )
        }
        id
    }

    suspend fun deleteKeyword(id: Long): Boolean = withContext(Dispatchers.IO) {
        val deleted = dbHelper.deleteSearchKeyword(id) > 0
        if (deleted) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "KEYWORD",
                    status = "INFO",
                    message = "Removed search keyword (ID: $id)"
                )
            )
        }
        deleted
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
        val deleted = dbHelper.deleteSavedPin(pinId) > 0
        if (deleted) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pinId,
                    status = "INFO",
                    message = "Deleted saved pin $pinId"
                )
            )
        }
        deleted
    }

    // --- LOGS ---
    suspend fun getRecentLogs(): List<PostLog> = withContext(Dispatchers.IO) {
        dbHelper.getRecentPostLogs(50)
    }

    suspend fun insertActivityLog(
        tag: String,
        message: String,
        status: String = "INFO",
        postUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        dbHelper.insertPostLog(
            PostLog(
                pinId = tag,
                status = status,
                message = message,
                postUrl = postUrl
            )
        )
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

            // Push notification & activity log: Search started
            notificationHelper.notifySearchStarted(account.username, keyword)
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "SEARCH",
                    instagramAccountId = account.linkedInstagramAccountId,
                    status = "INFO",
                    message = "Started Pinterest search with keyword '$keyword' for @${account.username}"
                )
            )

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

            // Push notification & activity log: Search completed
            notificationHelper.notifySearchCompleted(account.username, keyword, totalSaved)
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "SEARCH",
                    instagramAccountId = account.linkedInstagramAccountId,
                    status = if (totalSaved > 0) "SUCCESS" else "INFO",
                    message = "Completed Pinterest search: saved $totalSaved pins for '$keyword'"
                )
            )

            Pair(totalSaved, statusMsg)
        }

    /**
     * Requirement 3 & 5: Publishes a single pin to Instagram.
     * Can be invoked manually from Pin Card ('Publish Now') or from automation loops.
     */
    suspend fun postSinglePin(
        pin: SavedPin,
        targetInstagramAccountId: Long? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val instaAccountId = targetInstagramAccountId ?: run {
            val pinAccount = dbHelper.getPinterestAccountById(pin.pinterestAccountId)
            pinAccount?.linkedInstagramAccountId ?: dbHelper.getAllInstagramAccounts().firstOrNull()?.id
        }

        if (instaAccountId == null) {
            val err = "No Instagram account linked to post Pin ${pin.pinId}"
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pin.pinId,
                    instagramAccountId = null,
                    status = "FAILED",
                    message = err
                )
            )
            return@withContext Pair(false, err)
        }

        val instaAccount = dbHelper.getInstagramAccountById(instaAccountId)
        if (instaAccount == null) {
            val err = "Target Instagram account not found in database"
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pin.pinId,
                    instagramAccountId = instaAccountId,
                    status = "FAILED",
                    message = err
                )
            )
            return@withContext Pair(false, err)
        }

        val rawCaption = if (pin.title.isNotBlank()) pin.title else pin.description.ifEmpty { pin.searchTerm }
        val caption = InstagramCaptionUtils.cleanAndFormatCaption(rawCaption)
        Log.d(TAG, "Posting pin ${pin.pinId} to @${instaAccount.username} with caption: '$caption'")

        // Push notification & activity log: Post started
        notificationHelper.notifyPostStarted(pin.pinId, instaAccount.username)
        dbHelper.insertPostLog(
            PostLog(
                pinId = pin.pinId,
                instagramAccountId = instaAccountId,
                status = "INFO",
                message = "Started uploading Pin ${pin.pinId} to @${instaAccount.username}"
            )
        )

        val imageUrl = pin.getEffectiveImageUrl()
        if (imageUrl.isBlank()) {
            val err = "No valid image format found for Pin ${pin.pinId} (media: ${pin.mediaType})"
            dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
            notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, err)
            return@withContext Pair(false, err)
        }

        val mediaBytes = instagramClient.downloadMedia(imageUrl)
        if (mediaBytes == null || mediaBytes.isEmpty()) {
            val err = "Failed downloading pin media from $imageUrl"
            dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
            notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, "Image download failed")
            return@withContext Pair(false, err)
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

            // Push notification: Post succeeded with post URL
            notificationHelper.notifyPostSuccess(
                pinId = pin.pinId,
                igUsername = instaAccount.username,
                caption = caption,
                postUrl = postUrl,
                instagramAccountId = instaAccountId
            )
            Pair(true, postResult.message)
        } else {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pin.pinId,
                    instagramAccountId = instaAccountId,
                    status = "FAILED",
                    message = postResult.message
                )
            )
            notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, postResult.message)
            Pair(false, postResult.message)
        }
    }

    /**
     * Requirement 5: Download each pin and post on Instagram with title as caption
     */
    suspend fun executeInstagramPost(
        pinterestAccountId: Long,
        limit: Int = 1
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pinAccount = dbHelper.getPinterestAccountById(pinterestAccountId)
            ?: return@withContext Pair(0, "Pinterest account not found")

        val instaAccountId = pinAccount.linkedInstagramAccountId
            ?: return@withContext Pair(0, "No Instagram account linked to Pinterest account '${pinAccount.username}'")

        val unpostedPins = dbHelper.getUnpostedPins(limit)
        if (unpostedPins.isEmpty()) {
            return@withContext Pair(0, "No pending pins to post")
        }

        var successCount = 0
        val summaryMessages = mutableListOf<String>()

        for (pin in unpostedPins) {
            val res = postSinglePin(pin, instaAccountId)
            if (res.first) {
                successCount++
                summaryMessages.add("Pin ${pin.pinId}: Posted successfully")
            } else {
                summaryMessages.add("Pin ${pin.pinId}: ${res.second}")
            }
        }

        Pair(successCount, summaryMessages.joinToString("; "))
    }

    /**
     * Requirement 2:
     * When auto post scheduled time triggers or 'Run Now' is clicked:
     * Checks if there are pending pins available to publish.
     * - If available: publishes the existing item first (does NOT search Pinterest).
     * - If no items available: searches Pinterest using keyword, saves results, and publishes the first one.
     */
    suspend fun executePublishOrSearchFlow(pinterestAccountId: Long): Pair<Int, String> = withContext(Dispatchers.IO) {
        val account = dbHelper.getPinterestAccountById(pinterestAccountId)
            ?: return@withContext Pair(0, "Pinterest account not found")

        val unpostedPins = dbHelper.getUnpostedPins(1)

        if (unpostedPins.isNotEmpty()) {
            val pendingPin = unpostedPins.first()
            val totalPending = dbHelper.getUnpostedPins(50).size
            Log.d(TAG, "Pending pin available ($totalPending total). Publishing pending pin first: ${pendingPin.pinId}")

            dbHelper.insertPostLog(
                PostLog(
                    pinId = pendingPin.pinId,
                    instagramAccountId = account.linkedInstagramAccountId,
                    status = "INFO",
                    message = "Publishing available saved pin '${InstagramCaptionUtils.safeTruncate(pendingPin.title.ifEmpty { pendingPin.searchTerm }, 40)}' ($totalPending remaining)"
                )
            )

            val postRes = postSinglePin(pendingPin, account.linkedInstagramAccountId)
            val nextRun = System.currentTimeMillis() + (account.scheduleIntervalHours * 3600 * 1000L)
            val statusMsg = if (postRes.first) {
                "Published available pin ${pendingPin.pinId} (${totalPending - 1} remaining)"
            } else {
                "Failed publishing pin ${pendingPin.pinId}: ${postRes.second}"
            }
            dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), statusMsg, nextRun)
            Pair(if (postRes.first) 1 else 0, statusMsg)
        } else {
            // No items available -> start Pinterest search!
            Log.d(TAG, "No pending pins available. Starting Pinterest search for account '@${account.username}'")
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "SEARCH",
                    instagramAccountId = account.linkedInstagramAccountId,
                    status = "INFO",
                    message = "No pending pins found. Searching Pinterest for account '@${account.username}'..."
                )
            )

            val searchRes = executePinterestSearch(account.id)
            if (searchRes.first > 0 && account.linkedInstagramAccountId != null) {
                val newlyFound = dbHelper.getUnpostedPins(1)
                if (newlyFound.isNotEmpty()) {
                    val postRes = postSinglePin(newlyFound.first(), account.linkedInstagramAccountId)
                    val nextRun = System.currentTimeMillis() + (account.scheduleIntervalHours * 3600 * 1000L)
                    val statusMsg = "Searched and saved ${searchRes.first} pins. Published pin ${newlyFound.first().pinId}."
                    dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), statusMsg, nextRun)
                    return@withContext Pair(1, statusMsg)
                }
            }

            searchRes
        }
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

        val jsonOutput = root.toString(2)
        dbHelper.insertPostLog(
            PostLog(
                pinId = "BACKUP",
                status = "INFO",
                message = "Exported backup: ${pinterestAccounts.size} Pinterest and ${instagramAccounts.size} Instagram account(s)"
            )
        )
        jsonOutput
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
                        scheduleIntervalHours = obj.optInt("scheduleIntervalHours", 1),
                        isAutoPostEnabled = obj.optBoolean("isAutoPostEnabled", true)
                    )
                    dbHelper.insertOrUpdatePinterestAccount(account)
                    pinCount++
                }
            }

            dbHelper.insertPostLog(
                PostLog(
                    pinId = "BACKUP",
                    status = "SUCCESS",
                    message = "Imported $pinCount Pinterest and $instaCount Instagram account(s) successfully"
                )
            )

            ImportResult(
                pinterestImported = pinCount,
                instagramImported = instaCount,
                message = "Imported $pinCount Pinterest and $instaCount Instagram account(s) successfully"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import accounts JSON", e)
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "BACKUP",
                    status = "FAILED",
                    message = "Failed to import accounts: ${e.message}"
                )
            )
            ImportResult(
                pinterestImported = 0,
                instagramImported = 0,
                message = "Failed to parse JSON: ${e.message}"
            )
        }
    }
}
