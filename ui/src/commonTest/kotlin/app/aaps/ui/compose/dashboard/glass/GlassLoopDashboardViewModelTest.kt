package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of `GlassLoopDashboardViewModelTest` on `dev_OAPSAIMI`, on the pure state builder: the
 * ViewModel only reads the values and hands them to [buildGlassLoopDashboardState].
 */
class GlassLoopDashboardViewModelTest {

    /** Writes each text as `key(args)`, so a test sees which string and which values were used. */
    private val format = object : GlassLoopDashboardFormat {
        override fun text(ref: TextRef, vararg args: Any?): String =
            if (args.isEmpty()) key(ref) else "${key(ref)}(${args.joinToString()})"

        override fun glucose(mgdl: Double): String = "bg$mgdl"
        override fun signedGlucose(mgdl: Double): String = "sbg$mgdl"
        override fun glucoseInUnits(mgdl: Double): Double = mgdl
        override fun time(epochMs: Long): String = "14:32"
        override fun dateTime(epochMs: Long): String = "date$epochMs"
        override fun minutesAgo(epochMs: Long): String = "ago$epochMs"
    }

    private fun key(ref: TextRef): String = (ref as TextRef.Named).name

    private fun input(
        lastRun: GlassLoopDashboardInput.LastRun? = null,
        glucose: GlassLoopDashboardInput.Glucose? = null,
        aimi: AimiGlassDashboardData = AimiGlassDashboardData(),
    ) = GlassLoopDashboardInput(
        hourOfDay = 9,
        isWeekend = false,
        buildVersion = "3.5.0",
        glucoseUnit = GlucoseUnit.MGDL,
        lastRun = lastRun,
        glucose = glucose,
        aimi = aimi,
    )

    @Test
    fun `a null lastAPSResult produces a state with defaulted text and no crash`() {
        val state = buildGlassLoopDashboardState(input(), format)

        assertFalse(state.isLoading)
        assertEquals("", state.lastRunTime)
        assertEquals("3.5.0", state.buildVersionText)
        assertEquals("--", state.glucoseText)
        assertEquals("--", state.targetBgText)
        assertEquals("--", state.isfText)
        assertEquals("--", state.iobText)
        assertEquals("--", state.maxIobProfileText)
        assertEquals("--", state.requestedSmbText)
        assertFalse(state.hasTrajectory)
        assertFalse(state.hasPhysio)
        assertFalse(state.hasMlTraining)
        assertEquals(key(UiStrings.dashboard_glass_loop_ml_smb_never_attempted), state.smbTrainingStatusText)
        assertEquals(key(UiStrings.dashboard_glass_loop_ml_file_missing), state.basalModelFileText)
        assertEquals("", state.smbTrainingDetailText)
    }

    @Test
    fun `a real lastAPSResult with date greater than zero produces a non-empty lastRunTime`() {
        val state = buildGlassLoopDashboardState(
            input(
                lastRun = GlassLoopDashboardInput.LastRun(dateMs = 1_000_000L, smbUnits = 0.5, iobUnits = 0.3),
                glucose = GlassLoopDashboardInput.Glucose(glucoseMgdl = 120.0, deltaMgdl = 2.0, shortAvgDeltaMgdl = 1.5, longAvgDeltaMgdl = -1.0),
            ),
            format,
        )

        assertEquals("14:32", state.lastRunTime)
        assertTrue(state.delta5mIsPositive)
        assertTrue(state.shortAvgDeltaIsPositive)
        assertFalse(state.longAvgDeltaIsPositive)
        assertEquals("bg120.0", state.glucoseText)
        assertEquals("sbg2.0", state.delta5mText)
        assertEquals("format_insulin_units(0.5)", state.requestedSmbText)
        assertEquals("format_insulin_units(0.3)", state.iobText)
    }

