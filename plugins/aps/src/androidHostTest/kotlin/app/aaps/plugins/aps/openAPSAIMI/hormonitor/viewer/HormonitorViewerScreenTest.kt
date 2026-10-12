package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the Hormonitor viewer shows, written against the screen as it is today.
 *
 * The screen is driven only through its public entry point `HormonitorViewerScreen`, so the same
 * assertions hold wherever the file lives. Every label is read from the Android resource table, so
 * a wrong or missing string owner shows up as a failure instead of as blank text. The numbers are
 * spelled out as the exact text a user reads, so a changed format or a swapped field fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HormonitorViewerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var preferences: Preferences
    private lateinit var title: String
    private lateinit var emptyText: String
    private lateinit var noEventsText: String
    private lateinit var backDescription: String
    private lateinit var tirLabel: String
    private lateinit var reliabilityLabel: String
    private lateinit var recordsLabel: String
    private lateinit var patientStoryLabel: String
    private lateinit var physioSnapshotLabel: String

    /** The day the fixtures are written for, in the device's own time zone. */
    private val baseTs = 1_700_000_000_000L
    private val day: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(baseTs))

    @Before
    fun setUp() {
        // What MainApp does at startup: a TextRef.Named is resolved through this registry, so
        // without it every label renders as its raw name.
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
        preferences = JsonBackedPreferences(EmptyStorage)
        val context = RuntimeEnvironment.getApplication()
        title = context.getString(R.string.aimi_hormonitor_viewer_title)
        emptyText = context.getString(R.string.aimi_hormonitor_viewer_empty)
        noEventsText = context.getString(R.string.aimi_hormonitor_viewer_no_events)
        backDescription = context.getString(CoreUiR.string.back)
        tirLabel = context.getString(R.string.aimi_hormonitor_tir)
        reliabilityLabel = context.getString(R.string.aimi_hormonitor_reliability)
        recordsLabel = context.getString(R.string.aimi_hormonitor_records)
        patientStoryLabel = context.getString(R.string.aimi_hormonitor_patient_story)
        physioSnapshotLabel = context.getString(R.string.aimi_hormonitor_physio_snapshot)
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** One cumulative daily record, the compact file the day list is built from. */
    private fun writeDailyFile(dayLocal: String) {
        tempFolder.root.resolve(DAILY_FILE).writeText(
            JSONObject().apply {
                put("day_local", dayLocal)
                put("schema_version", "1.4.0")
                put("tir_low_pct", 4.0)
                put("tir_in_range_pct", 82.0)
                // tir_above_pct is left out on purpose: it is the missing-value case.
                put("tdd_24h_total_u", 45.0)
                put("decision_count_total", 3)
                put("decision_count_smb", 1)
                put("source_reliability_score", 0.9)
                put("source_stale_flag", true)
            }.toString() + "\n",
        )
    }

    /**
     * Three loop records on the same day. Two carry a patient story and all three carry a valid
     * physio snapshot, so the two integrity coverages differ and can be told apart on screen.
     * The SMB factor spans 0.8 to 1.2 so its min, mean and max are three different numbers.
     */
    private fun writeEventFile(dayTs: Long) {
        val lines = listOf(
            event(dayTs, smbFactor = 0.8, patientMode = "DAWN_ENDOGENOUS"),
            event(dayTs + 300_000, smbFactor = 1.0, patientMode = "DAWN_ENDOGENOUS"),
            event(dayTs + 600_000, smbFactor = 1.2, patientMode = null),
        )
        tempFolder.root.resolve(EVENT_FILE).writeText(lines.joinToString("\n"))
    }

    private fun event(ts: Long, smbFactor: Double, patientMode: String?): String =
        JSONObject().apply {
            put("timestamp", ts)
            put("schema_version", "1.4.0")
            put("physio_state", "RESTING")
            put("physio_confidence", 0.8)
            put("safety_gate", "SafetyPass")
            put("thyroid_status", "EUTHYROID")
            put("physio_snapshot_valid_flag", true)
            put("smb_factor", smbFactor)
            if (patientMode != null) {
                put(
                    "patient_story",
                    JSONObject().apply {
                        put("patient_mode", patientMode)
                        put("patient_mode_confidence", 0.9)
                        put("patient_narrative", "narrative for $patientMode")
                        put("patient_reason_codes", JSONArray().apply { put("CAUSAL_$patientMode") })
                    },
                )
            }
        }.toString()

    /** An [AimiStorage] rooted at the temporary folder, as in `HormonitorReaderTest`. */
    private fun storage(): AimiStorage {
        val helper = mock<AimiStorageHelper>()
        for (name in listOf(DAILY_FILE, EVENT_FILE)) {
            whenever(helper.getAimiFile(name)).thenReturn(File(tempFolder.root, name))
        }
        return AndroidAimiStorage(helper)
    }

    /**
     * The only place the screen is built. The production host provides `LocalPreferences` before it
     * draws any screen content, and `ProvidePreferenceTheme` reads it.
     */
    private fun setScreen(storage: AimiStorage, onBack: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                MaterialTheme {
                    // The same formatter the plugin passes in production.
                    HormonitorViewerScreen(storage = storage, dateFormatter = AndroidDateFormatter(), onBack = onBack)
                }
            }
        }
    }

    /** The reader loads off the main thread, so the first frame is still the loading spinner. */
    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- tests --------------------------------------------------------------------------------

    @Test
    fun showsTitle_andTheEmptyState_whenNoStudyFileExists() {
        setScreen(storage())

        compose.onNodeWithText(title).assertIsDisplayed()
        awaitText(emptyText)
        compose.onNodeWithText(emptyText).assertIsDisplayed()
    }

    @Test
    fun dayChip_showsTheShortWeekdayDate_whileTheCardKeepsTheIsoDay() {
        writeDailyFile(day)
        setScreen(storage())

        // Exactly what the platform formatter makes of "yyyy-MM-dd" read as US and written as
        // "EEE d MMM" in the device locale. The screen must still go through that formatter.
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!
        val shortDay = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(parsed)

        awaitText(shortDay)
        compose.onNodeWithText(shortDay).assertIsDisplayed()
        // The card header keeps the raw day key, so the two are different pieces of text.
        compose.onNodeWithText(day).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun summaryCard_showsTirAsLowInRangeHigh_withADashForTheMissingValue() {
        writeDailyFile(day)
        setScreen(storage())

        awaitText(tirLabel)
        compose.onNodeWithText("4% / 82% / —").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun summaryCard_showsSourceReliabilityAsAWholePercent_andMarksItStale() {
        writeDailyFile(day)
        setScreen(storage())

        awaitText(reliabilityLabel)
        compose.onNodeWithText("90%  (stale)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun integritySection_tellsTheTwoCoveragesApart() {
        writeDailyFile(day)
        writeEventFile(baseTs)
        setScreen(storage())

        awaitText(recordsLabel)
        compose.onNodeWithText(patientStoryLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(physioSnapshotLabel).performScrollTo().assertIsDisplayed()
        // Two of three records carried a patient story, all three carried a physio snapshot.
        compose.onNodeWithText("67%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100%").performScrollTo().assertIsDisplayed()
        // The record count, shown on its own.
        compose.onAllNodesWithText("3").assertCountEquals(1)
    }

    @Test
    fun physioSection_showsTheSmbFactorAsMinMaxThenMean() {
        writeDailyFile(day)
        writeEventFile(baseTs)
        setScreen(storage())

        awaitText(recordsLabel)
        compose.onNodeWithText("0.80 – 1.20  (mean 1.00)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun showsTheNoEventsText_whenTheDayHasASummaryButNoRecords() {
        writeDailyFile(day)
        // Events exist, but on a different day, so this day has no detail.
        writeEventFile(baseTs - 10L * 24 * 60 * 60 * 1000)
        setScreen(storage())

        awaitText(noEventsText)
        compose.onNodeWithText(noEventsText).assertIsDisplayed()
    }

    @Test
    fun backButton_callsOnBack() {
        var backs = 0
        setScreen(storage(), onBack = { backs++ })

        compose.onNodeWithContentDescription(backDescription).performClick()

        assert(backs == 1) { "expected exactly one back callback, got $backs" }
    }

    private companion object {

        // Spelled out, because the reader keeps these names in a private companion.
        const val DAILY_FILE = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl"
        const val EVENT_FILE = "AIMI_HORMONITOR_event_stream_v1.jsonl"
    }

    /**
     * Storage with nothing in it, only used to give [JsonBackedPreferences] a backing store that
     * always answers "no file" so every preference falls back to its own default.
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
}
