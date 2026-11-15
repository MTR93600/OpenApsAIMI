package app.aaps.ui.widget.glance

import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.IsfStep
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.SensitivityOverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.ui.widget.state.WidgetBgColorClass
import app.aaps.ui.widget.state.WidgetStateBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Step W1 moved the widget rule to the shared `WidgetStateBuilder`. The Android widgets must look
 * exactly as before, so every case here runs the frozen old loader ([LegacyWidgetStateLoader]) and
 * the new one on the same inputs and asks for the same [WidgetRenderState], field by field.
 */
internal class WidgetRenderStateParityTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    /** Everything one case sets. The defaults are a quiet closed loop with a fresh reading in range. */
    private data class Case(
        val units: GlucoseUnit = GlucoseUnit.MGDL,
        val lastBg: InMemoryGlucoseValue? = InMemoryGlucoseValue(timestamp = 1_800_000_000_000L - 2 * 60_000L, value = 120.0),
        val isLow: Boolean = false,
        val isHigh: Boolean = false,
        val trend: TrendArrow? = TrendArrow.FLAT,
        val delta: Double? = 2.0,
        val bolusIob: Double = 0.0,
        val basalIob: Double = 0.0,
        val cob: Double? = null,
        val futureCarbs: Double = 0.0,
        val aps: Boolean = true,
        val client: Boolean = false,
        val hasLastRun: Boolean = true,
        val carbsReq: Int = 0,
        val lastApsRun: Long = 1_800_000_000_000L - 60_000L,
        val newestCarbsTime: Long? = null,
        val loopTargetBg: Double = 0.0,
        val clientTargetBg: Double? = null,
        val tempTarget: TT? = null,
        val hasProfile: Boolean = true,
        /** The profile is a profile switch with a percentage other than 100. */
        val profileSwitchPercentage: Int? = null,
        val profileLow: Double = 100.0,
        val profileHigh: Double = 100.0,
        val tempBasalRunning: Boolean = false,
        val tempBasalAbsolute: Double = 0.0,
        val basal: Double = 1.0,
        val mode: RM.Mode = RM.Mode.CLOSED_LOOP,
        val sensitivity: SensitivityOverviewData = SensitivityOverviewData(),
        val opacity: Int = 25,
        val useBlack: Boolean = false
    )

    @Test
    fun freshInRangeMgdl() = assertSame(Case())

    @Test
    fun freshInRangeMmol() = assertSame(Case(units = GlucoseUnit.MMOL))

    @Test
    fun staleHighMmolWithTempTargetAndCarbsRequired() = assertSame(
        Case(
            units = GlucoseUnit.MMOL,
            lastBg = InMemoryGlucoseValue(timestamp = now - 25 * min, value = 250.0, smoothed = 246.0),
            isHigh = true,
            trend = null,
            bolusIob = 1.2344,
            basalIob = -0.3,
            cob = 20.0,
            futureCarbs = 10.0,
            carbsReq = 30,
            newestCarbsTime = now - 60 * min,
            tempTarget = tt(TT.Reason.EATING_SOON, duration = 45 * min),
            tempBasalRunning = true,
            tempBasalAbsolute = 2.0,
            sensitivity = SensitivityOverviewData(asText = "120%", isfFrom = "40", isfTo = "33", isfStep = IsfStep.DOWN, ratio = 1.2, isEnabled = true, hasData = true)
        )
    )

    @Test
    fun noReadingNoProfileNoStatus() = assertSame(
        Case(
            lastBg = null,
            trend = null,
            delta = null,
            hasProfile = false,
            hasLastRun = false,
            mode = RM.Mode.DISABLED_LOOP
        )
    )

    @Test
    fun lowWithAdjustedTargetAndLowTemp() = assertSame(
        Case(
            lastBg = InMemoryGlucoseValue(timestamp = now - min, value = 62.0),
            isLow = true,
            trend = TrendArrow.DOUBLE_DOWN,
            delta = -9.0,
            loopTargetBg = 90.0,
            carbsReq = 15,
            // Carbs eaten after the last loop run: no longer required.
            newestCarbsTime = now,
            lastApsRun = now - 2 * min,
            tempBasalRunning = true,
            tempBasalAbsolute = 0.0,
            mode = RM.Mode.SUSPENDED_BY_USER,
            sensitivity = SensitivityOverviewData(asText = "80%", isfFrom = "40", isfTo = "50", isfStep = IsfStep.UP, ratio = 0.8, isEnabled = false, hasData = true)
        )
    )

    @Test
    fun clientWithSceneTargetWithoutEnd() = assertSame(
        Case(
            aps = false,
            client = true,
            carbsReq = 20,
            clientTargetBg = 110.0,
            tempTarget = tt(TT.Reason.AUTOMATION, duration = Long.MAX_VALUE),
            useBlack = true,
            opacity = 255
        )
    )

    @Test
    fun clientWithAdjustedTarget() = assertSame(Case(aps = false, client = true, clientTargetBg = 110.0))

    @Test
    fun profileSwitchWithPercentageIsModified() = assertSame(Case(profileSwitchPercentage = 120))

    @Test
    fun tempBasalEqualToProfileIsNone() = assertSame(Case(tempBasalRunning = true, tempBasalAbsolute = 1.005))

    @Test
    fun everyRunningMode() {
        for (mode in RM.Mode.entries) assertSame(Case(mode = mode))
    }

    @Test
    fun everyTempTargetReason() {
        for (reason in TT.Reason.entries) assertSame(Case(tempTarget = tt(reason, duration = 30 * min)))
    }

    @Test
    fun everyTrendArrow() {
        for (trend in TrendArrow.entries) assertSame(Case(trend = trend))
    }

    @Test
    fun everySensitivityIcon() {
        for (enabled in listOf(true, false))
            for (ratio in listOf(0.7, 1.0, 1.3))
                for (step in IsfStep.entries)
                    assertSame(Case(sensitivity = SensitivityOverviewData(ratio = ratio, isEnabled = enabled, isfStep = step)))
    }

    @Test
    fun sharedStateMarksOldReadingAsStale() {
        val case = Case(lastBg = InMemoryGlucoseValue(timestamp = now - 25 * min, value = 250.0), isHigh = true)
        val state = runBlocking { builder(Fixture(case)).build() }
        assertThat(state.isStale).isTrue()
        assertThat(state.bgColorClass(now)).isEqualTo(WidgetBgColorClass.STALE)
    }

    /**
     * A new reading lands while the state is built, after a long gap. The old loader read the value
     * once and asked `isActualBg()` later, which already saw the new reading: an old value came out
     * not struck through. The age must come from the very value that is shown.
     */
    @Test
    fun oldValueIsStruckEvenWhenANewReadingLandsDuringTheBuild() {
        val old = InMemoryGlucoseValue(timestamp = now - 25 * min, value = 250.0)
        val new = InMemoryGlucoseValue(timestamp = now, value = 180.0)
        val fixture = Fixture(Case(lastBg = old, isHigh = true))
        // First read gives the old value, every later read the new one.
        whenever(fixture.lastBgData.lastBg()).thenReturn(old, new)
        whenever(fixture.lastBgData.isActualBg()).thenReturn(true)

        val state = runBlocking { builder(fixture).build() }
        assertThat(state.bgTimestamp).isEqualTo(old.timestamp)
        assertThat(state.isStale).isTrue()

        // The same race again, now through the Android loader.
        whenever(fixture.lastBgData.lastBg()).thenReturn(old, new)
        val render = runBlocking { WidgetStateLoader(builder(fixture), fixture.config, fixture.preferences).loadState(WIDGET_ID) }
        assertThat(render.strikeThrough).isTrue()
    }

    @Test
    fun sharedStateMarksProfileSwitchAsModified() {
        val state = runBlocking { builder(Fixture(Case(profileSwitchPercentage = 120))).build() }
        assertThat(state.profileModified).isTrue()
    }

    @Test
    fun sharedStateCarriesLiveActivityNumbers() {
        val case = Case(
            lastBg = InMemoryGlucoseValue(timestamp = now - 3 * min, value = 150.0, smoothed = 148.0),
            delta = 4.0,
            bolusIob = 1.5,
            basalIob = 0.25,
            cob = 12.0,
            units = GlucoseUnit.MMOL
        )
        val state = runBlocking { builder(Fixture(case)).build() }
        assertThat(state.bgMgdl).isEqualTo(148.0)
        assertThat(state.bgTimestamp).isEqualTo(now - 3 * min)
        assertThat(state.units).isEqualTo(GlucoseUnit.MMOL)
        assertThat(state.deltaMgdl).isEqualTo(4.0)
        assertThat(state.iob).isWithin(1e-9).of(1.75)
        assertThat(state.cob).isEqualTo(12.0)
        assertThat(state.lastLoopTime).isEqualTo(case.lastApsRun)
        assertThat(state.chartPoints).isEmpty()
    }

    @Test
    fun clientHasNoLoopTime() {
        val state = runBlocking { builder(Fixture(Case(aps = false, client = true))).build() }
        assertThat(state.lastLoopTime).isNull()
    }

    private fun assertSame(case: Case) {
        val fixture = Fixture(case)
        val legacy = LegacyWidgetStateLoader(
            profileFunction = fixture.profileFunction,
            profileUtil = fixture.profileUtil,
            lastBgData = fixture.lastBgData,
            trendCalculator = fixture.trendCalculator,
            rh = fixture.rh,
            glucoseStatusProvider = fixture.glucoseStatusProvider,
            dateUtil = fixture.dateUtil,
            iobCobCalculator = fixture.iobCobCalculator,
            loop = fixture.loop,
            config = fixture.config,
            preferences = fixture.preferences,
            decimalFormatter = fixture.decimalFormatter,
            persistenceLayer = fixture.persistenceLayer,
            processedDeviceStatusData = fixture.processedDeviceStatusData,
            sensitivityOverview = fixture.sensitivityOverview
        )
        val current = WidgetStateLoader(builder(fixture), fixture.config, fixture.preferences)
        val expected = runBlocking { legacy.loadState(WIDGET_ID) }
        val actual = runBlocking { current.loadState(WIDGET_ID) }
        assertThat(actual).isEqualTo(expected)
    }

    /**
     * The graph widget reads the reading once too. Its strike-through comes from the value it shows,
     * with the same 9 minute edge as `isActualBg()` had: fresh while strictly younger.
     */
    @Test
    fun bgGraphStrikesOldReadingsWithTheSameEdgeAsBefore() {
        for ((age, struck) in listOf(2 * min to false, 9 * min - 1 to false, 9 * min to true, 25 * min to true)) {
            val fixture = Fixture(Case(lastBg = InMemoryGlucoseValue(timestamp = now - age, value = 150.0)))
            val before = !fixture.lastBgData.isActualBg()
            val render = runBlocking { bgGraphLoader(fixture).loadState(WIDGET_ID) }
            assertThat(render.strikeThrough).isEqualTo(struck)
            assertThat(render.strikeThrough).isEqualTo(before)
        }
        val none = runBlocking { bgGraphLoader(Fixture(Case(lastBg = null))).loadState(WIDGET_ID) }
        assertThat(none.strikeThrough).isTrue()
        assertThat(none.arrowResId).isNull()
    }

    @Test
    fun bgGraphStrikesOldValueEvenWhenANewReadingLandsDuringTheLoad() {
        val old = InMemoryGlucoseValue(timestamp = now - 25 * min, value = 250.0)
        val new = InMemoryGlucoseValue(timestamp = now, value = 180.0)
        val fixture = Fixture(Case(lastBg = old, isHigh = true))
        whenever(fixture.lastBgData.lastBg()).thenReturn(old, new)
        whenever(fixture.lastBgData.isActualBg()).thenReturn(true)

        val render = runBlocking { bgGraphLoader(fixture).loadState(WIDGET_ID) }
        assertThat(render.bgText).isEqualTo("bg:250.0:${GlucoseUnit.MGDL}")
        assertThat(render.strikeThrough).isTrue()
    }

    /**
     * The age is judged at the moment the reading is read, like `isActualBg()` did, not at the start
     * of the load. The history and graph reads come in between, and a reading can turn old during them.
     */
    @Test
    fun bgGraphJudgesTheAgeWhenTheReadingIsRead() {
        val reading = InMemoryGlucoseValue(timestamp = now - 8 * min, value = 150.0)
        val fixture = Fixture(Case(lastBg = reading))
        // The first clock read is at the start of the load, every later one is 2 minutes on.
        whenever(fixture.dateUtil.now()).thenReturn(now, now + 2 * min)

        val render = runBlocking { bgGraphLoader(fixture).loadState(WIDGET_ID) }
        assertThat(render.strikeThrough).isTrue()
    }

    private fun bgGraphLoader(fixture: Fixture): BgGraphStateLoader {
        val cache: OverviewDataCache = mock()
        whenever(cache.bucketedDataFlow).thenReturn(MutableStateFlow(emptyList()))
        return BgGraphStateLoader(
            cacheProvider = { cache },
            profileFunction = fixture.profileFunction,
            profileUtil = fixture.profileUtil,
            lastBgData = fixture.lastBgData,
            trendCalculator = fixture.trendCalculator,
            iobCobCalculator = fixture.iobCobCalculator,
            dateUtil = fixture.dateUtil,
            rh = fixture.rh,
            preferences = fixture.preferences,
            config = fixture.config
        )
    }

    private fun builder(fixture: Fixture) = WidgetStateBuilder(
        profileFunction = fixture.profileFunction,
        profileUtil = fixture.profileUtil,
        lastBgData = fixture.lastBgData,
        trendCalculator = fixture.trendCalculator,
        rh = fixture.rh,
        glucoseStatusProvider = fixture.glucoseStatusProvider,
        dateUtil = fixture.dateUtil,
        iobCobCalculator = fixture.iobCobCalculator,
        loop = fixture.loop,
        config = fixture.config,
        decimalFormatter = fixture.decimalFormatter,
        persistenceLayer = fixture.persistenceLayer,
        processedDeviceStatusData = fixture.processedDeviceStatusData,
        sensitivityOverview = fixture.sensitivityOverview
    )

    private fun tt(reason: TT.Reason, duration: Long) =
        TT(timestamp = now - 10 * min, reason = reason, lowTarget = 80.0, highTarget = 90.0, duration = duration)

    /** Writes every resolved text with its key and arguments, so a wrong key or a lost argument shows. */
    private class EchoTextResolver : TextResolver {

        override fun gs(ref: TextRef): String = "<$ref>"
        override fun gs(ref: TextRef, vararg args: Any?): String = "<$ref|${args.joinToString("|")}>"
        override fun gsNotLocalised(ref: TextRef): String = "<en:$ref>"
    }

    /** The mocks for one case. The old and the new loader read the very same objects. */
    private inner class Fixture(case: Case) {

        val rh: TextResolver = EchoTextResolver()
        val profileFunction: ProfileFunction = mock()
        val profileUtil: ProfileUtil = mock()
        val lastBgData: LastBgData = mock()
        val trendCalculator: TrendCalculator = mock()
        val glucoseStatusProvider: GlucoseStatusProvider = mock()
        val dateUtil: DateUtil = mock()
        val iobCobCalculator: IobCobCalculator = mock()
        val loop: Loop = mock()
        val config: Config = mock()
        val preferences: Preferences = mock()
        val decimalFormatter: DecimalFormatter = mock()
        val persistenceLayer: PersistenceLayer = mock()
        val processedDeviceStatusData: ProcessedDeviceStatusData = mock()
        val sensitivityOverview: SensitivityOverview = mock()
        private val ads: AutosensDataStore = mock()
        private val profile: EffectiveProfile =
            case.profileSwitchPercentage?.let { percentage ->
                val eps: EPS = mock()
                whenever(eps.originalPercentage).thenReturn(percentage)
                whenever(eps.originalTimeshift).thenReturn(0L)
                whenever(eps.originalDuration).thenReturn(0L)
                mock<ProfileSealed.EPS>().also { whenever(it.value).thenReturn(eps) }
            } ?: mock()

        init {
            runBlocking {
                whenever(dateUtil.now()).thenReturn(now)
                whenever(dateUtil.minAgo(any(), anyOrNull())).thenAnswer { "ago:${it.getArgument<Long?>(1)}" }
                whenever(dateUtil.untilString(any(), any(), any())).thenAnswer { "until:${it.getArgument<Long>(0)}:${it.getArgument<Boolean>(2)}" }

                whenever(profileUtil.units).thenReturn(case.units)
                whenever(profileUtil.fromMgdlToStringInUnits(anyOrNull(), any())).thenAnswer { "bg:${it.getArgument<Double?>(0)}:${it.getArgument<GlucoseUnit>(1)}" }
                whenever(profileUtil.fromMgdlToSignedStringInUnits(any(), any())).thenAnswer { "delta:${it.getArgument<Double>(0)}:${it.getArgument<GlucoseUnit>(1)}" }
                whenever(profileUtil.toTargetRangeString(any(), any(), any(), any())).thenAnswer {
                    "range:${it.getArgument<Double>(0)}-${it.getArgument<Double>(1)}:${it.getArgument<GlucoseUnit>(2)}>${it.getArgument<GlucoseUnit>(3)}"
                }
                whenever(decimalFormatter.to0Decimal(any())).thenAnswer { "d0:${it.getArgument<Double>(0)}" }

                whenever(lastBgData.lastBg()).thenReturn(case.lastBg)
                whenever(lastBgData.isLow()).thenReturn(case.isLow)
                whenever(lastBgData.isHigh()).thenReturn(case.isHigh)
                // Like LastBgDataImpl: fresh while younger than 9 min.
                whenever(lastBgData.isActualBg()).thenReturn(case.lastBg?.let { it.timestamp > now - 9 * min } == true)

                whenever(iobCobCalculator.ads).thenReturn(ads)
                whenever(trendCalculator.getTrendArrow(ads)).thenReturn(case.trend)

                val glucoseStatus: GlucoseStatus? = case.delta?.let { delta -> mock<GlucoseStatus>().also { whenever(it.delta).thenReturn(delta) } }
                whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus)

                whenever(iobCobCalculator.calculateIobFromBolus()).thenAnswer { IobTotal(time = now, iob = case.bolusIob) }
                whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenAnswer { IobTotal(time = now, basaliob = case.basalIob) }
                whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(now, case.cob, case.futureCarbs))

                whenever(config.APS).thenReturn(case.aps)
                whenever(config.AAPSCLIENT).thenReturn(case.client)

                if (case.hasLastRun) {
                    val apsResult: APSResult = mock()
                    whenever(apsResult.carbsReq).thenReturn(case.carbsReq)
                    whenever(apsResult.targetBG).thenReturn(case.loopTargetBg)
                    val lastRun = Loop.LastRun().apply {
                        constraintsProcessed = apsResult
                        lastAPSRun = case.lastApsRun
                    }
                    whenever(loop.lastRun).thenReturn(lastRun)
                } else {
                    whenever(loop.lastRun).thenReturn(null)
                }
                whenever(loop.runningMode()).thenReturn(case.mode)

                val clientResult: APSResult? = case.clientTargetBg?.let { target -> mock<APSResult>().also { whenever(it.targetBG).thenReturn(target) } }
                whenever(processedDeviceStatusData.getAPSResult()).thenReturn(clientResult)

                whenever(persistenceLayer.getNewestCarbs()).thenReturn(case.newestCarbsTime?.let { CA(timestamp = it, amount = 10.0, duration = 0) })
                whenever(persistenceLayer.getTemporaryTargetActiveAt(now)).thenReturn(case.tempTarget)

                whenever(profile.getTargetLowMgdl()).thenReturn(case.profileLow)
                whenever(profile.getTargetHighMgdl()).thenReturn(case.profileHigh)
                whenever(profile.getRoundedTargetMgdl()).thenReturn((case.profileLow + case.profileHigh) / 2.0)
                whenever(profileFunction.getUnits()).thenReturn(case.units)
                whenever(profileFunction.getProfile()).thenReturn(if (case.hasProfile) profile else null)
                whenever(profileFunction.getProfileNameWithRemainingTime()).thenReturn("Profile (1h)")

                val basalData = BasalData().apply {
                    basal = case.basal
                    tempBasalAbsolute = case.tempBasalAbsolute
                    isTempBasalRunning = case.tempBasalRunning
                }
                whenever(iobCobCalculator.getBasalData(eq(profile), any())).thenReturn(basalData)

                whenever(sensitivityOverview.build()).thenReturn(case.sensitivity)

                whenever(preferences.get(IntComposedKey.WidgetOpacity, WIDGET_ID)).thenReturn(case.opacity)
                whenever(preferences.get(BooleanComposedKey.WidgetUseBlack, WIDGET_ID)).thenReturn(case.useBlack)
            }
        }
    }

    private companion object {

        const val WIDGET_ID = 7
    }
}