    @Test
    fun `a loop that has never run shows no run time`() {
        val state = buildGlassLoopDashboardState(
            input(lastRun = GlassLoopDashboardInput.LastRun(dateMs = 0L, smbUnits = 0.0, iobUnits = null)),
            format,
        )

        assertEquals("", state.lastRunTime)
        assertEquals("--", state.iobText)
    }

    @Test
    fun `aimi key factors and safety use the DEV strings and decimals`() {
        val state = buildGlassLoopDashboardState(
            input(
                aimi = AimiGlassDashboardData(
                    keyFactors = AimiGlassDashboardData.KeyFactors(
                        targetBgMgdl = 100.0,
                        maxIobUnits = 3.5,
                        maxSmbUnits = 1.2,
                        isfMgdlPerUnit = 40.0,
                        stableBgMinutes = 35.6,
                    ),
                    safety = AimiGlassDashboardData.Safety(
                        tdd7DaysPerHour = 0.8,
                        lowTirLastHourPercent = 1.4,
                        lowTirTodayPercent = 2.6,
                        stepsLast5Minutes = 42,
                    ),
                ),
            ),
            format,
        )

        assertEquals("bg100.0", state.targetBgText)
        assertEquals("${key(InterfacesStrings.format_insulin_units)}(3.5)", state.maxIobProfileText)
        assertEquals("${key(InterfacesStrings.format_insulin_units)}(1.2)", state.maxSmbProfileText)
        assertEquals("dashboard_glass_loop_isf_value(40.0, profile_isf_units_mgdl)", state.isfText)
        assertEquals("${key(CoreUiStrings.format_mins)}(36)", state.stableMinutesText)
        assertEquals("${key(CoreUiStrings.pump_base_basal_rate)}(0.8)", state.tdd7DaysPerHourText)
        assertEquals("${key(CoreUiStrings.units_format_percent)}(1)", state.tirLow1hText)
        assertEquals("${key(CoreUiStrings.units_format_percent)}(3)", state.tirLow24hText)
        assertEquals("dashboard_glass_loop_steps_5m(42)", state.steps5mText)
    }

    @Test
    fun `missing aimi values stay unavailable instead of zero`() {
        val state = buildGlassLoopDashboardState(
            input(
                aimi = AimiGlassDashboardData(
                    safety = AimiGlassDashboardData.Safety(
                        tdd7DaysPerHour = null,
                        lowTirLastHourPercent = null,
                        lowTirTodayPercent = null,
                        stepsLast5Minutes = null,
                    ),
                ),
            ),
            format,
        )

        assertEquals("--", state.tdd7DaysPerHourText)
        assertEquals("--", state.tirLow1hText)
        assertEquals("--", state.steps5mText)
        assertEquals("--", state.stableMinutesText)
    }

    @Test
    fun `a stale forecast is not shown`() {
        fun trajectory(stale: Boolean) = AimiGlassDashboardData(
            trajectory = AimiGlassDashboardData.Trajectory(
                hybridTerminalMgdl = 126.0,
                iobTerminalMgdl = 103.0,
                cobTerminalMgdl = null,
                uamTerminalMgdl = 120.0,
                publishedAtMs = 1L,
                isStale = stale,
            ),
        )

        val fresh = buildGlassLoopDashboardState(input(aimi = trajectory(stale = false)), format)
        assertTrue(fresh.hasTrajectory)
        assertEquals("bg126.0", fresh.trajectoryHybridText)
        assertEquals("bg103.0", fresh.trajectoryIobText)
        assertEquals("--", fresh.trajectoryCobText)

        val stale = buildGlassLoopDashboardState(input(aimi = trajectory(stale = true)), format)
        assertFalse(stale.hasTrajectory)
        assertEquals("--", stale.trajectoryHybridText)
    }

