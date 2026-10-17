package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** The iOS recorder: the switch, the tick limit, the file name and the record shape. */
@OptIn(ExperimentalForeignApi::class)
class IosAimiParityRecorderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun newStorage(): AimiStorage {
        val root = NSTemporaryDirectory().trimEnd('/') + "/parity-test-" + NSUUID().UUIDString
        return DirectoryAimiStorage(root)
    }

    private fun capture(recorder: IosAimiParityRecorder, storage: AimiStorage, time: Long = 1_000L) =
        recorder.maybeCapture(
            storage = storage,
            glucoseStatusAimi = GlucoseStatusAIMI(glucose = 140.0, delta = 2.0),
            currentTemp = CurrentTemp(duration = 30, rate = 0.5, minutesrunning = null),
            iobArray = arrayOf(IobTotal(time = time, iob = 1.2, activity = 0.01)),
            profile = AimiTestSnapshots.testProfile(),
            autosensResult = AutosensResult(),
            mealData = MealData(),
            microBolusAllowed = true,
            currentTime = time,
            flatBGsDetected = false,
            dynIsfMode = false,
            pkpdIobDataArray = null,
            effectiveDiaHours = 5.0,
            effectivePeakMinutes = 60.0,
            rt = RT(runningDynamicIsf = false),
        )

    private fun fileOf(storage: AimiStorage, tick: Int) =
        storage.file(AimiParityCaptureBuilder.DIRECTORY, AimiParityCaptureBuilder.fileName(tick))

    @Test
    fun `file names are six digits like on Android`() {
        assertEquals("tick-000000.json", AimiParityCaptureBuilder.fileName(0))
        assertEquals("tick-000042.json", AimiParityCaptureBuilder.fileName(42))
    }

    @Test
    fun `a recorder that is off writes nothing`() = runBlocking {
        val storage = newStorage()
        val recorder = IosAimiParityRecorder(scope)
        capture(recorder, storage)
        recorder.flush()
        assertEquals(0, recorder.capturedSoFar())
        assertFalse(storage.exists(fileOf(storage, 0)))
    }

    @Test
    fun `a recorder that is on writes one file per tick in order`() = runBlocking {
        val storage = newStorage()
        val recorder = IosAimiParityRecorder(scope)
        recorder.enabled = true
        capture(recorder, storage, time = 1_000L)
        capture(recorder, storage, time = 2_000L)
        recorder.flush()
        assertEquals(2, recorder.capturedSoFar())
        assertTrue(storage.exists(fileOf(storage, 0)))
        assertTrue(storage.exists(fileOf(storage, 1)))
        assertFalse(storage.exists(fileOf(storage, 2)))
        assertTrue(storage.readText(fileOf(storage, 0))!!.contains("1000"))
        assertTrue(storage.readText(fileOf(storage, 1))!!.contains("2000"))
    }

    @Test
    fun `the recorder stops at the tick limit and reset starts a new batch`() = runBlocking {
        val storage = newStorage()
        val recorder = IosAimiParityRecorder(scope, maxTicks = 2)
        recorder.enabled = true
        repeat(5) { capture(recorder, storage) }
        recorder.flush()
        assertEquals(2, recorder.capturedSoFar())
        assertFalse(storage.exists(fileOf(storage, 2)))
        recorder.reset()
        capture(recorder, storage)
        recorder.flush()
        assertEquals(1, recorder.capturedSoFar())
    }

    @Test
    fun `the file is the same text the shared builder gives`() = runBlocking {
        val storage = newStorage()
        val recorder = IosAimiParityRecorder(scope)
        recorder.enabled = true
        capture(recorder, storage)
        recorder.flush()
        val expected = AimiParityCaptureBuilder.buildCapture(
            generation = 0L,
            glucoseStatusAimi = GlucoseStatusAIMI(glucose = 140.0, delta = 2.0),
            currentTemp = CurrentTemp(duration = 30, rate = 0.5, minutesrunning = null),
            iobArray = arrayOf(IobTotal(time = 1_000L, iob = 1.2, activity = 0.01)),
            profile = AimiTestSnapshots.testProfile(),
            autosensResult = AutosensResult(),
            mealData = MealData(),
            microBolusAllowed = true,
            currentTime = 1_000L,
            flatBGsDetected = false,
            dynIsfMode = false,
            pkpdIobDataArray = null,
            effectiveDiaHours = 5.0,
            effectivePeakMinutes = 60.0,
            rt = RT(runningDynamicIsf = false),
            tickIndex = 0,
        ).encodeToString()
        assertEquals(expected, storage.readText(fileOf(storage, 0)))
        assertTrue(expected.contains("glucoseMgdl"))
    }

    @Test
    fun `the hook follows the preference in both directions`() = runBlocking {
        val storage = newStorage()
        val preferences = JsonBackedPreferences(storage)
        val hook = IosAimiTickCaptureHook(preferences)
        fun tick() = hook.maybeCapture(
            storage = storage,
            glucoseStatusAimi = GlucoseStatusAIMI(glucose = 140.0),
            currentTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = null),
            iobArray = arrayOf(IobTotal(time = 1L)),
            profile = AimiTestSnapshots.testProfile(),
            autosensResult = AutosensResult(),
            mealData = MealData(),
            microBolusAllowed = true,
            currentTime = 1L,
            flatBGsDetected = false,
            dynIsfMode = false,
            pkpdIobDataArray = null,
            effectiveDiaHours = null,
            effectivePeakMinutes = null,
            rt = RT(runningDynamicIsf = false),
        )
        // Default is off.
        tick()
        assertEquals(false, preferences.get(BooleanKey.AimiParityCaptureEnable))
        assertFalse(storage.exists(fileOf(storage, 0)))
        // Switch on: the next tick records.
        preferences.put(BooleanKey.AimiParityCaptureEnable, true)
        tick()
        waitForFile(storage, 0)
        assertNotNull(storage.readText(fileOf(storage, 0)))
        // Switch off: the next tick does not record.
        preferences.put(BooleanKey.AimiParityCaptureEnable, false)
        tick()
        delay(300)
        assertFalse(storage.exists(fileOf(storage, 1)))
    }

    private suspend fun waitForFile(storage: AimiStorage, tick: Int) {
        repeat(50) {
            if (storage.exists(fileOf(storage, tick))) return
            delay(100)
        }
    }
}
