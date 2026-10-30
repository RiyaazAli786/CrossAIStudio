package com.pintoinsta.app.data.repository

import android.content.Context
import android.util.Log
import com.pintoinsta.app.data.db.AppDatabaseHelper
import com.pintoinsta.app.data.model.InstagramAccount
import com.pintoinsta.app.data.model.PinterestAccount
import com.pintoinsta.app.data.model.PostLog
import com.pintoinsta.app.data.model.SavedPin
import com.pintoinsta.app.data.model.SearchKeyword
import com.pintoinsta.app.data.model.OverallAnalytics
import com.pintoinsta.app.network.InstagramClient
import com.pintoinsta.app.network.PinterestClient
import com.pintoinsta.app.notification.NotificationHelper
import com.pintoinsta.app.util.InstagramCaptionUtils
import com.pintoinsta.app.util.PostingConfigManager
import com.pintoinsta.app.util.SmartCaptionEngine
import com.pintoinsta.app.util.VideoMuxerUtils
import com.pintoinsta.app.util.CookieUtils
import com.pintoinsta.app.util.NetworkUtils
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class AppRepository(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val pinterestClient = PinterestClient()
    private val instagramClient = InstagramClient()
    private val notificationHelper = NotificationHelper.getInstance(context)
    private val publishMutex = Mutex()

    val logUpdates = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    fun notifyLogUpdated() {
        logUpdates.tryEmit(Unit)
    }

    init {
        AppDatabaseHelper.onLogInsertedCallback = {
            notifyLogUpdated()
        }
    }

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
        isEnabled: Boolean,
        linkedInstaIds: String = "",
        dailyPostTimes: String = "09:00,14:00,20:00"
    ) = withContext(Dispatchers.IO) {
        dbHelper.updatePinterestAccountSchedule(id, linkedInstaId, intervalHours, isEnabled, linkedInstaIds, dailyPostTimes)
        val acc = dbHelper.getPinterestAccountById(id)
        if (acc != null) {
            val countMsg = if (linkedInstaIds.isNotBlank()) {
                "${linkedInstaIds.split(',').size} Instagram account(s) linked"
            } else if (linkedInstaId != null) {
                "1 Instagram account linked"
            } else {
                "No Instagram account linked"
            }
            val timesMsg = "${acc.getDailyPostTimeList().size} post(s)/day (${acc.dailyPostTimes})"
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "SCHEDULE",
                    instagramAccountId = linkedInstaId,
                    status = "INFO",
                    message = "Updated schedule for '@${acc.username}': Times: $timesMsg, Auto Post ${if (isEnabled) "ON" else "OFF"}, $countMsg"
                )
            )
        }
    }

    suspend fun updatePinterestAccountRunInfo(id: Long, lastRunTime: Long, lastRunStatus: String, nextRunTime: Long) = withContext(Dispatchers.IO) {
        dbHelper.updatePinterestAccountRunInfo(id, lastRunTime, lastRunStatus, nextRunTime)
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
            ""
        }
    }

    // --- PINS ---
    suspend fun getAllSavedPins(): List<SavedPin> = withContext(Dispatchers.IO) {
        dbHelper.getAllSavedPins()
    }

    suspend fun getUnpostedPins(limit: Int = 10): List<SavedPin> = withContext(Dispatchers.IO) {
        dbHelper.getUnpostedPins(limit)
    }

    suspend fun savePin(pin: SavedPin): Boolean = withContext(Dispatchers.IO) {
        val rowId = dbHelper.insertSavedPin(pin)
        val success = rowId != -1L
        if (success) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pin.pinId,
                    status = "SUCCESS",
                    message = "Saved pin '${pin.title.ifEmpty { pin.pinId }}' to vault"
                )
            )
        }
        success
    }

    suspend fun savePins(pins: List<SavedPin>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (pin in pins) {
            val rowId = dbHelper.insertSavedPin(pin)
            if (rowId != -1L) {
                count++
            }
        }
        if (count > 0) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "BATCH",
                    status = "SUCCESS",
                    message = "Saved $count pins to vault from custom search"
                )
            )
        }
        count
    }

    suspend fun isPinSaved(pinId: String): Boolean = withContext(Dispatchers.IO) {
        dbHelper.getSavedPinById(pinId) != null
    }

    suspend fun getSavedPinIds(): Set<String> = withContext(Dispatchers.IO) {
        dbHelper.getAllSavedPins().map { it.pinId }.toSet()
    }

    suspend fun searchPinterestCustom(
        account: PinterestAccount?,
        query: String,
        bookmark: String? = null,
        scope: String = "pins"
    ): PinterestClient.SearchResult = withContext(Dispatchers.IO) {
        // If account is null, try using first active account from database
        val effectiveAccount = account ?: dbHelper.getAllPinterestAccounts().firstOrNull { it.status == "ACTIVE" }
            ?: dbHelper.getAllPinterestAccounts().firstOrNull()

        if (bookmark.isNullOrBlank()) {
            pinterestClient.searchInitial(effectiveAccount, query, scope)
        } else {
            pinterestClient.searchWithPagination(effectiveAccount, query, bookmark, scope)
        }
    }

    suspend fun deleteSavedPin(pinId: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = dbHelper.deleteSavedPin(pinId) > 0
        if (deleted) {
            try {
                val cacheDir = File(context.cacheDir, "pin_videos")
                File(cacheDir, "pin_${pinId}.mp4").delete()
            } catch (_: Exception) {}
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

    suspend fun deleteSavedPins(
        pinIds: List<String>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val total = pinIds.size
        val cacheDir = File(context.cacheDir, "pin_videos")
        for ((idx, pinId) in pinIds.withIndex()) {
            if (dbHelper.deleteSavedPin(pinId) > 0) {
                count++
            }
            try {
                File(cacheDir, "pin_${pinId}.mp4").delete()
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                onProgress?.invoke(idx + 1, total)
            }
        }
        if (count > 0) {
            dbHelper.insertPostLog(
                PostLog(
                    pinId = "BATCH",
                    status = "INFO",
                    message = "Bulk deleted $count saved pins"
                )
            )
        }
        count
    }

    // --- LOGS ---
    suspend fun getRecentLogs(): List<PostLog> = withContext(Dispatchers.IO) {
        dbHelper.getRecentPostLogs(50)
    }

    suspend fun clearAllLogs(): Boolean = withContext(Dispatchers.IO) {
        dbHelper.clearAllPostLogs() >= 0
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
    suspend fun executePinterestSearch(
        pinterestAccountId: Long,
        specificKeyword: String? = null
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val account = dbHelper.getPinterestAccountById(pinterestAccountId)
                ?: return@withContext Pair(0, "Pinterest account not found")

            val keyword = specificKeyword?.trim()?.takeIf { it.isNotEmpty() } ?: getRandomKeyword()
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
    /**
     * Downloads video media for a pin, muxing CMAF video and audio tracks if needed,
     * producing a clean, standard MP4 file.
     */
    suspend fun downloadVideoForPin(pin: SavedPin): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "pin_videos").apply { mkdirs() }
        val outputMp4 = File(cacheDir, "pin_${pin.pinId}.mp4")

        // Helper to check for genuine MP4 container (starts with 'ftyp' at offset 4)
        fun isRealMp4(file: File): Boolean {
            if (!file.exists() || file.length() < 1000) return false
            return try {
                file.inputStream().use { stream ->
                    val header = ByteArray(12)
                    val read = stream.read(header)
                    if (read >= 8) {
                        val magic = String(header, 4, 4, Charsets.US_ASCII)
                        magic == "ftyp"
                    } else false
                }
            } catch (_: Exception) {
                false
            }
        }

        // Fast path: cached binary MP4 is already valid
        if (isRealMp4(outputMp4)) {
            Log.d(TAG, "Reusing cached valid video for Pin ${pin.pinId} (${outputMp4.length()} bytes)")
            return@withContext outputMp4
        } else if (outputMp4.exists()) {
            outputMp4.delete()
        }

        try {
            val candidates = pin.getVideoTrackCandidates()
            if (candidates.isEmpty()) {
                Log.w(TAG, "No video track candidate URLs found for Pin ${pin.pinId}")
                return@withContext null
            }

            var videoBytes: ByteArray? = null
            for (url in candidates) {
                Log.d(TAG, "Attempting video track download for Pin ${pin.pinId}: $url")
                val bytes = instagramClient.downloadMedia(url)
                if (bytes != null && bytes.size > 1024) {
                    videoBytes = bytes
                    break
                }
            }

            if (videoBytes == null) {
                Log.w(TAG, "Failed downloading video track from any candidate for Pin ${pin.pinId}")
                return@withContext null
            }

            val tempVideoFile = File(cacheDir, "temp_vid_${pin.pinId}.cmfv")
            tempVideoFile.writeBytes(videoBytes)

            val audioTrackUrl = pin.getAudioTrackUrl()
            var tempAudioFile: File? = null
            if (!audioTrackUrl.isNullOrBlank()) {
                Log.d(TAG, "Downloading audio track for Pin ${pin.pinId}: $audioTrackUrl")
                val audioBytes = instagramClient.downloadMedia(audioTrackUrl)
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    tempAudioFile = File(cacheDir, "temp_aud_${pin.pinId}.cmfa")
                    tempAudioFile.writeBytes(audioBytes)
                }
            }

            val muxSuccess = VideoMuxerUtils.mux(tempVideoFile, tempAudioFile, outputMp4)

            try { tempVideoFile.delete() } catch (_: Exception) {}
            try { tempAudioFile?.delete() } catch (_: Exception) {}

            if (muxSuccess && isRealMp4(outputMp4)) {
                Log.d(TAG, "Video prepared successfully for Pin ${pin.pinId} (${outputMp4.length()} bytes)")
                outputMp4
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/preparing video for Pin ${pin.pinId}", e)
            null
        }
    }

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

        val isSmart = PostingConfigManager.isSmartCaptionsEnabled(context)
        val isFirstComment = PostingConfigManager.isFirstCommentHashtagsEnabled(context)
        val isCredit = PostingConfigManager.isCreditAttributionEnabled(context)

        val preparedCaption = SmartCaptionEngine.buildCaption(
            rawTitle = pin.title,
            description = pin.description,
            searchTerm = pin.searchTerm,
            isSmartCaptionEnabled = isSmart,
            isFirstCommentEnabled = isFirstComment,
            isCreditAttributionEnabled = isCredit
        )
        val caption = preparedCaption.mainCaption
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

        val isVideo = pin.isVideoPin()
        var postResult: InstagramClient.InstagramPostResponse? = null

        if (isVideo) {
            Log.d(TAG, "Pin ${pin.pinId} is a VIDEO pin. Downloading and publishing as Reel...")
            val videoFile = downloadVideoForPin(pin)
            if (videoFile != null && videoFile.exists() && videoFile.length() > 1024) {
                val videoBytes = videoFile.readBytes()
                val coverBytes = instagramClient.downloadMedia(pin.getEffectiveImageUrl())

                var durationMs = 15000L
                var width = 720
                var height = 1280
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(videoFile.absolutePath)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (w != null && w > 0) width = w
                    if (h != null && h > 0) height = h
                    if (d != null && d > 0) durationMs = d
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Metadata extraction warning: ${e.message}")
                }

                postResult = instagramClient.postVideo(
                    account = instaAccount,
                    videoBytes = videoBytes,
                    coverImageBytes = coverBytes,
                    caption = caption,
                    durationMs = durationMs,
                    width = width,
                    height = height
                )

                if (!postResult.success) {
                    Log.w(TAG, "Initial video upload warning: ${postResult.message}. Retrying once...")
                    delay(3000L)
                    postResult = instagramClient.postVideo(
                        account = instaAccount,
                        videoBytes = videoBytes,
                        coverImageBytes = coverBytes,
                        caption = caption,
                        durationMs = durationMs,
                        width = width,
                        height = height
                    )
                }
            } else {
                val err = "Could not obtain video for Pin ${pin.pinId}"
                Log.e(TAG, err)
                dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
                notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, err)
                return@withContext Pair(false, err)
            }

            // CRITICAL FIX: If this is a video pin, NEVER fall back to posting a photo!
            // Falling back causes double posts (first the video, then the image for the exact same video).
            if (postResult == null || !postResult.success) {
                val err = postResult?.message ?: "Reel video upload failed for Pin ${pin.pinId}"
                Log.e(TAG, err)
                dbHelper.insertPostLog(PostLog(pinId = pin.pinId, instagramAccountId = instaAccountId, status = "FAILED", message = err))
                notificationHelper.notifyPostFailed(pin.pinId, instaAccount.username, err)
                return@withContext Pair(false, err)
            }
        } else {
            // For standard photo pins, post as photo
            val imageUrl = pin.getEffectiveImageUrl()
            if (imageUrl.isBlank()) {
                val err = "No valid media format found for Pin ${pin.pinId} (media: ${pin.mediaType})"
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

            postResult = instagramClient.postPhoto(instaAccount, mediaBytes, caption)
        }

        if (postResult.success) {
            val postUrl = postResult.postUrl
            val targetUser = instaAccount.username
            pin.isPosted = true
            pin.postUrl = postUrl
            pin.publishedTo = targetUser
            pin.instagramAccountId = instaAccountId
            pin.postedAt = System.currentTimeMillis()
            dbHelper.markPinAsPosted(pin.pinId, instaAccountId, postUrl = postUrl, publishedToUsername = targetUser)
            dbHelper.insertPostLog(
                PostLog(
                    pinId = pin.pinId,
                    instagramAccountId = instaAccountId,
                    status = "SUCCESS",
                    message = "${postResult.message} (@$targetUser)",
                    postUrl = postUrl
                )
            )

            // Auto First-Comment with targeted hashtags
            val firstCommentText = preparedCaption.firstComment
            val mediaId = postResult.mediaId
            if (!firstCommentText.isNullOrBlank() && !mediaId.isNullOrBlank()) {
                Log.d(TAG, "Posting first comment with hashtags on media $mediaId to @$targetUser...")
                delay(1500L) // Brief pause to ensure media indexing on Instagram servers
                val commentRes = instagramClient.postComment(instaAccount, mediaId, firstCommentText)
                if (commentRes.first) {
                    dbHelper.insertPostLog(
                        PostLog(
                            pinId = pin.pinId,
                            instagramAccountId = instaAccountId,
                            status = "SUCCESS",
                            message = "First comment with ${preparedCaption.hashtagsUsed.size} hashtags posted to @$targetUser",
                            postUrl = postUrl
                        )
                    )
                } else {
                    Log.w(TAG, "First comment posting warning: ${commentRes.second}")
                }
            }

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
     * Requirement 1: Publishes a pin across multiple selected Instagram accounts.
     * If multiple accounts are selected, it publishes with a random delay between 5 and 10 minutes
     * between each account post to protect account reputation.
     */
    suspend fun postPinToMultipleAccounts(
        pin: SavedPin,
        instagramAccountIds: List<Long>,
        onProgress: ((current: Int, total: Int, message: String) -> Unit)? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val validIds = instagramAccountIds.distinct()
        if (validIds.isEmpty()) {
            return@withContext Pair(0, "No Instagram accounts selected for posting")
        }

        var successCount = 0
        val results = mutableListOf<String>()

        for ((index, instaId) in validIds.withIndex()) {
            val instaAccount = dbHelper.getInstagramAccountById(instaId)
            val username = instaAccount?.username ?: "Account_$instaId"

            // If this is a subsequent account, wait random delay between 5 and 10 minutes (300 - 600 seconds)
            if (index > 0) {
                val delaySeconds = Random.nextLong(300, 601) // 5 to 10 minutes
                val delayMinutes = delaySeconds / 60
                val delayMsg = "Waiting random delay of ${delayMinutes}m before posting Pin ${pin.pinId} to @$username..."
                Log.d(TAG, delayMsg)
                dbHelper.insertPostLog(
                    PostLog(
                        pinId = pin.pinId,
                        instagramAccountId = instaId,
                        status = "INFO",
                        message = delayMsg
                    )
                )
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(index, validIds.size, delayMsg)
                }

                delay(delaySeconds * 1000L)
            }

            withContext(Dispatchers.Main) {
                onProgress?.invoke(index + 1, validIds.size, "Publishing to @$username (${index + 1}/${validIds.size})...")
            }

            val res = postSinglePin(pin, instaId)
            if (res.first) {
                successCount++
                results.add("@$username: Success")
            } else {
                results.add("@$username: Failed (${res.second})")
            }
        }

        Pair(successCount, results.joinToString(", "))
    }

    /**
     * Requirement 5: Download pin and post on Instagram with title as caption (strictly 1 post)
     */
    suspend fun executeInstagramPost(
        pinterestAccountId: Long,
        limit: Int = 1
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pinAccount = dbHelper.getPinterestAccountById(pinterestAccountId)
            ?: return@withContext Pair(0, "Pinterest account not found")

        val targetAccounts = pinAccount.getLinkedInstagramIdList()
        val primaryInstaId = targetAccounts.firstOrNull() ?: pinAccount.linkedInstagramAccountId
            ?: return@withContext Pair(0, "No Instagram account linked to Pinterest account '${pinAccount.username}'")

        val unpostedPins = dbHelper.getUnpostedPins(1)
        if (unpostedPins.isEmpty()) {
            return@withContext Pair(0, "No pending pins to post")
        }

        val targetId = if (targetAccounts.size > 1) {
            targetAccounts.minByOrNull { dbHelper.getLastPostTimeForInstagramAccount(it) } ?: primaryInstaId
        } else {
            primaryInstaId
        }

        val pin = unpostedPins.first()
        val singleRes = postSinglePin(pin, targetId)
        val success = if (singleRes.first) 1 else 0
        Pair(success, "Pin ${pin.pinId}: ${singleRes.second}")
    }

    /**
     * Executes scheduled publish flow or manual 'Run Now':
     * - Only publishes strictly ONE post per execution.
     * - Uses user-saved search keywords from Settings (no hardcoded defaults).
     * - Rotates among linked Instagram accounts if multiple are selected.
     * - Protected by publishMutex against concurrent duplicate posting.
     */
    suspend fun executePublishOrSearchFlow(pinterestAccountId: Long): Pair<Int, String> = withContext(Dispatchers.IO) {
        publishMutex.withLock {
            val account = dbHelper.getPinterestAccountById(pinterestAccountId)
                ?: return@withContext Pair(0, "Pinterest account not found")

            val targetAccounts = account.getLinkedInstagramIdList()
            val primaryTargetId = targetAccounts.firstOrNull() ?: account.linkedInstagramAccountId
            val effectiveTargetAccounts = if (targetAccounts.isNotEmpty()) {
                targetAccounts
            } else {
                listOfNotNull(primaryTargetId)
            }

            // Offline protection: hold post if offline
            if (!NetworkUtils.isOnline(context)) {
                Log.w(TAG, "Device is offline. Holding scheduled post for account '${account.username}' until online.")
                dbHelper.insertPostLog(
                    PostLog(
                        pinId = "OFFLINE",
                        instagramAccountId = primaryTargetId,
                        status = "WARNING",
                        message = "Device is offline. Scheduled post held for @${account.username}; will publish automatically when device comes online."
                    )
                )
                return@withContext Pair(0, "Device is offline. Scheduled post held until connection is restored.")
            }

            if (effectiveTargetAccounts.isEmpty()) {
                val nextRun = account.calculateNextScheduledTime(System.currentTimeMillis())
                val msg = "No Instagram accounts linked to publish pins"
                dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), msg, nextRun)
                return@withContext Pair(0, msg)
            }

            // Select 1 Instagram account (if multiple linked, choose least recently posted)
            val targetInstaId = if (effectiveTargetAccounts.size > 1) {
                effectiveTargetAccounts.minByOrNull { instaId ->
                    dbHelper.getLastPostTimeForInstagramAccount(instaId)
                } ?: effectiveTargetAccounts.first()
            } else {
                effectiveTargetAccounts.first()
            }

            // Advance nextRunTime immediately so other tickers/receivers don't re-trigger
            val tentativeNextRun = account.calculateNextScheduledTime(System.currentTimeMillis() + 60_000L)
            dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), "Publishing in progress...", tentativeNextRun)

            // Strictly 1 unposted pin is published
            var unpostedPins = dbHelper.getUnpostedPins(1)

            // If no pins available, search Pinterest using user's saved keywords
            if (unpostedPins.isEmpty()) {
                val savedKeywords = dbHelper.getAllSearchKeywords()
                    .map { it.keyword.trim() }
                    .filter { it.isNotEmpty() }

                if (savedKeywords.isEmpty()) {
                    val nextRun = account.calculateNextScheduledTime(System.currentTimeMillis())
                    val msg = "No search keywords configured in Settings. Please add keywords in Settings tab."
                    Log.w(TAG, msg)
                    dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), msg, nextRun)
                    dbHelper.insertPostLog(
                        PostLog(
                            pinId = "KEYWORD",
                            instagramAccountId = targetInstaId,
                            status = "WARNING",
                            message = msg
                        )
                    )
                    return@withContext Pair(0, msg)
                }

                val keywordsToTry = savedKeywords.shuffled()
                dbHelper.insertPostLog(
                    PostLog(
                        pinId = "SEARCH",
                        instagramAccountId = targetInstaId,
                        status = "INFO",
                        message = "Searching Pinterest for fresh pins (${keywordsToTry.size} keyword(s) available) for @${account.username}..."
                    )
                )

                for (kw in keywordsToTry) {
                    Log.d(TAG, "Searching Pinterest with saved keyword '$kw'...")
                    val res = executePinterestSearch(account.id, kw)
                    if (res.first > 0) {
                        break
                    }
                }

                // Refresh available unposted pins
                unpostedPins = dbHelper.getUnpostedPins(1)
            }

            if (unpostedPins.isEmpty()) {
                val nextRun = account.calculateNextScheduledTime(System.currentTimeMillis())
                val failMsg = "No pins available to publish and search returned 0 pins."
                dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), failMsg, nextRun)
                return@withContext Pair(0, failMsg)
            }

            // Publish exactly ONE pin to the selected Instagram account
            val pinToPublish = unpostedPins.first()
            val instaAccount = dbHelper.getInstagramAccountById(targetInstaId)
            val igUser = instaAccount?.username ?: "account_$targetInstaId"

            Log.d(TAG, "Publishing single pin ${pinToPublish.pinId} to @$igUser")
            val singleRes = postSinglePin(pinToPublish, targetInstaId)

            val nextRun = account.calculateNextScheduledTime(System.currentTimeMillis())
            val statusMsg = if (singleRes.first) {
                "Published 1 post to @$igUser (${pinToPublish.pinId}): Success"
            } else {
                "Failed publishing to @$igUser (${pinToPublish.pinId}): ${singleRes.second}"
            }
            dbHelper.updatePinterestAccountRunInfo(account.id, System.currentTimeMillis(), statusMsg, nextRun)

            Pair(if (singleRes.first) 1 else 0, statusMsg)
        }
    }

    // --- ANALYTICS ---
    suspend fun getAnalyticsData(): OverallAnalytics = withContext(Dispatchers.IO) {
        dbHelper.getAnalyticsData()
    }

    // --- ACCOUNT IMPORT & EXPORT ---
    data class ImportResult(
        val pinterestImported: Int,
        val instagramImported: Int,
        val keywordsImported: Int = 0,
        val message: String
    )

    suspend fun exportAccountsJson(): String = withContext(Dispatchers.IO) {
        val pinterestAccounts = dbHelper.getAllPinterestAccounts()
        val instagramAccounts = dbHelper.getAllInstagramAccounts()

        // Flush live WebView cookies so CookieManager has the freshest session tokens
        withContext(Dispatchers.Main) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {}
        }
        val liveIgCookies = CookieUtils.getLiveInstagramCookies()
        val livePinCookies = CookieUtils.getLivePinterestCookies()

        val root = JSONObject().apply {
            put("appName", "CrossAIStudio")
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())

            val pinArray = JSONArray()
            val unifiedAccounts = JSONArray()
            val sessionsArray = JSONArray()

            for (acc in pinterestAccounts) {
                // If stored cookies are empty or missing session, attempt fallback to live CookieManager
                val effectiveCookies = if (acc.cookies.isNotBlank() && acc.cookies.contains("_pinterest_sess")) {
                    acc.cookies
                } else if (livePinCookies.contains("_pinterest_sess")) {
                    CookieUtils.mergeCookies(listOf(acc.cookies, livePinCookies))
                } else {
                    acc.cookies
                }

                // If DB had empty/missing cookies but live cookies had them, update DB
                if (effectiveCookies != acc.cookies && effectiveCookies.isNotBlank()) {
                    dbHelper.insertOrUpdatePinterestAccount(acc.copy(cookies = effectiveCookies))
                }

                val sessId = CookieUtils.extractCookieValue(effectiveCookies, "_pinterest_sess")
                val authVal = CookieUtils.extractCookieValue(effectiveCookies, "_auth").ifEmpty { "1" }
                val csrf = acc.csrfToken.ifEmpty { CookieUtils.extractCookieValue(effectiveCookies, "csrftoken") }

                val sessionDataObj = JSONObject().apply {
                    put("_pinterest_sess", sessId)
                    put("sessionId", sessId)
                    put("session", sessId)
                    put("_auth", authVal)
                    put("csrfToken", csrf)
                    put("cookies", effectiveCookies)
                }

                val pinObj = JSONObject().apply {
                    put("platform", "PINTEREST")
                    put("username", acc.username)
                    put("displayName", acc.displayName)
                    put("profilePicUrl", acc.profilePicUrl)
                    put("session", sessId)
                    put("sessionId", sessId)
                    put("session_id", sessId)
                    put("_pinterest_sess", sessId)
                    put("_auth", authVal)
                    put("cookies", effectiveCookies)
                    put("cookie", effectiveCookies)
                    put("csrfToken", csrf)
                    put("sessionData", sessionDataObj)
                    put("hasActiveSession", sessId.isNotBlank())
                    put("status", acc.status)
                    put("scheduleIntervalHours", acc.scheduleIntervalHours)
                    put("isAutoPostEnabled", acc.isAutoPostEnabled)
                    put("linkedInstagramAccountIds", acc.linkedInstagramAccountIds)
                    put("dailyPostTimes", acc.dailyPostTimes)
                }
                pinArray.put(pinObj)
                unifiedAccounts.put(pinObj)

                if (sessId.isNotBlank() || effectiveCookies.isNotBlank()) {
                    sessionsArray.put(JSONObject().apply {
                        put("platform", "PINTEREST")
                        put("username", acc.username)
                        put("session", sessId)
                        put("sessionId", sessId)
                        put("_pinterest_sess", sessId)
                        put("cookies", effectiveCookies)
                        put("csrfToken", csrf)
                    })
                }
            }
            put("pinterestAccounts", pinArray)

            val instaArray = JSONArray()
            for (acc in instagramAccounts) {
                // If stored cookies are empty or missing sessionid, attempt fallback to live CookieManager
                val effectiveCookies = if (acc.cookies.isNotBlank() && acc.cookies.contains("sessionid")) {
                    acc.cookies
                } else if (liveIgCookies.contains("sessionid")) {
                    CookieUtils.mergeCookies(listOf(acc.cookies, liveIgCookies))
                } else {
                    acc.cookies
                }

                if (effectiveCookies != acc.cookies && effectiveCookies.isNotBlank()) {
                    dbHelper.insertOrUpdateInstagramAccount(acc.copy(cookies = effectiveCookies))
                }

                val sessId = CookieUtils.extractCookieValue(effectiveCookies, "sessionid")
                val csrf = acc.csrfToken.ifEmpty { CookieUtils.extractCookieValue(effectiveCookies, "csrftoken") }
                val dsUser = acc.dsUserId.ifEmpty { CookieUtils.extractCookieValue(effectiveCookies, "ds_user_id") }
                val mid = CookieUtils.extractCookieValue(effectiveCookies, "mid")
                val igDid = CookieUtils.extractCookieValue(effectiveCookies, "ig_did")
                val rur = CookieUtils.extractCookieValue(effectiveCookies, "rur")

                val sessionDataObj = JSONObject().apply {
                    put("sessionid", sessId)
                    put("sessionId", sessId)
                    put("session", sessId)
                    put("ds_user_id", dsUser)
                    put("dsUserId", dsUser)
                    put("csrftoken", csrf)
                    put("csrfToken", csrf)
                    put("mid", mid)
                    put("ig_did", igDid)
                    put("rur", rur)
                    put("cookies", effectiveCookies)
                }

                val instaObj = JSONObject().apply {
                    put("platform", "INSTAGRAM")
                    put("username", acc.username)
                    put("displayName", acc.displayName)
                    put("profilePicUrl", acc.profilePicUrl)
                    put("session", sessId)
                    put("sessionId", sessId)
                    put("sessionid", sessId)
                    put("session_id", sessId)
                    put("cookies", effectiveCookies)
                    put("cookie", effectiveCookies)
                    put("csrfToken", csrf)
                    put("dsUserId", dsUser)
                    put("sessionData", sessionDataObj)
                    put("hasActiveSession", sessId.isNotBlank())
                    put("status", acc.status)
                }
                instaArray.put(instaObj)
                unifiedAccounts.put(instaObj)

                if (sessId.isNotBlank() || effectiveCookies.isNotBlank()) {
                    sessionsArray.put(JSONObject().apply {
                        put("platform", "INSTAGRAM")
                        put("username", acc.username)
                        put("session", sessId)
                        put("sessionId", sessId)
                        put("sessionid", sessId)
                        put("dsUserId", dsUser)
                        put("cookies", effectiveCookies)
                        put("csrfToken", csrf)
                    })
                }
            }
            val keywordsList = dbHelper.getAllSearchKeywords()
            val keywordsArray = JSONArray()
            val searchKeywordsArray = JSONArray()
            for (kw in keywordsList) {
                keywordsArray.put(kw.keyword)
                searchKeywordsArray.put(JSONObject().apply {
                    put("id", kw.id)
                    put("keyword", kw.keyword)
                    put("createdAt", kw.createdAt)
                })
            }
            put("keywords", keywordsArray)
            put("searchKeywords", searchKeywordsArray)
            put("instagramAccounts", instaArray)
            put("accounts", unifiedAccounts)
            put("sessions", sessionsArray)
        }

        val jsonOutput = root.toString(2)
        val kwCount = dbHelper.getAllSearchKeywords().size
        dbHelper.insertPostLog(
            PostLog(
                pinId = "BACKUP",
                status = "INFO",
                message = "Exported backup: ${pinterestAccounts.size} Pinterest, ${instagramAccounts.size} Instagram account(s), and $kwCount keyword(s) with session data"
            )
        )
        jsonOutput
    }

    suspend fun importAccountsJson(jsonString: String): ImportResult = withContext(Dispatchers.IO) {
        var pinCount = 0
        var instaCount = 0
        var keywordCount = 0
        try {
            val cleanJson = jsonString.trim()

            // Helper to parse and insert search keywords
            fun parseAndInsertKeyword(item: Any?): Boolean {
                val kw = when (item) {
                    is String -> item.trim()
                    is JSONObject -> item.optString("keyword").trim()
                    else -> item?.toString()?.trim() ?: ""
                }
                if (kw.isNotBlank()) {
                    val id = dbHelper.addSearchKeyword(kw)
                    return id != -1L
                }
                return false
            }

            // Helper to parse and insert/update an Instagram account object
            fun parseAndInsertInstagram(obj: JSONObject): Boolean {
                val username = obj.optString("username").ifBlank {
                    obj.optString("user").ifBlank { obj.optString("handle") }
                }.trim()
                if (username.isBlank()) return false

                var rawCookies = obj.optString("cookies").ifBlank {
                    obj.optString("cookie").ifBlank {
                        obj.optJSONObject("sessionData")?.optString("cookies", "") ?: ""
                    }
                }.trim()

                val rawSession = obj.optString("session").ifBlank {
                    obj.optString("sessionId").ifBlank {
                        obj.optString("sessionid").ifBlank {
                            obj.optString("session_id").ifBlank {
                                obj.optJSONObject("sessionData")?.optString("sessionid", "") ?: ""
                            }
                        }
                    }
                }.trim()

                val dsUser = obj.optString("dsUserId").ifBlank {
                    obj.optString("ds_user_id").ifBlank {
                        obj.optJSONObject("sessionData")?.optString("ds_user_id", "") ?: ""
                    }
                }.trim()

                val csrf = obj.optString("csrfToken").ifBlank {
                    obj.optString("csrftoken").ifBlank {
                        obj.optString("csrf_token").ifBlank {
                            obj.optJSONObject("sessionData")?.optString("csrftoken", "") ?: ""
                        }
                    }
                }.trim()

                // Synthesize cookie string if only session token was provided
                if (rawCookies.isBlank() && rawSession.isNotBlank()) {
                    val sb = StringBuilder("sessionid=$rawSession")
                    if (dsUser.isNotBlank()) sb.append("; ds_user_id=$dsUser")
                    if (csrf.isNotBlank()) sb.append("; csrftoken=$csrf")
                    rawCookies = sb.toString()
                }

                if (rawCookies.isBlank()) return false

                val resolvedCsrf = csrf.ifBlank { CookieUtils.extractCookieValue(rawCookies, "csrftoken") }
                val resolvedDsUser = dsUser.ifBlank { CookieUtils.extractCookieValue(rawCookies, "ds_user_id") }

                val account = InstagramAccount(
                    username = username,
                    displayName = obj.optString("displayName", username),
                    profilePicUrl = obj.optString("profilePicUrl", ""),
                    cookies = rawCookies,
                    csrfToken = resolvedCsrf,
                    dsUserId = resolvedDsUser,
                    status = obj.optString("status", "ACTIVE")
                )
                dbHelper.insertOrUpdateInstagramAccount(account)

                // Inject into CookieManager so WebViews and API calls are immediately authenticated
                CookieUtils.injectCookies("https://www.instagram.com", rawCookies)
                return true
            }

            // Helper to parse and insert/update a Pinterest account object
            fun parseAndInsertPinterest(obj: JSONObject): Boolean {
                val username = obj.optString("username").ifBlank {
                    obj.optString("user").ifBlank { obj.optString("handle") }
                }.trim()
                if (username.isBlank()) return false

                var rawCookies = obj.optString("cookies").ifBlank {
                    obj.optString("cookie").ifBlank {
                        obj.optJSONObject("sessionData")?.optString("cookies", "") ?: ""
                    }
                }.trim()

                val rawSession = obj.optString("session").ifBlank {
                    obj.optString("sessionId").ifBlank {
                        obj.optString("_pinterest_sess").ifBlank {
                            obj.optJSONObject("sessionData")?.optString("_pinterest_sess", "") ?: ""
                        }
                    }
                }.trim()

                val csrf = obj.optString("csrfToken").ifBlank {
                    obj.optString("csrftoken").ifBlank {
                        obj.optString("csrf_token").ifBlank {
                            obj.optJSONObject("sessionData")?.optString("csrftoken", "") ?: ""
                        }
                    }
                }.trim()

                // Synthesize cookie string if only session token was provided
                if (rawCookies.isBlank() && rawSession.isNotBlank()) {
                    val sb = StringBuilder("_pinterest_sess=$rawSession; _auth=1")
                    if (csrf.isNotBlank()) sb.append("; csrftoken=$csrf")
                    rawCookies = sb.toString()
                }

                if (rawCookies.isBlank()) return false

                // Crucial fix: Pinterest considers account logged in ONLY if _auth=1 is present!
                if (rawCookies.contains("_auth=0") && (rawCookies.contains("_pinterest_sess=") || rawSession.isNotBlank())) {
                    rawCookies = rawCookies.replace("_auth=0", "_auth=1")
                } else if (!rawCookies.contains("_auth=")) {
                    rawCookies = "$rawCookies; _auth=1"
                }

                val resolvedCsrf = csrf.ifBlank { CookieUtils.extractCookieValue(rawCookies, "csrftoken") }
                val dailyPostTimes = obj.optString("dailyPostTimes", "09:00,14:00,20:00")

                val account = PinterestAccount(
                    username = username,
                    displayName = obj.optString("displayName", username),
                    profilePicUrl = obj.optString("profilePicUrl", ""),
                    cookies = rawCookies,
                    csrfToken = resolvedCsrf,
                    status = obj.optString("status", "ACTIVE"),
                    scheduleIntervalHours = obj.optInt("scheduleIntervalHours", 1),
                    isAutoPostEnabled = obj.optBoolean("isAutoPostEnabled", true),
                    dailyPostTimes = dailyPostTimes
                ).apply {
                    nextRunTime = calculateNextScheduledTime(System.currentTimeMillis())
                }
                dbHelper.insertOrUpdatePinterestAccount(account)

                // Inject into CookieManager across all Pinterest subdomains
                CookieUtils.injectPinterestCookies(rawCookies)
                return true
            }

            if (cleanJson.startsWith("[")) {
                // Root is a direct JSONArray
                val array = JSONArray(cleanJson)
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    if (item is String) {
                        if (parseAndInsertKeyword(item)) keywordCount++
                    } else if (item is JSONObject) {
                        val platform = item.optString("platform", "").uppercase()
                        if (platform == "INSTAGRAM" || item.has("dsUserId") || item.has("sessionid") || item.optString("cookies").contains("sessionid")) {
                            if (parseAndInsertInstagram(item)) instaCount++
                        } else if (platform == "PINTEREST" || item.has("_pinterest_sess") || item.optString("cookies").contains("_pinterest_sess")) {
                            if (parseAndInsertPinterest(item)) pinCount++
                        } else if (item.has("keyword")) {
                            if (parseAndInsertKeyword(item)) keywordCount++
                        }
                    }
                }
            } else {
                val root = JSONObject(cleanJson)

                // 1. Process Instagram accounts array
                val instaArray = root.optJSONArray("instagramAccounts")
                if (instaArray != null) {
                    for (i in 0 until instaArray.length()) {
                        val obj = instaArray.optJSONObject(i) ?: continue
                        if (parseAndInsertInstagram(obj)) instaCount++
                    }
                }

                // 2. Process Pinterest accounts array
                val pinArray = root.optJSONArray("pinterestAccounts")
                if (pinArray != null) {
                    for (i in 0 until pinArray.length()) {
                        val obj = pinArray.optJSONObject(i) ?: continue
                        if (parseAndInsertPinterest(obj)) pinCount++
                    }
                }

                // 3. Process unified accounts array if present
                val unifiedArray = root.optJSONArray("accounts")
                if (unifiedArray != null) {
                    for (i in 0 until unifiedArray.length()) {
                        val obj = unifiedArray.optJSONObject(i) ?: continue
                        val platform = obj.optString("platform", "").uppercase()
                        if (platform == "INSTAGRAM") {
                            if (parseAndInsertInstagram(obj)) instaCount++
                        } else if (platform == "PINTEREST") {
                            if (parseAndInsertPinterest(obj)) pinCount++
                        }
                    }
                }

                // 4. Process sessions array if present
                val sessArray = root.optJSONArray("sessions")
                if (sessArray != null) {
                    for (i in 0 until sessArray.length()) {
                        val obj = sessArray.optJSONObject(i) ?: continue
                        val platform = obj.optString("platform", "").uppercase()
                        if (platform == "INSTAGRAM") {
                            if (parseAndInsertInstagram(obj)) instaCount++
                        } else if (platform == "PINTEREST") {
                            if (parseAndInsertPinterest(obj)) pinCount++
                        }
                    }
                }

                // 5. Process search keywords array if present
                val kwArray = root.optJSONArray("keywords") ?: root.optJSONArray("searchKeywords")
                if (kwArray != null) {
                    for (i in 0 until kwArray.length()) {
                        if (parseAndInsertKeyword(kwArray.opt(i))) {
                            keywordCount++
                        }
                    }
                }
            }

            dbHelper.insertPostLog(
                PostLog(
                    pinId = "BACKUP",
                    status = "SUCCESS",
                    message = "Imported $pinCount Pinterest, $instaCount Instagram account(s), and $keywordCount search keyword(s) with session data successfully"
                )
            )

            ImportResult(
                pinterestImported = pinCount,
                instagramImported = instaCount,
                keywordsImported = keywordCount,
                message = "Imported $pinCount Pinterest, $instaCount Instagram account(s), and $keywordCount search keyword(s) successfully"
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
                keywordsImported = 0,
                message = "Failed to parse JSON: ${e.message}"
            )
        }
    }

    /**
     * Resolves and imports a shared Pinterest pin URL directly into the local Pin Vault.
     */
    suspend fun importPinFromSharedText(sharedText: String, targetAccountId: Long? = null): Pair<Boolean, SavedPin?> = withContext(Dispatchers.IO) {
        try {
            val accountId = targetAccountId ?: dbHelper.getAllPinterestAccounts().firstOrNull()?.id ?: 0L
            val pin = pinterestClient.fetchPinDetailsByUrl(sharedText, accountId)
            if (pin != null) {
                val inserted = dbHelper.insertSavedPin(pin)
                if (inserted > 0L) {
                    dbHelper.insertPostLog(
                        PostLog(
                            pinId = pin.pinId,
                            instagramAccountId = null,
                            status = "INFO",
                            message = "Captured pin '${pin.title.take(30)}' via Android Share into Pin Vault"
                        )
                    )
                }
                Pair(true, pin)
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing shared pin", e)
            Pair(false, null)
        }
    }
}
