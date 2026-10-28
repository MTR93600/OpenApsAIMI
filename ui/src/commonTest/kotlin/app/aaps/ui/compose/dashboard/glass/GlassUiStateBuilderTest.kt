package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [buildGlassUiState]. Ported from `dev_OAPSAIMI` `GlassUiStateBuilderTest` and
 * `GlassOverviewComposeEmbeddedKtTest`; on this branch the inputs are the separate overview sources
 * instead of one `StatusCardState`.
 */
class GlassUiStateBuilderTest {

    private fun treatmentData(boluses: List<BolusGraphPoint> = emptyList(), carbs: List<CarbsGraphPoint> = emptyList()) =
        TreatmentGraphData(boluses = boluses, carbs = carbs, extendedBoluses = emptyList(), therapyEvents = emptyList(), calibrations = emptyList())

    private fun bgInfo(isOutdated: Boolean = false, timestamp: Long = 100_000L) = BgInfoData(
        bgValue = 120.0,
        bgText = "120",
        bgRange = BgRange.IN_RANGE,
        isOutdated = isOutdated,
        timestamp = timestamp,
        trendArrow = TrendArrow.FLAT,
        trendDescription = "",
        delta = 1.0,
        deltaText = "+1",
        shortAvgDelta = 1.0,
        shortAvgDeltaText = "+1",
        longAvgDelta = 1.0,
        longAvgDeltaText = "+1",
    )

    private fun build(
        bgInfo: BgInfoData? = null,
        unitText: String = "mg/dL",
        iobText: String? = null,
        lights: StatusUiState? = null,
        homeStatus: GlassHomeStatus? = null,
        treatmentData: TreatmentGraphData? = treatmentData(),
        warmup: CgmWarmupStatus? = null,
        nowMs: Long = 10_000L,
    ) = buildGlassUiState(
        bgInfo = bgInfo,
        timeAgo = "",
        deltaText = "",
        unitText = unitText,
        iobText = iobText,
        lights = lights,
        homeStatus = homeStatus,
        treatmentData = treatmentData,
        warmup = warmup,
        nowMs = nowMs,
    )

    @Test
    fun `no sources map to the all-defaults state`() {
        assertEquals(GlassUiState(), build(treatmentData = null))
    }

    @Test
    fun `most recent valid bolus label is used and invalid or older ones are ignored`() {
        val data = treatmentData(
            boluses = listOf(
                BolusGraphPoint(timestamp = 1000L, amount = 1.0, bolusType = BolusType.NORMAL, isValid = true, label = "1.00 U"),
                BolusGraphPoint(timestamp = 3000L, amount = 2.0, bolusType = BolusType.NORMAL, isValid = false, label = "2.00 U (invalid)"),
                BolusGraphPoint(timestamp = 2000L, amount = 1.5, bolusType = BolusType.SMB, isValid = true, label = "1.50 U"),
            )
        )
        assertEquals("1.50 U", build(treatmentData = data).lastBolusText)
    }

    @Test
    fun `a treatment in the future is not the last one`() {
        val data = treatmentData(
            carbs = listOf(
                CarbsGraphPoint(timestamp = 1000L, amount = 20.0, isValid = true, label = "20 g"),
                CarbsGraphPoint(timestamp = 50_000L, amount = 40.0, isValid = true, label = "40 g"),
            )
        )
        assertEquals("20 g", build(treatmentData = data, nowMs = 10_000L).lastCarbsText)
    }

    @Test
    fun `no valid bolus or carbs falls back to placeholder`() {
        val state = build()
        assertEquals("--", state.lastBolusText)
        assertEquals("--", state.lastCarbsText)
    }

    /** GLASS used to drop the CGM link state, so a reconnect showed nothing at all. */
    @Test
    fun `cgm link state and glucose time reach the glass state`() {
        val warmup = CgmWarmupStatus(active = true, phase = CgmWarmupStatus.Phase.RECONNECTING, remainingMs = null, endsAtEpochMs = null, message = null)
        val state = build(bgInfo = bgInfo(isOutdated = true, timestamp = 123_000L), warmup = warmup)
        assertEquals(warmup, state.warmup)
        assertFalse(state.isGlucoseActual)
        assertEquals(123_000L, state.glucoseTimestampMs)
    }

    @Test
    fun `glucose range and trend reach the state for colour and arrow`() {
        val state = build(bgInfo = bgInfo())
        assertEquals("120", state.currentBg)
        assertEquals(BgRange.IN_RANGE, state.glucoseRange)
        assertEquals(TrendArrow.FLAT, state.trendArrow)
    }

    @Test
    fun `active temp target maps to isTempTargetActive true`() {
        assertTrue(build(homeStatus = GlassHomeStatus(isTempTargetActive = true)).isTempTargetActive)
    }

    @Test
    fun `no active temp target maps to isTempTargetActive false`() {
        assertFalse(build(homeStatus = GlassHomeStatus(isTempTargetActive = false)).isTempTargetActive)
    }

    @Test
    fun `unit text is the one passed in and not hardcoded`() {
        assertEquals("mmol/L", build(unitText = "mmol/L").unit)
    }

    @Test
    fun `loop not running maps to loopIsRunning false`() {
        val state = build(homeStatus = GlassHomeStatus(loopStatusText = "Loop suspended", loopIsRunning = false))
        assertFalse(state.loopIsRunning)
        assertEquals("Loop suspended", state.loopStatusText)
    }

    @Test
    fun `home status texts reach their pills and missing ones show the placeholder`() {
        val state = build(
            iobText = "1.25 U",
            homeStatus = GlassHomeStatus(targetText = "100 - 120", effectiveBasalText = "0.80 U/h", stepsText = "4200", hrText = "72"),
        )
        assertEquals("1.25 U", state.iobText)
        assertEquals("100 - 120", state.targetText)
        assertEquals("0.80 U/h", state.basalPercentText)
        assertEquals("4200", state.stepsText)
        assertEquals("72", state.hrText)
        val empty = build(homeStatus = GlassHomeStatus())
        assertEquals("--", empty.iobText)
        assertEquals("--", empty.targetText)
        assertEquals("--", empty.basalPercentText)
    }

    @Test
    fun `status lights give the short age and status and label`() {
        val sensor = StatusItem(label = "Sensor", age = "3d4h", ageStatus = StatusLevel.WARNING, icon = IcCgmInsert)
        val state = build(lights = StatusUiState(sensorStatus = sensor))
        assertEquals("3d4h", state.sensorAge)
        assertEquals(StatusLevel.WARNING, state.sensorAgeStatus)
        assertEquals("Sensor", state.sensorLabel)
        assertEquals("--", state.insulinAge)
        assertNull(state.insulinLabel)
    }
}
