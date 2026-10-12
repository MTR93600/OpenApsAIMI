package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPackId
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionDocument
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the AIMI Control Center screen shows, written against the screen as it is today.
 *
 * The screen is driven only through its public entry point `AimiControlCenterScreen`, so the same
 * assertions hold wherever the file lives. Every string is read from the Android resource table,
 * so a wrong or missing string owner shows up as a failure instead of as blank text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AimiControlCenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var preferences: Preferences
    private lateinit var title: String
    private lateinit var noPendingChanges: String
    private lateinit var tpoSessionTitle: String
    private lateinit var tpoPending: String
    private lateinit var tpoRevertNow: String
    private lateinit var showDetails: String
    private lateinit var hideDetails: String

    /**
     * Storage with nothing in it. Every read answers "no file", which is what a phone looks like
     * before AIMI has written a runtime log, so the screen falls back to its own empty states.
     */
    private object EmptyStorage : AimiStorage {

        private val nowhere = AimiPath("nowhere")

        override fun directory(): AimiPath = nowhere
        override fun file(name: String): AimiPath = nowhere
        override fun file(subdirectory: String, name: String): AimiPath = nowhere
        override fun resolve(directory: AimiPath, name: String): AimiPath = nowhere
        override fun sibling(path: AimiPath, suffix: String): AimiPath = nowhere
        override fun exists(path: AimiPath): Boolean = false
        override fun canRead(path: AimiPath): Boolean = false
        override fun canWrite(path: AimiPath): Boolean = false
        override fun createDirectories(path: AimiPath): Boolean = false
        override fun createParentDirectories(path: AimiPath): Boolean = false
        override fun createFile(path: AimiPath): Boolean = false
        override fun readText(path: AimiPath): String? = null
        override fun readLines(path: AimiPath): List<String> = emptyList()
        override fun readFirstLine(path: AimiPath): String? = null
        override fun forEachLine(path: AimiPath, action: (String) -> Unit): Boolean = false
        override fun writeText(path: AimiPath, text: String): Boolean = false
        override fun appendText(path: AimiPath, text: String): Boolean = false
        override fun displayPath(path: AimiPath): String = path.value
        override fun healthReport(): String = ""
        override fun fallbackFile(name: String): AimiPath = nowhere
        override fun delete(path: AimiPath): Boolean = false
        override fun replaceText(path: AimiPath, text: String): Boolean = false
        override fun replaceKeepingBackup(path: AimiPath, text: String): Boolean = false
        override fun rewriteLines(path: AimiPath, temporary: AimiPath, lines: Sequence<String>): Boolean = false
        override fun readTailLines(path: AimiPath, maxLines: Int): List<String> = emptyList()
        override fun sizeBytes(path: AimiPath): Long = 0L
        override fun lastChar(path: AimiPath): Char? = null
        override fun copy(from: AimiPath, to: AimiPath): Boolean = false
        override fun lastModifiedMs(path: AimiPath): Long? = null
    }

    @Before
    fun setUp() {
        // What MainApp does at startup: a TextRef.Named is resolved through this registry, so
        // without it every label renders as its raw name.
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
        preferences = JsonBackedPreferences(EmptyStorage)
        val context = RuntimeEnvironment.getApplication()
        title = context.getString(R.string.aimi_control_center_title)
        noPendingChanges = context.getString(R.string.aimi_control_center_no_pending_changes)
        tpoSessionTitle = context.getString(R.string.aimi_tpo_active_session_title)
        tpoPending = context.getString(R.string.aimi_tpo_active_session_pending)
        tpoRevertNow = context.getString(R.string.aimi_tpo_revert_now)
        showDetails = context.getString(R.string.aimi_control_center_show_current_details)
        hideDetails = context.getString(R.string.aimi_control_center_hide_details)
    }

    private fun orchestratorWith(session: TpoSessionDocument?): TpoOrchestrator {
        val orchestrator = mock<TpoOrchestrator>()
        whenever(orchestrator.currentSession()).thenReturn(session)
        return orchestrator
    }

    private fun session(status: TpoSessionStatus): TpoSessionDocument =
        TpoSessionDocument(
            sessionId = "test-session",
            packId = TpoPackId.POST_HYPO_RECOVERY,
            tier = TuningStepTier.MICRO,
            status = status,
            startedAtMs = 0L,
            expiresAtMs = Long.MAX_VALUE / 2,
            triggerAlgoConfidence = 0.5,
            triggerReasonCodes = emptyList(),
            baseline = emptyMap(),
            overlay = emptyMap(),
        )

    private fun setScreen(orchestrator: TpoOrchestrator) {
        compose.setContent {
            // The production host (the preference screen shell) already provides this local before
            // it draws any screen content, and `ProvidePreferenceTheme` reads it.
            CompositionLocalProvider(LocalPreferences provides preferences) {
                MaterialTheme {
                    AimiControlCenterScreen(
                        preferences = preferences,
                        tpoOrchestrator = orchestrator,
                        storage = EmptyStorage,
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun showsTitle_andNoPendingChanges_forAnUntouchedDraft() {
        setScreen(orchestratorWith(null))

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(noPendingChanges).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tpoCard_isAbsent_whenThereIsNoSession() {
        setScreen(orchestratorWith(null))

        compose.onAllNodesWithText(tpoSessionTitle).assertCountEquals(0)
        compose.onAllNodesWithText(tpoRevertNow).assertCountEquals(0)
    }

    @Test
    fun tpoCard_offersRevertNow_forAnActiveSession() {
        setScreen(orchestratorWith(session(TpoSessionStatus.ACTIVE)))

        compose.onNodeWithText(tpoSessionTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tpoRevertNow).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(tpoPending).assertCountEquals(0)
    }

    @Test
    fun tpoCard_waitsForTheLlm_andHidesRevertNow_forAPendingSession() {
        setScreen(orchestratorWith(session(TpoSessionStatus.PENDING_LLM)))

        compose.onNodeWithText(tpoSessionTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tpoPending).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(tpoRevertNow).assertCountEquals(0)
    }

    @Test
    fun familyCard_detailsAreHidden_untilTheUserAsksForThem() {
        setScreen(orchestratorWith(null))

        // One toggle per behaviour family, all closed, so no card shows "Hide details" yet.
        val closedCount = compose.onAllNodesWithText(showDetails).fetchSemanticsNodes().size
        assert(closedCount >= 2) { "expected several family cards, found $closedCount" }
        compose.onAllNodesWithText(hideDetails).assertCountEquals(0)

        compose.onAllNodesWithText(showDetails)[0].performScrollTo().performClick()

        compose.onAllNodesWithText(hideDetails).assertCountEquals(1)
        compose.onAllNodesWithText(showDetails).assertCountEquals(closedCount - 1)
    }
}
