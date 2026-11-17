package app.aaps.plugins.sync.tidepool.comm

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.tidepool.TidepoolDevice
import app.aaps.plugins.sync.tidepool.auth.AuthFlowOut
import app.aaps.plugins.sync.tidepool.auth.TidepoolToken
import app.aaps.plugins.sync.tidepool.comm.TidepoolUploader.Companion.RETRY_LOGIN_INTERVAL_SECONDS
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.keys.TidepoolBooleanKey
import app.aaps.plugins.sync.tidepool.keys.TidepoolStringNonKey
import app.aaps.plugins.sync.tidepool.messages.AuthReplyMessage
import app.aaps.plugins.sync.tidepool.messages.OpenDatasetRequestMessage
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(AppScope::class)
@Inject
class TidepoolUploader(
    private val aapsLogger: AAPSLogger,
    private val tidepoolRepository: TidepoolRepository,
    private val wakeLock: TidepoolWakeLock,
    private val preferences: Preferences,
    private val uploadChunk: UploadChunk,
    private val dateUtil: DateUtil,
    private val receiverDelegate: TidepoolReceiverDelegate,
    private val config: Config,
    private val l: L,
    private val authFlowOut: AuthFlowOut,
    private val rateLimit: RateLimit
) {

    private val isAllowed get() = receiverDelegate.allowed
    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)

    // doLogin checks the status and then sets it: two triggers at once must not both start a login
    private val loginLock = AapsLock()

    companion object {

        private const val INTEGRATION_BASE_URL = "https://int-api.tidepool.org"
        private const val PRODUCTION_BASE_URL = "https://api.tidepool.org"

        // Datasets per request when purge lists them. Old versions opened a new dataset on every
        // session, so a user can have many more than this; purge asks again until all are gone.
        internal const val PURGE_PAGE_SIZE = 100

        // Purge writes a progress line to the Tidepool log after this many deleted datasets
        internal const val PURGE_PROGRESS_STEP = 100

        // Shortest time between two automatic openings of the login page
        internal val RETRY_LOGIN_INTERVAL_SECONDS = T.mins(10).secs().toInt()
    }

    private var api: TidepoolApi? = null

    private var session: Session? = null

    // Single-flight guard: getLastEnd()..setLastEnd(session.end) is a non-atomic read-modify-write split
    // across the async upload callback, and doUpload() is fanned in concurrently from several triggers.
    // Only one chunk may be in flight so concurrent triggers don't read the same LastEnd and upload
    // overlapping windows. Held from getNext() until the upload's success/failure callback.
    private val uploadMutex = Mutex()

    // Set when a purge was requested but no dataset was open yet; consumed once the session/dataset is ready.
    @Volatile private var pendingPurge = false

    private fun getApi(): TidepoolApi =
        api ?: KtorTidepoolApi.create(
            baseUrl = if (preferences.get(TidepoolBooleanKey.UseTestServers)) INTEGRATION_BASE_URL else PRODUCTION_BASE_URL,
            applicationId = config.APPLICATION_ID,
            versionName = config.VERSION_NAME,
            aapsLogger = aapsLogger,
            // The bodies only in a debug build with the TIDEPOOL log on. The token is never logged (#5206).
            logBodies = l.findByName(LTag.TIDEPOOL.tag).enabled && (config.isEngineeringMode() || config.isDev())
        ).also { api = it }

    fun createSession(): Session = Session(getApi())

    fun resetInstance() {
        aapsLogger.debug(LTag.TIDEPOOL, "Instance reset")
        api = null
        session = null
        // Reset connection status so the next doUpload() triggers a fresh login
        // instead of trying to use the now-null session
        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.NONE)
    }

    /**
     * IMPROVED: Simplified login without connectivity checks
     *
     * Connectivity is now checked by TidepoolPlugin.doUpload() BEFORE calling this method.
     * This separation of concerns makes the code clearer and prevents state machine deadlock.
     *
     * Old behavior:
     *   - Checked connectivity here and set BLOCKED state
     *   - Auth state could get stuck in BLOCKED
     *
     * New behavior:
     *   - Assumes caller already checked connectivity
     *   - Only handles authentication state transitions
     *   - No BLOCKED state to get stuck in
     */
    fun doLogin(doUpload: Boolean = false, from: String?) = loginLock.withLock {
        aapsLogger.debug(LTag.TIDEPOOL, "doLogin from=$from doUpload=$doUpload currentStatus=${authFlowOut.connectionStatus}")

        // IMPROVEMENT: Removed connectivity check - caller's responsibility
        // This prevents mixing connectivity constraints with auth state
        //
        // REMOVED CODE:
        // if (!isAllowed) {
        //     authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.BLOCKED)
        //     aapsLogger.debug(LTag.TIDEPOOL, "Blocked by connectivity settings")
        //     return
        // }

        // Check if already in a connected or connecting state
        if (authFlowOut.connectionStatus == AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED ||
            authFlowOut.connectionStatus == AuthFlowOut.ConnectionStatus.FETCHING_TOKEN
        ) {
            aapsLogger.debug(LTag.TIDEPOOL, "Already connected or connecting")
            return@withLock
        }

        // Proceed with authentication
        handleTokenLoginAndStartSession(doUpload, from)
    }

    fun handleTokenLoginAndStartSession(doUpload: Boolean, from: String?) {
        //aapsLogger.debug(LTag.TIDEPOOL, "handleTokenLoginAndStartSession")
        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.FETCHING_TOKEN, "Connecting")
        scope.launch {
            when (val token = authFlowOut.freshToken()) {
                is TidepoolToken.Valid       -> {
                    val session = createSession().also {
                        it.authReply = AuthReplyMessage().apply { userid = preferences.get(TidepoolStringNonKey.SubscriptionId) }
                        it.token = token.accessToken
                    }
                    startSession(session, doUpload, from)
                }

                is TidepoolToken.Unavailable -> when (token.kind) {
                    // Only the network or the server failed. Keep the saved credentials and try the silent
                    // refresh again on the next upload instead of opening the login page in the browser.
                    TidepoolToken.Kind.TRANSIENT         -> {
                        aapsLogger.debug(LTag.TIDEPOOL, "Token refresh failed for now, keeping login: ${token.reason}")
                        // NO_SESSION makes the next doUpload() call doLogin() again.
                        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.NO_SESSION, "Network problem, will try again later")
                        cancelPendingPurge()
                    }

                    TidepoolToken.Kind.REFUSED           -> {
                        aapsLogger.error(LTag.TIDEPOOL, "Failing to use access token - trying initial login again: ${token.reason}")
                        tidepoolRepository.addLog("Got exception token: ${token.reason}")
                        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.NOT_LOGGED_IN, "Failed to use token")
                        cancelPendingPurge()
                        retryInitialLogin("handleTokenLoginAndStartSession accessToken == null")
                    }

                    TidepoolToken.Kind.NO_TOKEN_RESPONSE -> {
                        aapsLogger.error(LTag.TIDEPOOL, "Failing to get response / token type - trying initial login again")
                        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.NOT_LOGGED_IN, "Failed to get token")
                        cancelPendingPurge()
                        retryInitialLogin("handleTokenLoginAndStartSession lastTokenResponse == null")
                    }
                }
            }
        }
    }

    /**
     * Open the login page in the browser, but not more often than once per [RETRY_LOGIN_INTERVAL_SECONDS].
     * Uploads are triggered from many places, so without this limit a broken login could open the browser
     * again and again. The Login button in the plugin screen calls [AuthFlowOut.doTidePoolInitialLogin]
     * directly and is never limited.
     */
    private fun retryInitialLogin(from: String) {
        if (rateLimit.rateLimit("tidepool-retry-login", RETRY_LOGIN_INTERVAL_SECONDS))
            authFlowOut.doTidePoolInitialLogin(from)
        else
            aapsLogger.debug(LTag.TIDEPOOL, "Not opening login page again so soon: $from")
    }

    fun startSession(newSession: Session, doUpload: Boolean = false, @Suppress("unused") from: String?) {
        //aapsLogger.debug(LTag.TIDEPOOL, "startSession $from")
        extendWakeLock(30000)
        session = newSession
        val userId = newSession.authReply?.userid
        if (userId == null) {
            aapsLogger.error("Got login response but cannot determine userId - cannot proceed")
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "Error userId")
            cancelPendingPurge()
            releaseWakeLock()
            return
        }
        val service = newSession.service ?: return
        scope.launch { findOrOpenDataset(newSession, service, userId, doUpload) }
    }

    /**
     * Find the dataset to write to, or open a new one, then run what waits for it: a purge, an upload, or nothing.
     *
     * The search must match the client.name the dataset is CREATED with (OpenDatasetRequestMessage -> ClientInfo(config.APPLICATION_ID)).
     * A hardcoded "AAPS" here never matched config.APPLICATION_ID ("info.nightscout.androidaps"), so the existing open
     * dataset was never found and a new dataset (new uploadId) was opened on every session. Because the Tidepool
     * dataset.delete.origin deduplicator is scoped to a single uploadId, prior syncs' data could never be replaced,
     * so every full sync duplicated all data. Reusing one dataset lets the per-uploadId origin dedup collapse re-uploads.
     * The deviceId filter skips datasets from before 4.0 (named "Tandem", no deviceId), see TidepoolDevice.NAME.
     */
    private suspend fun findOrOpenDataset(session: Session, service: TidepoolApi, userId: String, doUpload: Boolean) {
        val dataSets = call("Get Open Datasets") { service.getDataSets(session.token!!, userId, config.APPLICATION_ID, TidepoolDevice.NAME, 1) }
        if (dataSets == null) {
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "Open dataset FAILED")
            cancelPendingPurge()
            releaseWakeLock()
            return
        }
        dataSets.firstOrNull()?.let { session.datasetReply = it }
        if (session.datasetReply == null) {
            tidepoolRepository.addLog("Creating new dataset")
            val reply = call("Open New Dataset") { service.openDataSet(session.token!!, userId, OpenDatasetRequestMessage(config, dateUtil)) }
            if (reply == null) {
                authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "New dataset FAILED")
                cancelPendingPurge()
                releaseWakeLock()
                return
            }
            session.datasetReply = reply
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED, "New dataset OK")
        } else {
            aapsLogger.debug(LTag.TIDEPOOL, "Existing Dataset: " + session.datasetReply!!.getUploadId())
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED, "Appending to existing dataset")
        }
        when {
            // A new dataset is empty, but older datasets (e.g. from before 4.0) can still hold data.
            pendingPurge -> executePurge()
            doUpload     -> doUpload("startSession")
            else         -> releaseWakeLock()
        }
    }

    /**
     * Run one call that must return a body. Network errors, refused calls and replies without a body all
     * give null, after writing why to the log and to the Tidepool screen.
     */
    private suspend fun <T> call(name: String, request: suspend () -> TidepoolResponse<T>): T? {
        val msg = try {
            val response = request()
            val body = response.body
            if (response.isSuccessful && body != null) {
                aapsLogger.debug(LTag.TIDEPOOL, "$name success")
                return body
            }
            name + " was not successful: " + response.code + " " + response.message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$name Failed: $e"
        }
        aapsLogger.debug(LTag.TIDEPOOL, msg)
        tidepoolRepository.addLog(msg)
        return null
    }

    suspend fun doUpload(from: String?) {
        //aapsLogger.debug(LTag.TIDEPOOL, "doUpload $from")
        if (!isAllowed) {
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.BLOCKED)
            aapsLogger.debug(LTag.TIDEPOOL, "Blocked by connectivity settings")
            return
        }
        val session = this.session
        if (session == null) {
            aapsLogger.warn(LTag.TIDEPOOL, "Session is null, triggering re-login")
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.NONE)
            doLogin(doUpload = true, from = "doUpload session recovery")
            return
        }
        // Skip if a chunk is already in flight; concurrent triggers must not read the same LastEnd (see uploadMutex).
        if (!uploadMutex.tryLock()) {
            aapsLogger.debug(LTag.TIDEPOOL, "Upload already in progress, skipping trigger from $from")
            return
        }
        var uploadMore = false
        try {
            extendWakeLock(60000)
            session.iterations++
            val batches = uploadChunk.getNext(session)
            when {
                batches == null   -> {
                    aapsLogger.error("Upload chunk is null, cannot proceed")
                    releaseWakeLock()
                }

                batches.isEmpty() -> {
                    aapsLogger.debug(LTag.TIDEPOOL, "Empty dataset - marking as succeeded")
                    tidepoolRepository.addLog("No data to upload")
                    releaseWakeLock()
                    uploadMore = true
                }

                else              -> {
                    tidepoolRepository.addLog("Uploading")
                    val service = session.service
                    val uploadId = session.datasetReply?.getUploadId()
                    if (service != null && uploadId != null) uploadMore = uploadBatches(session, service, uploadId, batches, from)
                }
            }
        } finally {
            // The lock is held for the whole upload, so it is released here on every path
            uploadMutex.unlock()
        }
        if (uploadMore) uploadNext()
    }

    /**
     * Send [batches] one after another. `LastEnd` moves only after the last one, so when one fails the
     * whole time window is sent again later; the deduplicator then replaces the records that arrived
     * already, by their origin id, instead of adding them a second time.
     *
     * @return true when every batch arrived
     */
    private suspend fun uploadBatches(session: Session, service: TidepoolApi, uploadId: String, batches: List<String>, from: String?): Boolean {
        for (index in batches.indices) {
            if (batches.size > 1) tidepoolRepository.addLog("Uploading part ${index + 1} of ${batches.size}")
            extendWakeLock(60000)
            // FAILED makes the next upload log in again, which tells a network problem from a lost login
            val token = freshToken(session) ?: return uploadFailed("Upload FAILED - no valid token")
            call("Data Upload $from") { service.doUpload(token, uploadId, batches[index]) } ?: return uploadFailed("Upload FAILED")
        }
        uploadChunk.setLastEnd(session.end)
        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED, "Upload completed OK")
        releaseWakeLock()
        return true
    }

    private fun uploadFailed(message: String): Boolean {
        authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, message)
        releaseWakeLock()
        return false
    }

    /**
     * Delete ALL AAPS-uploaded data from Tidepool by removing every dataset with our `client.name`
     * (`config.APPLICATION_ID`): the one in use and older ones, e.g. from before 4.0 ("Tandem") or from
     * versions that opened a new dataset on every session. Then reset so the next upload opens a fresh
     * empty dataset. The old datasets matter: our records have fixed origin ids and the deduplicator works
     * only inside one dataset, so a "Full sync" next to an old dataset would show everything twice. Use it to clear
     * data corrupted by an earlier generator (e.g. the resync basal-overlap bug), which a plain re-sync
     * cannot repair because it never re-emits the old records' start-times. Existing forward progress
     * (the `LastEnd` watermark) is left untouched, so "Full sync" is still the way to re-upload history.
     *
     * Logs in / opens a session first if needed and defers via [pendingPurge].
     *
     * NOTE: [TidepoolApiService.deleteDataSet] (DELETE /v1/datasets/{id}) is verified against the Tidepool
     * API spec (200 + empty body) but not runtime-tested; [executePurge] treats any 2xx as success.
     */
    fun purge() {
        if (!isAllowed) {
            tidepoolRepository.addLog("Purge blocked by connectivity settings")
            return
        }
        pendingPurge = true
        extendWakeLock(30000)
        if (session?.datasetReply?.getUploadId() != null) scope.launch { executePurge() }
        else {
            tidepoolRepository.addLog("Purge: connecting…")
            doLogin(doUpload = false, from = "purge")
        }
    }

    /**
     * Drop a purge that waits for an open dataset ([pendingPurge]) because connecting failed. Without this
     * the request would stay armed and a later upload could open a session hours afterwards and delete all
     * data at a moment the user is not looking. The user can start the purge again when the connection works.
     */
    private fun cancelPendingPurge() {
        if (pendingPurge) {
            pendingPurge = false
            aapsLogger.warn(LTag.TIDEPOOL, "Purge dropped, could not connect")
            tidepoolRepository.addLog("Purge failed - not connected")
            releaseWakeLock()
        }
    }

    private suspend fun executePurge() {
        pendingPurge = false
        val session = this.session
        val service = session?.service
        val userId = session?.authReply?.userid
        val currentId = session?.datasetReply?.getUploadId()
        if (session == null || service == null || userId == null || currentId == null) {
            aapsLogger.warn(LTag.TIDEPOOL, "Purge: no open dataset to delete")
            tidepoolRepository.addLog("Purge failed - not connected")
            releaseWakeLock()
            return
        }
        tidepoolRepository.addLog("Purging all Tidepool data…")
        purgeAll(session, service, userId, currentId)
    }

    /**
     * An access token that is still valid, or null when there is none.
     *
     * A session lives much longer than its access token (about 10 minutes), so the token from the session
     * start is not enough: with it, the first upload after the token expired failed with 401, and a long
     * purge stopped half way. The platform refreshes only when the token is about to expire, so this costs
     * nothing most of the time. The new token goes into [session] as well.
     */
    private suspend fun freshToken(session: Session): String? =
        when (val token = authFlowOut.freshToken()) {
            is TidepoolToken.Valid       -> token.accessToken.also { session.token = it }
            is TidepoolToken.Unavailable -> {
                aapsLogger.warn(LTag.TIDEPOOL, "No valid token: ${token.reason}")
                null
            }
        }

    private fun purgeWithoutToken() {
        tidepoolRepository.addLog("Purge failed - not connected")
        releaseWakeLock()
    }

    /**
     * List our datasets, delete all of them except the one in use, then list again. Deleted datasets are
     * not listed any more, so this ends when only the dataset in use is left. That one is deleted last:
     * if a delete fails before, the session still has a working dataset to upload to.
     * [deleted] guards against a loop if the server still lists a dataset right after deleting it.
     */
    private suspend fun purgeAll(session: Session, service: TidepoolApi, userId: String, currentId: String) {
        val deleted = mutableSetOf<String>()
        while (true) {
            val token = freshToken(session) ?: return purgeWithoutToken()
            val response = try {
                service.getDataSets(token, userId, config.APPLICATION_ID, null, PURGE_PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return purgeFailed(e.toString())
            }
            val dataSets = response.body
            if (!response.isSuccessful || dataSets == null) return purgeFailed("${response.code} ${response.message}")
            val others = dataSets.mapNotNull { it.getUploadId() }.filter { it != currentId && it !in deleted }
            if (others.isEmpty()) {
                if (!deleteDataSets(session, service, listOf(currentId), deleted)) return
                tidepoolRepository.addLog("All Tidepool data purged (deleted datasets: ${deleted.size})")
                resetInstance() // drop the deleted dataset; the next upload opens a fresh one
                releaseWakeLock()
                return
            }
            if (!deleteDataSets(session, service, others, deleted)) return
        }
    }

    /** Delete [ids] one after another. Stops at the first failure; returns true when all are gone. */
    private suspend fun deleteDataSets(session: Session, service: TidepoolApi, ids: List<String>, deleted: MutableSet<String>): Boolean {
        for (id in ids) {
            // A big dataset can take up to the DELETE read timeout, keep the phone awake that long
            extendWakeLock(T.mins(KtorTidepoolApi.DELETE_READ_TIMEOUT_MINUTES + 1L).msecs())
            val token = freshToken(session) ?: return purgeWithoutToken().let { false }
            // Not through call(): DELETE returns 200 with an empty body, which call() would treat as a failure
            val response = try {
                service.deleteDataSet(token, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return purgeFailed(e.toString()).let { false }
            }
            if (!response.isSuccessful) return purgeFailed("${response.code} ${response.message}").let { false }
            aapsLogger.debug(LTag.TIDEPOOL, "Purged Tidepool dataset $id")
            deleted.add(id)
            // An account from old versions can hold thousands of datasets, and purge then runs for
            // many minutes. Show that it moves.
            if (deleted.size % PURGE_PROGRESS_STEP == 0) tidepoolRepository.addLog("Purge: ${deleted.size} datasets deleted")
        }
        return true
    }

    private fun purgeFailed(reason: String) {
        aapsLogger.error(LTag.TIDEPOOL, "Purge FAILED: $reason")
        tidepoolRepository.addLog("Purge FAILED: $reason")
        releaseWakeLock()
    }

    private fun uploadNext() {
        //aapsLogger.debug(LTag.TIDEPOOL, "uploadNext")
        if (!isAllowed) {
            authFlowOut.updateConnectionStatus(AuthFlowOut.ConnectionStatus.BLOCKED)
            aapsLogger.debug(LTag.TIDEPOOL, "Blocked by connectivity settings")
            return
        }
        if (uploadChunk.getLastEnd() < dateUtil.now() - T.hours(3).msecs() - T.mins(1).msecs()) {
            aapsLogger.debug(LTag.TIDEPOOL, "Restarting doUpload. Last: " + dateUtil.dateAndTimeString(uploadChunk.getLastEnd()))
            scope.launch {
                delay(3000)
                doUpload("uploadNext")
            }
        }
    }

    private fun extendWakeLock(ms: Long) = wakeLock.extend(ms)

    private fun releaseWakeLock() = wakeLock.release()
}