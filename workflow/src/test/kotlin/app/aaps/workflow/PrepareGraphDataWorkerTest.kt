package app.aaps.workflow

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Sensitivity
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.glucose.GlucoseCorrection
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow.ProgressData
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider
import kotlin.test.assertIs

class PrepareGraphDataWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var workflowChainData: WorkflowChainData
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseCorrection: GlucoseCorrection
    @Mock lateinit var profiler: Profiler
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var mockedRxBus: RxBus
    @Mock lateinit var sensitivity: Sensitivity
    @Mock lateinit var dataAds: AutosensDataStore
    @Mock lateinit var dataIobCob: IobCobCalculator
    @Mock lateinit var cache: OverviewDataCache
    @Mock lateinit var overviewData: OverviewData
    @Mock lateinit var signals: CalculationSignalsEmitter
    @Mock lateinit var workerParameters: WorkerParameters
    @Mock lateinit var activeCalibration: Calibration
    @Mock lateinit var activeSmoothing: Smoothing

    private val autosensDataProvider = Provider { mock<AutosensData>() }

    private fun worker() =
        PrepareGraphDataWorker(
            context, workerParameters, aapsLogger, fabricPrivacy, workflowChainData, dateUtil, mockedRxBus, persistenceLayer,
            glucoseCorrection, activePlugin, profileFunction, profileUtil, preferences, config, profiler, rh, decimalFormatter,
            processedDeviceStatusData, autosensDataProvider
        )

    private fun buildData(bgDataReload: Boolean, emitFinalProgress: Boolean) =
        PrepareGraphDataWorker.PrepareGraphData(
            iobCobCalculator = dataIobCob,
            overviewData = overviewData,
            cache = cache,
            signals = signals,
            reason = "test",
            end = 0L,
            bgDataReload = bgDataReload,
            limitDataToOldestAvailable = false,
            triggeredByNewBG = false,
            emitFinalProgress = emitFinalProgress
        )

    @BeforeEach
    fun setup() {
        whenever(workerParameters.inputData).thenReturn(workDataOf())
        whenever(dataIobCob.ads).thenReturn(dataAds)
        whenever(dataAds.clone()).thenReturn(dataAds)
        whenever(dataAds.dataLock).thenReturn(Any())
        whenever(dataAds.getBucketedDataTableCopy()).thenReturn(null)
        // Mockito returns empty (not null) for collection getters; force null so smooth/oref short-circuit.
        whenever(dataAds.bucketedData).thenReturn(null)
        whenever(cache.timeRangeFlow).thenReturn(MutableStateFlow(null))
        whenever(activePlugin.activeSensitivity).thenReturn(sensitivity)
        whenever(sensitivity.isOref1).thenReturn(false)
    }

    private suspend fun stubSuspendCalls() {
        // Profile invalid → the oref autosens loop short-circuits; that algorithm has its own tests.
        whenever(profileFunction.isProfileValid(any())).thenReturn(false)
        whenever(profileFunction.getProfile(any())).thenReturn(null)
        whenever(dataAds.getLastAutosensData(any(), any(), any())).thenReturn(null)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getApsResults(any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getTemporaryTargetActiveAt(any())).thenReturn(null)
        whenever(dataIobCob.calculateIobArrayForSMB(any(), any(), any(), any())).thenReturn(emptyArray())
        whenever(dataIobCob.iobArrayToString(any())).thenReturn("")
    }

    @Test
    fun `missing or stale chain data returns failure`() = runTest {
        whenever(workflowChainData.prepareFor(anyOrNull(), any())).thenReturn(null)

        assertIs<ListenableWorker.Result.Failure>(worker().doWorkAndLog())
    }

    @Test
    fun `full run traverses all phases and emits progress`() = runTest {
        stubSuspendCalls()
        whenever(workflowChainData.prepareFor(anyOrNull(), any())).thenReturn(buildData(bgDataReload = false, emitFinalProgress = true))

        val result = worker().doWorkAndLog()

        assertIs<ListenableWorker.Result.Success>(result)
        verify(signals).emitProgress(eq(ProgressData.DRAW_BG), any())
        verify(signals).emitProgress(eq(ProgressData.DRAW_IOB), any())
        verify(signals).emitProgress(eq(ProgressData.DRAW_FINAL), any())
    }

    @Test
    fun `bg data reload phase loads, smooths and signals bucketed data`() = runTest {
        stubSuspendCalls()
        whenever(workflowChainData.prepareFor(anyOrNull(), any())).thenReturn(buildData(bgDataReload = true, emitFinalProgress = false))

        val result = worker().doWorkAndLog()

        assertIs<ListenableWorker.Result.Success>(result)
        verify(mockedRxBus).send(any<EventBucketedDataCreated>())
        verify(dataIobCob).clearCache()
        // Terminal-only progress not emitted when emitFinalProgress = false
        verify(signals, never()).emitProgress(eq(ProgressData.DRAW_FINAL), any())
    }

    // ----- never both: a sensor that holds its own calibration is not calibrated again here -----

    /**
     * Makes the smoothing step run on one real reading, with [calibratesInSensor] telling whether
     * the active source keeps its calibration in the sensor.
     */
    private suspend fun stubSmoothingCalls(calibratesInSensor: Boolean): MutableList<InMemoryGlucoseValue> {
        val data = mutableListOf(InMemoryGlucoseValue(timestamp = 1000L, value = 100.0))
        whenever(dataAds.bucketedData).thenReturn(data)
        whenever(dataIobCob.calculateIobFromBolus()).thenReturn(IobTotal(0L))
        whenever(dataIobCob.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(0L))
        val source = mock<BgSource>()
        whenever(source.calibratesInSensor()).thenReturn(calibratesInSensor)
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(activePlugin.activeCalibration).thenReturn(activeCalibration)
        whenever(activeCalibration.calibrate(any(), any())).thenAnswer { it.getArgument(0) }
        whenever(activePlugin.activeSmoothing).thenReturn(activeSmoothing)
        whenever(activeSmoothing.smooth(any(), any())).thenAnswer { it.getArgument(0) }
        return data
    }

    @Test
    fun `no calibration in the app when the source calibrates in the sensor`() = runTest {
        stubSuspendCalls()
        stubSmoothingCalls(calibratesInSensor = true)
        whenever(workflowChainData.prepareFor(anyOrNull(), any())).thenReturn(buildData(bgDataReload = true, emitFinalProgress = false))

        assertIs<ListenableWorker.Result.Success>(worker().doWorkAndLog())

        verify(activeCalibration, never()).calibrate(any(), any())
        // Smoothing is a local filter, so it still runs.
        verify(activeSmoothing).smooth(any(), any())
    }

    @Test
    fun `calibration runs in the app for a normal source`() = runTest {
        stubSuspendCalls()
        stubSmoothingCalls(calibratesInSensor = false)
        whenever(workflowChainData.prepareFor(anyOrNull(), any())).thenReturn(buildData(bgDataReload = true, emitFinalProgress = false))

        assertIs<ListenableWorker.Result.Success>(worker().doWorkAndLog())

        verify(activeCalibration).calibrate(any(), any())
    }
}
