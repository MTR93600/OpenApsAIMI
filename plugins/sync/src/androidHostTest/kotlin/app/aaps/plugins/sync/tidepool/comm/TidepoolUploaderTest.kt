package app.aaps.plugins.sync.tidepool.comm

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.tidepool.TidepoolDevice
import app.aaps.plugins.sync.tidepool.auth.AuthFlowOut
import app.aaps.plugins.sync.tidepool.auth.TidepoolToken
import app.aaps.plugins.sync.tidepool.compose.TidepoolRepository
import app.aaps.plugins.sync.tidepool.messages.AuthReplyMessage
import app.aaps.plugins.sync.tidepool.messages.DatasetReplyMessage
import app.aaps.plugins.sync.tidepool.messages.OpenDatasetRequestMessage
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the state handling of [TidepoolUploader]: when it may talk to Tidepool, when it starts a new
 * login and what it does without an open dataset. The calls to the Tidepool API itself need a server
 * and stay out of scope; [TidepoolUploaderAuthTest] covers the token refresh.
 *
 * Runs under Robolectric because the uploader takes a wake lock from the real [Context]. Token answers
 * come from a stub of [AuthFlowOut.freshToken]; how AppAuth answers map to them is in `AppAuthTokenMappingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35])
class TidepoolUploaderTest {

    private val aapsLogger: AAPSLogger = mock()
    private val tidepoolRepository = TidepoolRepository(aapsLogger)
    private val context: Context = RuntimeEnvironment.getApplication()
    private val preferences: Preferences = mock()
    private val uploadChunk: UploadChunk = mock()
    private val dateUtil: DateUtil = mock()
    private val receiverDelegate: TidepoolReceiverDelegate = mock()
    private val config: Config = mock()
    private val l: L = mock()
    private val authFlowOut: AuthFlowOut = mock()

    private val now = 1_700_000_000_000L

    private lateinit var sut: TidepoolUploader

    /** What a token request answers. Until a test says otherwise it never finishes, like a login still in the browser. */
    @Volatile private var tokenAnswer: suspend () -> TidepoolToken = { awaitCancellation() }

    @Before
    fun setUp() {
        // Stubbed once: stubbing again would call the stub, and the default one never returns
        wheneverBlocking { authFlowOut.freshToken() }.doSuspendableAnswer { tokenAnswer() }
        whenever(dateUtil.now()).thenReturn(now)
        whenever(config.APPLICATION_ID).thenReturn("info.nightscout.androidaps")
        whenever(config.VERSION_NAME).thenReturn("4.0.0") // client.version of a new dataset
        sut = TidepoolUploader(
            aapsLogger, tidepoolRepository, AndroidTidepoolWakeLock(context, aapsLogger), preferences, uploadChunk, dateUtil,
            receiverDelegate, config, l, authFlowOut, RateLimit(dateUtil)
        )
    }

    /** What the Tidepool screen shows in its log */
    private fun statusMessages(): List<String> = tidepoolRepository.logList.value.map { it.action }

    /** Getting a token is the first step of every login, so it shows that a login was started. */
    private fun verifyLoginStarted() =
        verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.FETCHING_TOKEN), any())

    private fun verifyNoLoginStarted() =
        verify(authFlowOut, never()).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.FETCHING_TOKEN), any())

    /** Open a session without a service, so the uploader has one but makes no network call. */
    private fun startSessionWithUser(userId: String? = "user-1") {
        val session = Session(null)
        session.authReply = AuthReplyMessage().apply { userid = userId }
        sut.startSession(session, doUpload = false, from = "test")
    }

    /** Let the platform report a network problem instead of a token, so the login cannot finish. */
    private fun failTokenRefresh() {
        tokenAnswer = { TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, "no route to host") }
    }

    /**
     * A fake Tidepool server. It holds `dataSets` as upload id to device id, newest first. Like the real
     * server, a list filters by `deviceId` (when given) and returns at most `size`; opening adds a new
     * [TidepoolDevice.NAME] dataset; a successful delete removes the dataset. It records every call.
     */
    private class FakeTidepool(vararg initialDataSets: Pair<String, String?>) : TidepoolApi {

        data class ListCall(val token: String, val userId: String, val clientName: String, val deviceId: String?, val size: Int)

        val dataSets = CopyOnWriteArrayList(listOf(*initialDataSets))
        val deleted = CopyOnWriteArrayList<String>()
        val listCalls = CopyOnWriteArrayList<ListCall>()
        val openCalls = CopyOnWriteArrayList<String>()

        /** Token and upload id of every upload, and the number (1, 2, …) of the one that answers with an error. */
        val uploadCalls = CopyOnWriteArrayList<Pair<String, String>>()
        var failUpload: Int? = null
        val uploads get() = uploadCalls.size

        /** Answer uploads with 200 and nothing to read. */
        var emptyUploadReply = false

        /** Token and upload id of every delete. */
        val deleteCalls = CopyOnWriteArrayList<Pair<String, String>>()

        /** How a delete answers, or throws. The default is what Tidepool does: 200 with an empty body. */
        var deleteAnswer: (String) -> TidepoolResponse<Unit> = { TidepoolResponse(200, "OK", null) }

        override suspend fun getDataSets(token: String, userId: String, clientName: String, deviceId: String?, size: Int): TidepoolResponse<List<DatasetReplyMessage>> {
            listCalls += ListCall(token, userId, clientName, deviceId, size)
            val replies = dataSets
                .filter { deviceId == null || it.second == deviceId }
                .take(size)
                .map { DatasetReplyMessage().apply { uploadId = it.first } }
            return TidepoolResponse(200, "OK", replies)
        }

        override suspend fun openDataSet(token: String, userId: String, request: OpenDatasetRequestMessage): TidepoolResponse<DatasetReplyMessage> {
            openCalls += userId
            dataSets.add(0, "new-1" to TidepoolDevice.NAME)
            return TidepoolResponse(200, "OK", DatasetReplyMessage().apply { uploadId = "new-1" })
        }

        override suspend fun doUpload(token: String, uploadId: String, records: String): TidepoolResponse<Unit> {
            uploadCalls += token to uploadId
            return when {
                uploadCalls.size == failUpload -> TidepoolResponse(500, "down", null)
                emptyUploadReply               -> TidepoolResponse(200, "OK", null)
                else                           -> TidepoolResponse(200, "OK", Unit)
            }
        }

        override suspend fun deleteDataSet(token: String, uploadId: String): TidepoolResponse<Unit> {
            deleteCalls += token to uploadId
            val answer = deleteAnswer(uploadId)
            if (answer.isSuccessful) {
                dataSets.removeAll { it.first == uploadId }
                deleted.add(uploadId)
            }
            return answer
        }
    }

    /** A purge runs in the background: retry [assertion] until it holds, for up to 3 s. */
    private fun eventually(assertion: () -> Unit) {
        val end = System.currentTimeMillis() + 3000
        while (true) {
            try {
                assertion()
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(20)
            }
        }
    }

    /**
     * Let the platform answer every token request at once. [tokenFor] gets the number of the request
     * (1, 2, …) and returns the token, or null for a failed refresh.
     */
    private fun answerTokens(tokenFor: (Int) -> String? = { "token-1" }) {
        val requests = AtomicInteger()
        tokenAnswer = {
            tokenFor(requests.incrementAndGet())?.let { TidepoolToken.Valid(it) } ?: TidepoolToken.Unavailable(TidepoolToken.Kind.TRANSIENT, "no route to host")
        }
    }

    /**
     * Open a session on [server]. The session finds or opens its dataset in the background. AppAuth gives a
     * valid token from now on, unless the test sets up other answers after this.
     */
    private fun startSession(server: FakeTidepool): Session {
        answerTokens()
        val session = Session(server)
        session.authReply = AuthReplyMessage().apply { userid = "user-1" }
        session.token = "token-1"
        sut.startSession(session, doUpload = false, from = "test")
        return session
    }

    /** Open a session on [server] and wait until it has its dataset, so a purge can start right away. */
    private fun startSessionAndWait(server: FakeTidepool): Session {
        val session = startSession(server)
        verify(authFlowOut, timeout(2000)).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED), any())
        return session
    }

    @Test
    fun `resetInstance asks for a fresh login`() {
        sut.resetInstance()

        verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
    }

    @Test
    fun `doLogin does nothing when the session is already there`() {
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED)

        sut.doLogin(from = "test")

        verifyNoLoginStarted()
    }

    @Test
    fun `doLogin does nothing while a token is being fetched`() {
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.FETCHING_TOKEN)

        sut.doLogin(from = "test")

        // The state was set up by the test, the uploader must not start a second try
        verifyNoLoginStarted()
    }

    @Test
    fun `doLogin gets a token when there is no session`() {
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)

        sut.doLogin(from = "test")

        verifyLoginStarted()
    }

    @Test
    fun `upload is skipped when connectivity settings do not allow it`() {
        whenever(receiverDelegate.allowed).thenReturn(false)

        runBlocking { sut.doUpload("test") }

        verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.BLOCKED), isNull())
        runBlocking { verify(uploadChunk, never()).getNext(anyOrNull()) }
    }

    @Test
    fun `upload without a session starts a login`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)

        runBlocking { sut.doUpload("test") }

        verifyLoginStarted()
        runBlocking { verify(uploadChunk, never()).getNext(anyOrNull()) }
    }

    @Test
    fun `session without a user id fails instead of uploading`() {
        whenever(receiverDelegate.allowed).thenReturn(true)

        startSessionWithUser(userId = null)

        verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.FAILED), any())
    }

    @Test
    fun `empty chunk is reported and nothing is sent`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now) // up to date, so no follow-up upload
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(emptyList()) }
        startSessionWithUser()

        runBlocking { sut.doUpload("test") }

        assertThat(statusMessages()).contains("No data to upload")
        verify(uploadChunk, never()).setLastEnd(any())
    }

    @Test
    fun `upload asks for a valid token and does not reuse the one from the session start`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now) // up to date, so no follow-up upload
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(listOf("""[{"type":"cbg"}]""")) }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        val session = startSessionAndWait(server) // the session started with "token-1"
        answerTokens { "fresh-$it" } // and AppAuth has refreshed it since

        runBlocking { sut.doUpload("test") }

        assertThat(server.uploadCalls).containsExactly("fresh-1" to "aaps-1")
        assertThat(session.token).isEqualTo("fresh-1")
        verify(uploadChunk, timeout(2000)).setLastEnd(any())
    }

    @Test
    fun `upload without a valid token fails and frees the upload lock`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now)
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(listOf("""[{"type":"cbg"}]""")) }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        startSessionAndWait(server)
        answerTokens { null }

        runBlocking { sut.doUpload("test") }

        assertThat(server.uploadCalls).isEmpty()
        verify(authFlowOut).updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "Upload FAILED - no valid token")
        // The lock is free again: the next upload is not skipped as "already in progress"
        answerTokens { "fresh-$it" }
        runBlocking { sut.doUpload("test") }
        assertThat(server.uploadCalls).containsExactly("fresh-1" to "aaps-1")
    }

    @Test
    fun `a window larger than one batch is sent in parts and LastEnd moves once at the end`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now)
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(listOf("[1]", "[2]", "[3]")) }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        startSessionAndWait(server)

        runBlocking { sut.doUpload("test") }

        verify(uploadChunk, timeout(2000)).setLastEnd(any())
        assertThat(server.uploads).isEqualTo(3)
        assertThat(statusMessages()).containsAtLeast("Uploading part 1 of 3", "Uploading part 2 of 3", "Uploading part 3 of 3")
    }

    @Test
    fun `a failed part keeps LastEnd so the window is sent again, and frees the upload lock`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now)
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(listOf("[1]", "[2]", "[3]")) }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        server.failUpload = 2
        startSessionAndWait(server)

        runBlocking { sut.doUpload("test") }

        verify(authFlowOut, timeout(2000)).updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "Upload FAILED")
        assertThat(server.uploads).isEqualTo(2) // part 3 is not sent after part 2 failed
        verify(uploadChunk, never()).setLastEnd(any())
        // The lock is free again: the next trigger reads a new window instead of being skipped
        runBlocking { sut.doUpload("test") }
        runBlocking { verify(uploadChunk, timeout(2000).times(2)).getNext(anyOrNull()) }
    }

    @Test
    fun `an upload answered with success but no body counts as failed`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(uploadChunk.getLastEnd()).thenReturn(now)
        runBlocking { whenever(uploadChunk.getNext(anyOrNull())).thenReturn(listOf("[1]")) }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        server.emptyUploadReply = true
        startSessionAndWait(server)

        runBlocking { sut.doUpload("test") }

        verify(authFlowOut).updateConnectionStatus(AuthFlowOut.ConnectionStatus.FAILED, "Upload FAILED")
        verify(uploadChunk, never()).setLastEnd(any())
        assertThat(statusMessages().any { it.startsWith("Data Upload test was not successful: 200") }).isTrue()
    }

    @Test
    fun `purge is refused when connectivity settings do not allow it`() {
        whenever(receiverDelegate.allowed).thenReturn(false)

        sut.purge()

        assertThat(statusMessages()).contains("Purge blocked by connectivity settings")
        verifyNoLoginStarted()
    }

    @Test
    fun `purge without an open dataset connects first`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)

        sut.purge()

        assertThat(statusMessages()).contains("Purge: connecting…")
        verifyLoginStarted()
    }

    @Test
    fun `session reuses only a dataset with the AAPS device id`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)

        startSessionAndWait(server)

        assertThat(server.listCalls.map { listOf(it.userId, it.clientName, it.deviceId, it.size) })
            .containsExactly(listOf("user-1", "info.nightscout.androidaps", "AAPS", 1))
        assertThat(server.openCalls).isEmpty()
    }

    @Test
    fun `session opens a new dataset when only a dataset from before 4_0 exists`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        // Before 4.0 the dataset was named "Tandem" and had no device id
        val server = FakeTidepool("tandem-1" to null)

        startSessionAndWait(server)

        assertThat(server.openCalls).containsExactly("user-1")
        assertThat(server.dataSets.map { it.first }).containsExactly("new-1", "tandem-1").inOrder()
    }

    @Test
    fun `purge deletes the dataset and drops the session`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        startSessionAndWait(server)

        sut.purge()

        eventually {
            assertThat(server.deleted).containsExactly("aaps-1")
            assertThat(statusMessages()).contains("All Tidepool data purged (deleted datasets: 1)")
            // resetInstance(), so the next upload opens a fresh dataset
            verify(authFlowOut).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
        }
    }

    @Test
    fun `purge deletes older datasets too and the one in use last`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME, "tandem-2" to null, "tandem-1" to null)
        startSessionAndWait(server)

        sut.purge()

        eventually {
            assertThat(server.deleted).containsExactly("tandem-2", "tandem-1", "aaps-1").inOrder()
            assertThat(server.dataSets).isEmpty()
            assertThat(statusMessages()).contains("All Tidepool data purged (deleted datasets: 3)")
        }
    }

    @Test
    fun `purge deletes more datasets than fit in one list`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        // Old versions opened a new dataset on every session, so there can be many
        val old = (1..TidepoolUploader.PURGE_PAGE_SIZE + 20).map { "old-$it" to null }
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME, *old.toTypedArray())
        startSessionAndWait(server)

        sut.purge()

        eventually {
            assertThat(server.dataSets).isEmpty()
            assertThat(server.deleted.last()).isEqualTo("aaps-1")
            // A long purge shows progress, then the total
            val messages = statusMessages()
            assertThat(messages.filter { it.startsWith("Purge: ") }).containsExactly("Purge: 100 datasets deleted")
            assertThat(messages).contains("All Tidepool data purged (deleted datasets: ${TidepoolUploader.PURGE_PAGE_SIZE + 21})")
        }
    }

    @Test
    fun `purge asks for a valid token before every call`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME, "tandem-1" to null)
        val session = startSessionAndWait(server)
        // A long purge outlives the access token, so AppAuth hands out a new one on the way
        answerTokens { "fresh-$it" }

        sut.purge()

        eventually { assertThat(statusMessages()).contains("All Tidepool data purged (deleted datasets: 2)") }
        // The session start listed with "token-1"; the purge lists without a device id
        assertThat(server.listCalls.filter { it.deviceId == null }.map { it.token }).containsExactly("fresh-1", "fresh-3").inOrder()
        assertThat(server.deleteCalls).containsExactly("fresh-2" to "tandem-1", "fresh-4" to "aaps-1").inOrder()
        assertThat(session.token).isEqualTo("fresh-4")
    }

    @Test
    fun `purge stops when the token cannot be refreshed and keeps the dataset in use`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME, "tandem-1" to null)
        startSessionAndWait(server)
        answerTokens { if (it == 1) "fresh-1" else null } // the list works, then the refresh fails

        sut.purge()

        eventually { assertThat(statusMessages()).contains("Purge failed - not connected") }
        assertThat(server.deleteCalls).isEmpty()
        assertThat(server.dataSets).hasSize(2)
        verify(authFlowOut, never()).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
    }

    @Test
    fun `purge keeps the session when the server refuses`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        server.deleteAnswer = { TidepoolResponse(403, "Forbidden", null) }
        startSessionAndWait(server)

        sut.purge()

        eventually { assertThat(statusMessages().any { it.startsWith("Purge FAILED: 403") }).isTrue() }
        verify(authFlowOut, never()).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
    }

    @Test
    fun `purge stops at the first failure and keeps the dataset in use`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME, "tandem-1" to null)
        server.deleteAnswer = { throw IOException("no route to host") }
        startSessionAndWait(server)

        sut.purge()

        eventually { assertThat(statusMessages().any { it.startsWith("Purge FAILED") }).isTrue() }
        assertThat(server.deleteCalls.map { it.second }).doesNotContain("aaps-1")
        assertThat(server.dataSets).hasSize(2)
        verify(authFlowOut, never()).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
    }

    @Test
    fun `purge waits for the dataset and deletes it once the session is open`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)

        sut.purge() // no session yet, so the purge waits and a login is started
        startSession(server)

        eventually { assertThat(server.deleteCalls.map { it.second }).contains("aaps-1") }
    }

    @Test
    fun `purge also deletes old datasets when the session had to open a new one`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)
        // Only a dataset from before 4.0, so the session opens a new one first
        val server = FakeTidepool("tandem-1" to null)

        sut.purge()
        startSession(server)

        // resetInstance() runs after the last delete has finished
        verify(authFlowOut, timeout(2000)).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.NONE), isNull())
        assertThat(server.deleted).containsExactly("tandem-1", "new-1").inOrder()
    }

    @Test
    fun `purge is dropped when the login fails and does not run later`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        whenever(authFlowOut.connectionStatus).thenReturn(AuthFlowOut.ConnectionStatus.NO_SESSION)
        failTokenRefresh()

        sut.purge()

        eventually { assertThat(statusMessages()).contains("Purge failed - not connected") }
        // A later upload opens a session, but the old purge must not delete anything now
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        startSession(server)
        verify(authFlowOut, timeout(2000)).updateConnectionStatus(eq(AuthFlowOut.ConnectionStatus.SESSION_ESTABLISHED), any())
        Thread.sleep(300)
        assertThat(server.deleteCalls).isEmpty()
    }

    @Test
    fun `purge reports a network failure`() {
        whenever(receiverDelegate.allowed).thenReturn(true)
        val server = FakeTidepool("aaps-1" to TidepoolDevice.NAME)
        server.deleteAnswer = { throw IOException("no route to host") }
        startSessionAndWait(server)

        sut.purge()

        eventually { assertThat(statusMessages().any { it.contains("no route to host") }).isTrue() }
    }
}