    @Test
    fun `physio comes from the patient state summary`() {
        val state = buildGlassLoopDashboardState(
            input(
                aimi = AimiGlassDashboardData(
                    physioPresentation = AimiGlassDashboardData.Physio.Presentation(
                        modeHeadline = "Mode",
                        intentSummary = "Intent",
                        thermalSummary = "Thermal",
                        updatedSummary = "Updated",
                    ),
                ),
            ),
            format,
        )

        assertTrue(state.hasPhysio)
        assertEquals("Mode", state.physioModeText)
        assertEquals("Intent", state.physioIntentText)
        assertEquals("Thermal", state.physioThermalText)
        assertEquals("Updated", state.physioUpdatedText)
    }

    @Test
    fun `ml training shows the basal and smb trainer state`() {
        val smbFile = AimiGlassDashboardData.ModelFile(exists = true, modifiedAtMs = 7L)
        fun data(attempt: AimiGlassDashboardData.SmbAttempt?, waiting: AimiGlassDashboardData.SmbWaiting?) = AimiGlassDashboardData(
            training = AimiGlassDashboardData.Training(
                basal = AimiGlassDashboardData.BasalTraining(
                    lastTrainedAtMs = 5L,
                    sampleCount = 245,
                    circuitOpen = true,
                    modelFile = AimiGlassDashboardData.ModelFile(exists = false, modifiedAtMs = null),
                ),
                smb = AimiGlassDashboardData.SmbTraining(
                    lastAttempt = attempt,
                    waiting = waiting,
                    lastTrainedAtMs = 0L,
                    circuitOpen = false,
                    modelLoaded = true,
                    modelFile = smbFile,
                ),
            ),
        )
        fun attempt(detail: String, kept: Int) = AimiGlassDashboardData.SmbAttempt(
            atMs = 9L,
            outcome = AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES,
            totalRows = 2_000L,
            samplesAfterFilter = kept,
            rowsRejectedByFilter = 1_740L,
            detail = detail,
        )

        val withGate = buildGlassLoopDashboardState(input(aimi = data(attempt("spread too low", 260), null)), format)
        assertTrue(withGate.hasMlTraining)
        assertTrue(withGate.mlCircuitOpen)
        assertEquals("ago5", withGate.mlLastTrainedText)
        assertEquals("245", withGate.mlSampleCountText)
        assertEquals(key(UiStrings.dashboard_glass_loop_ml_file_missing), withGate.basalModelFileText)
        assertEquals("date7", withGate.smbModelFileText)
        assertEquals("dashboard_glass_loop_ml_smb_status_value(dashboard_glass_loop_ml_smb_outcome_rejected, ago9)", withGate.smbTrainingStatusText)
        assertEquals("spread too low", withGate.smbTrainingDetailText)

        val withRows = buildGlassLoopDashboardState(input(aimi = data(attempt("", 260), null)), format)
        assertEquals("dashboard_glass_loop_ml_smb_rows(260, 1740)", withRows.smbTrainingDetailText)

        val waiting = buildGlassLoopDashboardState(input(aimi = data(null, AimiGlassDashboardData.SmbWaiting.NOT_DUE)), format)
        assertEquals(key(UiStrings.dashboard_glass_loop_ml_smb_never_attempted), waiting.smbTrainingStatusText)
        assertEquals(key(UiStrings.dashboard_glass_loop_ml_smb_waiting_not_due), waiting.smbTrainingDetailText)
    }

    @Test
    fun `adaptive smoothing tiers use their own badge and message`() {
        assertEquals(
            AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_ok, UiStrings.adaptive_smoothing_quality_dialog_ok),
            adaptiveSmoothingQualityTexts(AdaptiveSmoothingQualityTier.OK),
        )
        assertEquals(
            AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_uncertain, UiStrings.adaptive_smoothing_quality_dialog_uncertain),
            adaptiveSmoothingQualityTexts(AdaptiveSmoothingQualityTier.UNCERTAIN),
        )
        assertEquals(
            AdaptiveSmoothingQualityTexts(UiStrings.adaptive_smoothing_quality_badge_bad, UiStrings.adaptive_smoothing_quality_dialog_bad),
            adaptiveSmoothingQualityTexts(AdaptiveSmoothingQualityTier.BAD),
        )
    }
}
