package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.sync.SyncStringsValues
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminIntNonKey
import app.aaps.plugins.sync.garmin.keys.GarminLongNonKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.shared.tests.generatedTextResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.atMost
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.Condition
import kotlin.ranges.LongProgression.Companion.fromClosedRange

class GarminPluginTest : TestBaseWithProfile() {

    private lateinit var gp: GarminPlugin

    @Mock private lateinit var loopHub: LoopHub
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))

    @BeforeEach
    fun setup() {
        gp = GarminPlugin(aapsLogger, rh, preferences, context, loopHub, persistenceLayer, config, notificationManager)
        gp.clock = clock
        whenever(loopHub.currentProfileName).thenReturn("Default")
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28890)
        whenever(preferences.get(any<IntNonKey>())).thenAnswer { i -> 0 }
        whenever(preferences.get(any<BooleanNonKey>())).thenAnswer { i -> false }
        whenever(preferences.get(any<StringNonKey>())).thenAnswer { i -> "" }
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verify(loopHub, atMost(2)).currentProfileName
        verify(loopHub, atMost(3)).insulinOnboard
        verify(loopHub, atMost(3)).insulinBasalOnboard
        verify(loopHub, atMost(3)).temporaryBasal
        verify(loopHub, atMost(3)).carbsOnboard
        // onSgv reads the marks again for the time in range, and the profile for the TBR rate.
        verify(loopHub, atMost(6)).lowGlucoseMark
        verify(loopHub, atMost(6)).highGlucoseMark
        verify(loopHub, atMost(6)).currentProfile
        verifyNoMoreInteractions(loopHub)
    }

    private val getGlucoseValuesFrom = clock.instant()
        .minus(2, ChronoUnit.HOURS)
        .minus(9, ChronoUnit.MINUTES)

    private fun createUri(params: Map<String, Any>): URI {
        return URI("http://foo?" + params.entries.joinToString(separator = "&") { (k, v) ->
            "$k=$v"
        })
    }

    private fun createHeartRate(@Suppress("SameParameterValue") heartRate: Int) = mapOf<String, Any>(
        "hr" to heartRate,
        "hrStart" to 1001L,
        "hrEnd" to 2001L,
        "device" to "Test_Device"
    )

    private fun createSteps() = mapOf<String, Any>(
        "stepsStart" to 2001L,
        "stepsEnd" to 2301L,
        "steps5" to 12,
        "steps10" to 18,
        "steps15" to 24,
        "steps30" to 36,
        "steps60" to 48,
        "steps180" to 60,
        "device" to "Test_Device",
    )

    private fun verifyStepsStored(steps: Map<String, Any>) {
        verify(loopHub).storeStepsCount(
            Instant.ofEpochSecond(steps["stepsStart"] as Long),
            Instant.ofEpochSecond(steps["stepsEnd"] as Long),
            steps["steps5"] as Int,
            steps["steps10"] as Int,
            steps["steps15"] as Int,
            steps["steps30"] as Int,
            steps["steps60"] as Int,
            steps["steps180"] as Int,
            steps["device"] as String,
        )
    }

    @Test
    fun testReceiveStepsMap() {
        val steps = createSteps()
        gp.receiveSteps(steps, false)
        verifyStepsStored(steps)
    }

    @Test
    fun testReceiveStepsMap_NumbersAsText() {
        val steps = createSteps().mapValues { (_, v) -> v.toString() }
        gp.receiveSteps(steps, false)
        verifyStepsStored(createSteps())
    }

    @Test
    fun testReceiveStepsMap_TestIsNotStored() {
        gp.receiveSteps(createSteps(), true)
    }

    @Test
    fun testReceiveStepsMap_AllZeroIsNotStored() {
        gp.receiveSteps(createSteps().mapValues { (k, v) -> if (v is Int) 0 else v }, false)
    }

    @Test
    fun testReceiveStepsUri() {
        val steps = createSteps()
        gp.receiveSteps(createUri(steps))
        verifyStepsStored(steps)
    }

    @Test
    fun testReceiveHeartRateUri_AlsoReadsSteps() {
        val params = createHeartRate(99) + createSteps()
        gp.receiveHeartRate(createUri(params))
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(params["hrStart"] as Long),
            Instant.ofEpochSecond(params["hrEnd"] as Long),
            99,
            params["device"] as String
        )
        verifyStepsStored(params)
    }

    @Test
    fun testReceiveStepsUri_FirstTotalIsOnlyKept() {
        whenever(preferences.get(GarminIntNonKey.LastTotalSteps)).thenReturn(-1)
        gp.receiveSteps(URI("http://foo?steps=165&device=Watch"))
        verify(preferences).put(GarminIntNonKey.LastTotalSteps, 165)
        verify(preferences).put(GarminLongNonKey.LastTotalStepsTime, clock.millis())
    }

    @Test
    fun testReceiveStepsUri_TotalStoresTheDelta() {
        whenever(preferences.get(GarminIntNonKey.LastTotalSteps)).thenReturn(1000)
        gp.receiveSteps(URI("http://foo?steps=1250&device=Watch"))
        verify(preferences).put(GarminIntNonKey.LastTotalSteps, 1250)
        val now = clock.instant().epochSecond
        verify(loopHub).storeStepsCount(Instant.ofEpochSecond(now - 300), Instant.ofEpochSecond(now), 250, 0, 0, 0, 0, 0, "Watch")
    }

    @Test
    fun testReceiveStepsUri_LowerTotalIsANewDay() {
        whenever(preferences.get(GarminIntNonKey.LastTotalSteps)).thenReturn(17506)
        gp.receiveSteps(URI("http://foo?steps=165&device=Watch"))
        verify(preferences).put(GarminIntNonKey.LastTotalSteps, 165)
        val now = clock.instant().epochSecond
        verify(loopHub).storeStepsCount(Instant.ofEpochSecond(now - 300), Instant.ofEpochSecond(now), 165, 0, 0, 0, 0, 0, "Watch")
    }

    @Test
    fun testOnPostTempTarget() {
        gp.onPostTempTarget(createUri(mapOf("target" to 7.5, "duration" to 45)))
        verify(loopHub).postTempTarget(7.5, 45)
    }

    @Test
    fun testOnPostTempTarget_NoValuesCancels() {
        gp.onPostTempTarget(createUri(mapOf()))
        verify(loopHub).postTempTarget(0.0, 0)
    }

    private fun createGlucoseValue(timestamp: Instant, value: Double = 93.0) = GV(
        id = 10 * timestamp.toEpochMilli(),
        timestamp = timestamp.toEpochMilli(), raw = 90.0, value = value,
        trendArrow = TrendArrow.FLAT, noise = 4.5,
        sourceSensor = SourceSensor.RANDOM
    )

    @Test
    fun testReceiveHeartRateMap() {
        val hr = createHeartRate(80)
        gp.receiveHeartRate(hr, false)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            80,
            hr["device"] as String
        )
    }

    @Test
    fun testReceiveHeartRateUri() {
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        gp.receiveHeartRate(uri)
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String
        )
    }

    @Test
    fun testReceiveHeartRate_UriTestIsTrue() {
        val params = createHeartRate(99).toMutableMap()
        params["test"] = true
        val uri = createUri(params)
        gp.receiveHeartRate(uri)
    }

    @Test
    fun testGetGlucoseValues_NoLast() {
        val from = getGlucoseValuesFrom
        val prev = createGlucoseValue(clock.instant().minusSeconds(310))
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun testGetGlucoseValues_NoNewLast() {
        val from = getGlucoseValuesFrom
        val lastTimestamp = clock.instant()
        val prev = createGlucoseValue(clock.instant())
        gp.newValue = mock<Condition>()
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(listOf(prev))
        gp.onNewBloodGlucose(listOf(createGlucoseValue(lastTimestamp)))
        assertArrayEquals(arrayOf(prev), gp.getGlucoseValues().toTypedArray())

        verify(gp.newValue).signalAll()
        verify(loopHub).getGlucoseValues(from, true)
    }

    @Test
    fun setupHttpServer_enabled() = runBlocking {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        whenever(preferences.get(GarminBooleanKey.LocalHttpServer)).thenReturn(true)
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28892)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28892/get")
        val resp = reqUri.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp.responseCode)

        // Change port
        whenever(preferences.get(GarminIntKey.LocalHttpPort)).thenReturn(28893)
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri2 = URI("http://127.0.0.1:28893/get")
        val resp2 = reqUri2.toURL().openConnection() as HttpURLConnection
        assertEquals(200, resp2.responseCode)

        whenever(preferences.get(GarminBooleanKey.LocalHttpServer)).thenReturn(false)
        gp.setupHttpServer(Duration.ofSeconds(10))
        assertThrows(ConnectException::class.java) {
            (reqUri2.toURL().openConnection() as HttpURLConnection).responseCode
        }
        gp.onStop()

        verify(loopHub, times(2)).getGlucoseValues(anyOrNull(), eq(true))
        verify(loopHub, times(2)).insulinOnboard
        verify(loopHub, times(2)).temporaryBasal
        verify(loopHub, times(2)).isConnected
        verify(loopHub, times(2)).glucoseUnit
        verify(loopHub, times(2)).lowGlucoseMark
        verify(loopHub, times(2)).highGlucoseMark
    }

    @Test
    fun setupHttpServer_disabled() {
        gp.setupHttpServer(Duration.ofSeconds(10))
        val reqUri = URI("http://127.0.0.1:28890/get")
        assertThrows(ConnectException::class.java) {
            (reqUri.toURL().openConnection() as HttpURLConnection).responseCode
        }
    }

    @Test
    fun requestHandler_NoKey() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )
    }

    @Test
    fun requestHandler_KeyProvided() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )
    }

    @Test
    fun requestHandler_KeyRequiredAndProvided() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val uri = createUri(mapOf("key" to "foo"))
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_OK to "OK",
            handler(mock<SocketAddress>(), uri, null)
        )

    }

    @Test
    fun requestHandler_KeyRequired() {
        gp.garminMessengerField = mock<GarminMessenger>()

        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val uri = createUri(emptyMap())
        val handler = gp.requestHandler { u: URI -> assertEquals(uri, u); "OK" }
        assertEquals(
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}",
            handler(mock<SocketAddress>(), uri, null)
        )

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger).sendMessage(captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun onConnectDevice() {
        gp.garminMessengerField = mock<GarminMessenger>()
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("foo")
        val device = GarminDevice(mock(), 1, "Edge")
        gp.onConnectDevice(device)

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(gp.garminMessenger).sendMessage(eq(device), captor.capture() ?: "")
        @Suppress("UNCHECKED_CAST")
        val r = captor.value as Map<String, Any>
        assertEquals("foo", r["key"])
        assertEquals("glucose", r["command"])
        assertEquals("D", r["profile"])
        assertEquals("", r["encodedGlucose"])
        assertEquals(0.0, r["remainingInsulin"])
        assertEquals("mmoll", r["glucoseUnit"])
        assertEquals(0.0, r["temporaryBasalRate"])
        assertEquals(false, r["connected"])
        assertEquals(clock.instant().epochSecond, r["timestamp"])
        verify(loopHub).getGlucoseValues(getGlucoseValuesFrom, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
    }

    @Test
    fun testOnGetBloodGlucose() {
        whenever(loopHub.isConnected).thenReturn(true)
        whenever(loopHub.insulinOnboard).thenReturn(3.14)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.71)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.lowGlucoseMark).thenReturn(70.0)
        whenever(loopHub.highGlucoseMark).thenReturn(130.0)
        val from = getGlucoseValuesFrom
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(Instant.ofEpochSecond(1_000)))
        )
        val hr = createHeartRate(99)
        val uri = createUri(hr)
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            """{"encodedGlucose":"0A+6AQ==",""" +
                """"remainingInsulin":3.14,"remainingBasalInsulin":2.71,""" +
                """"lowGlucoseMark":70,"highGlucoseMark":130,""" +
                """"glucoseUnit":"mmoll","temporaryBasalRate":0.8,""" +
                """"profile":"D","connected":true}""",
            result.toString()
        )
        verify(loopHub).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).lowGlucoseMark
        verify(loopHub).highGlucoseMark
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(hr["hrStart"] as Long),
            Instant.ofEpochSecond(hr["hrEnd"] as Long),
            99,
            hr["device"] as String
        )
    }

    @Test
    fun testOnGetBloodGlucose_Wait() {
        whenever(loopHub.isConnected).thenReturn(true)
        whenever(loopHub.insulinOnboard).thenReturn(3.14)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        val from = getGlucoseValuesFrom
        whenever(loopHub.getGlucoseValues(from, true)).thenReturn(
            listOf(createGlucoseValue(clock.instant().minusSeconds(330)))
        )
        val params = createHeartRate(99).toMutableMap()
        params["wait"] = 10
        val uri = createUri(params)
        gp.newValue = mock<Condition>()
        val result = gp.onGetBloodGlucose(uri)
        assertEquals(
            """{"encodedGlucose":"/wS6AQ==",""" +
                """"remainingInsulin":3.14,"remainingBasalInsulin":0.0,""" +
                """"glucoseUnit":"mmoll","temporaryBasalRate":0.8,""" +
                """"profile":"D","connected":true}""",
            result.toString()
        )
        verify(gp.newValue).awaitNanos(anyLong())
        verify(loopHub, times(2)).getGlucoseValues(from, true)
        verify(loopHub).insulinOnboard
        verify(loopHub).temporaryBasal
        verify(loopHub).isConnected
        verify(loopHub).glucoseUnit
        verify(loopHub).lowGlucoseMark
        verify(loopHub).highGlucoseMark
        verify(loopHub).storeHeartRate(
            Instant.ofEpochSecond(params["hrStart"] as Long),
            Instant.ofEpochSecond(params["hrEnd"] as Long),
            99,
            params["device"] as String
        )
    }

    @Test
    fun testOnPostCarbs() {
        val uri = createUri(mapOf("carbs" to "12"))
        assertEquals("", gp.onPostCarbs(uri))
        verify(loopHub).postCarbs(12)
    }

    @Test
    fun testOnPostMode_Lunch() {
        val uri = createUri(mapOf("mode" to "lunch", "duration" to "60"))
        assertEquals("""{"ok":true,"mode":"lunch","duration":60}""", gp.onPostMode(uri))
        verify(loopHub).postTherapyMode("lunch", 60)
        verify(loopHub, times(0)).postTempTarget(any(), any())
    }

    @Test
    fun testOnPostMode_FclDefaultDuration() {
        val uri = createUri(mapOf("mode" to "FCL"))
        assertEquals("""{"ok":true,"mode":"fcl","duration":30}""", gp.onPostMode(uri))
        verify(loopHub).postTherapyMode("fcl", 30)
        verify(loopHub).postTempTarget(80.0, 30)
    }

    @Test
    fun testOnPostMode_Sport() {
        val uri = createUri(mapOf("mode" to "sport", "duration" to "120"))
        assertEquals("""{"ok":true,"mode":"sport","duration":120}""", gp.onPostMode(uri))
        verify(loopHub).postTherapyMode("sport", 120)
        verify(loopHub, times(0)).postTempTarget(any(), any())
    }

    /** "coffee": the loop meets the rise with a bounded basal, not with SMBs (CoffeeRiseMode). No temp target. */
    @Test
    fun testOnPostMode_CoffeeDefaultDuration() {
        val uri = createUri(mapOf("mode" to "Coffee"))
        assertEquals("""{"ok":true,"mode":"coffee","duration":90}""", gp.onPostMode(uri))
        verify(loopHub).postTherapyMode("coffee", 90)
        verify(loopHub, times(0)).postTempTarget(any(), any())
    }

    @Test
    fun testOnPostMode_InvalidRejected() {
        val uri = createUri(mapOf("mode" to "hack"))
        assertEquals("""{"ok":false,"error":"invalid_mode"}""", gp.onPostMode(uri))
        verify(loopHub, times(0)).postTherapyMode(any(), any())
    }

    @Test
    fun testOnPostMode_Stop() {
        val uri = createUri(mapOf("mode" to "stop"))
        assertEquals("""{"ok":true,"mode":"stop","duration":1}""", gp.onPostMode(uri))
        verify(loopHub).postTherapyMode("stop", 1)
    }

    @Test
    fun testOnConnectPump_Disconnect() {
        val uri = createUri(mapOf("disconnectMinutes" to "20"))
        whenever(loopHub.isConnected).thenReturn(false)
        assertEquals("{\"connected\":false}", gp.onConnectPump(uri))
        verify(loopHub).disconnectPump(20)
        verify(loopHub).isConnected
    }

    @Test
    fun testOnConnectPump_Connect() {
        val uri = createUri(mapOf("disconnectMinutes" to "0"))
        whenever(loopHub.isConnected).thenReturn(true)
        assertEquals("{\"connected\":true}", gp.onConnectPump(uri))
        verify(loopHub).connectPump()
        verify(loopHub).isConnected
    }

    // A client only shows values. Carbs or a pump disconnect stored there would never reach the master, and the watch
    // cannot show why, so the phone says it.
    private fun clientPlugin(): GarminPlugin = textPlugin(client = true)

    private fun textPlugin(client: Boolean): GarminPlugin {
        whenever(config.AAPSCLIENT).thenReturn(client)
        val text = generatedTextResolver("sync" to SyncStringsValues::textOf)
        return GarminPlugin(aapsLogger, text, preferences, context, loopHub, persistenceLayer, config, notificationManager)
    }

    private fun postedNotice(id: NotificationId = NotificationId.GARMIN_NOT_ON_CLIENT): String {
        val captor = argumentCaptor<String>()
        verify(notificationManager).post(eq(id), captor.capture(), any(), any<Int>(), anyOrNull(), any(), anyOrNull())
        return captor.firstValue
    }

    // A watch bolus: refused on a client and without a request key (any app on the phone can reach the
    // local server), and always limited by the bolus constraints in LoopHub.postBolus.
    @Test
    fun testOnPostBolus_OnClientIsRefused() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        assertEquals("{\"ok\":false}", textPlugin(client = true).onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        assertEquals(
            "The Garmin watch asked for a bolus of 1.50 U. This phone is a client, so no bolus was given. Give it in AAPS.",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
    }

    @Test
    fun testOnPostBolus_WithoutKeyIsRefused() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("")
        assertEquals("{\"ok\":false}", textPlugin(client = false).onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        assertEquals(
            "The Garmin watch asked for a bolus of 1.50 U. No bolus was given: a bolus from the watch needs a request key in the Garmin settings.",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
    }

    @Test
    fun testOnPostBolus_WithKeyIsGiven() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        whenever(loopHub.postBolus(eq(1.5), any())).thenReturn(1.5)
        assertEquals("{\"ok\":true,\"bolus\":1.5}", textPlugin(client = false).onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        verify(loopHub).postBolus(eq(1.5), any())
        verifyNoInteractions(notificationManager)
    }

    @Test
    fun testOnPostBolus_LimitedIsTold() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        whenever(loopHub.postBolus(eq(12.0), any())).thenReturn(10.0)
        assertEquals("{\"ok\":true,\"bolus\":10.0}", textPlugin(client = false).onPostBolus(createUri(mapOf("bolus" to "12"))))
        verify(loopHub).postBolus(eq(12.0), any())
        assertEquals(
            "The Garmin watch asked for a bolus of 12.00 U. It was limited to the maximum bolus: 10.00 U.",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
    }

    @Test
    fun testOnPostBolus_RefusedByLimitsIsTold() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        whenever(loopHub.postBolus(eq(2.0), any())).thenReturn(0.0)
        assertEquals("{\"ok\":false}", textPlugin(client = false).onPostBolus(createUri(mapOf("bolus" to "2"))))
        verify(loopHub).postBolus(eq(2.0), any())
        assertEquals(
            "The Garmin watch asked for a bolus of 2.00 U. No bolus was given: the bolus limits do not allow it now.",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
    }

    @Test
    fun testOnPostBolus_RepeatWithinAMinuteIsRefused() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        whenever(loopHub.postBolus(eq(1.5), any())).thenReturn(1.5)
        val plugin = textPlugin(client = false)
        plugin.clock = clock
        assertEquals("{\"ok\":true,\"bolus\":1.5}", plugin.onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        assertEquals("{\"ok\":false}", plugin.onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        verify(loopHub).postBolus(eq(1.5), any())
        assertEquals(
            "The Garmin watch asked for a bolus of 1.50 U less than one minute after the last one. No bolus was given, so a repeated request cannot give it twice. Ask again in a minute if you really want it.",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
        // A minute later the next one is accepted again.
        plugin.clock = Clock.offset(clock, Duration.ofSeconds(60))
        assertEquals("{\"ok\":true,\"bolus\":1.5}", plugin.onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        verify(loopHub, times(2)).postBolus(eq(1.5), any())
    }

    @Test
    fun testOnPostBolus_NotDeliveredLaterIsTold() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        whenever(loopHub.postBolus(eq(1.5), any())).thenAnswer {
            it.getArgument<(String) -> Unit>(1)("Loop is suspended")
            1.5
        }
        assertEquals("{\"ok\":true,\"bolus\":1.5}", textPlugin(client = false).onPostBolus(createUri(mapOf("bolus" to "1.5"))))
        verify(loopHub).postBolus(eq(1.5), any())
        assertEquals(
            "The Garmin watch asked for a bolus of 1.50 U. It was not given: Loop is suspended",
            postedNotice(NotificationId.GARMIN_BOLUS)
        )
    }

    @Test
    fun testOnPostBolus_NoOrBadValueDoesNothing() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("key")
        val plugin = textPlugin(client = false)
        assertEquals("{\"ok\":false}", plugin.onPostBolus(createUri(mapOf())))
        assertEquals("{\"ok\":false}", plugin.onPostBolus(createUri(mapOf("bolus" to "-1"))))
        assertEquals("{\"ok\":false}", plugin.onPostBolus(createUri(mapOf("bolus" to "abc"))))
        verifyNoInteractions(notificationManager)
    }

    @Test
    fun testOnPostCarbs_OnClient() {
        val uri = createUri(mapOf("carbs" to "12"))
        assertEquals("", clientPlugin().onPostCarbs(uri))
        assertEquals("The Garmin watch sent 12 g of carbs. This phone is a client, so they were not entered. Enter them in AAPS.", postedNotice())
    }

    @Test
    fun testOnConnectPump_DisconnectOnClient() {
        val uri = createUri(mapOf("disconnectMinutes" to "20"))
        whenever(loopHub.isConnected).thenReturn(true)
        assertEquals("{\"connected\":true}", clientPlugin().onConnectPump(uri))
        verify(loopHub).isConnected
        assertEquals(
            "The Garmin watch asked to connect or disconnect the pump. This phone is a client, so nothing was changed. Do it in AAPS.",
            postedNotice()
        )
    }

    @Test
    fun onSgv_NoGlucose() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(emptyList())
        assertEquals("[]", gp.onSgv(createUri(mapOf())))
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
    }

    @Test
    fun onSgv_NoDelta() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.temporaryBasalHistory(any())).thenReturn(emptyList())
        whenever(loopHub.smbHistory(any())).thenReturn(emptyList())
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(
            listOf(
                createGlucoseValue(
                    clock.instant().minusSeconds(100L), 99.3
                )
            )
        )
        assertEquals(
            """[{"_id":"-900000","device":"RANDOM","deviceString":"1969-12-31T23:58:30Z","sysTime":"1969-12-31T23:58:30Z","unfiltered":90.0,"date":-90000,"sgv":99,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":"0.0/80","cob":10.7,"tir":100,"basalHistory":[],"smbHistory":[]}]""",
            gp.onSgv(createUri(mapOf()))
        )
        verify(loopHub).getGlucoseValues(clock.instant().minusSeconds(25L * 300L), false)
        verify(loopHub).glucoseUnit
        verify(loopHub, atLeastOnce()).currentTargetMgdl
        verify(loopHub, atLeastOnce()).activeTherapyMode
        verify(loopHub, atLeastOnce()).temporaryBasalRemainingMinutes
        verify(loopHub, atLeastOnce()).temporaryBasalHistory(any())
        verify(loopHub, atLeastOnce()).smbHistory(any())
    }

    @Test
    fun onSgv() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MMOL)
        whenever(loopHub.insulinOnboard).thenReturn(2.7)
        whenever(loopHub.insulinBasalOnboard).thenReturn(2.5)
        whenever(loopHub.temporaryBasal).thenReturn(0.8)
        whenever(loopHub.carbsOnboard).thenReturn(10.7)
        whenever(loopHub.temporaryBasalHistory(any())).thenReturn(emptyList())
        whenever(loopHub.smbHistory(any())).thenReturn(emptyList())
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenAnswer { i ->
            val from = i.getArgument<Instant>(0)
            fromClosedRange(from.toEpochMilli(), clock.instant().toEpochMilli(), 300_000L)
                .map(Instant::ofEpochMilli)
                .mapIndexed { idx, ts -> createGlucoseValue(ts, 100.0 + (10 * idx)) }.reversed()
        }
        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":"0.0/80","cob":10.7,"tir":100,"basalHistory":[],"smbHistory":[]}]""",
            gp.onSgv(createUri(mapOf("count" to "1")))
        )
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(600L), false
        )


        assertEquals(
            """[{"_id":"100000","device":"RANDOM","deviceString":"1970-01-01T00:00:10Z","sysTime":"1970-01-01T00:00:10Z","unfiltered":90.0,"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":"0.0/80","cob":10.7,"tir":100,"basalHistory":[],"smbHistory":[]},""" +
                """{"_id":"-2900000","device":"RANDOM","deviceString":"1969-12-31T23:55:10Z","sysTime":"1969-12-31T23:55:10Z","unfiltered":90.0,"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2")))
        )
        verify(loopHub).getGlucoseValues(
            clock.instant().minusSeconds(900L), false
        )

        assertEquals(
            """[{"date":10000,"sgv":130,"delta":10,"direction":"Flat","noise":4.5,"units_hint":"mmol","iob":5.2,"tbr":"0.0/80","cob":10.7,"tir":100,"basalHistory":[],"smbHistory":[]},""" +
                """{"date":-290000,"sgv":120,"delta":10,"direction":"Flat","noise":4.5}]""",
            gp.onSgv(createUri(mapOf("count" to "2", "brief_mode" to "true")))
        )
        verify(loopHub, times(2)).getGlucoseValues(
            clock.instant().minusSeconds(900L), false
        )

        verify(loopHub, atLeastOnce()).glucoseUnit
        verify(loopHub, atLeastOnce()).currentTargetMgdl
        verify(loopHub, atLeastOnce()).activeTherapyMode
        verify(loopHub, atLeastOnce()).temporaryBasalRemainingMinutes
        verify(loopHub, atLeastOnce()).temporaryBasalHistory(any())
        verify(loopHub, atLeastOnce()).smbHistory(any())
    }

    /** Fields added for the AIMI watchfaces: target, mode, TBR minutes, basal staircase and SMB times. */
    @Test
    fun onSgv_IncludesTargetModeTbrMinsAndHistory() {
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MGDL)
        whenever(loopHub.insulinOnboard).thenReturn(1.0)
        whenever(loopHub.insulinBasalOnboard).thenReturn(0.5)
        whenever(loopHub.temporaryBasal).thenReturn(1.2)
        whenever(loopHub.carbsOnboard).thenReturn(5.0)
        whenever(loopHub.currentTargetMgdl).thenReturn(100.0)
        whenever(loopHub.activeTherapyMode).thenReturn("FCL")
        whenever(loopHub.temporaryBasalRemainingMinutes).thenReturn(27)
        whenever(loopHub.temporaryBasalHistory(any())).thenReturn(
            listOf(BasalHistoryStep(t = 1000L, p = 100), BasalHistoryStep(t = 2000L, p = 130))
        )
        whenever(loopHub.smbHistory(any())).thenReturn(
            listOf(SmbHistoryStep(t = 1500L, u = 0.35), SmbHistoryStep(t = 2500L, u = 0.20))
        )
        whenever(loopHub.getGlucoseValues(any(), eq(false))).thenReturn(
            listOf(createGlucoseValue(clock.instant(), 110.0))
        )
        val json = gp.onSgv(createUri(mapOf("count" to "1", "brief_mode" to "true"))).toString()
        assertTrue(json.contains("\"target\":100"), json)
        assertTrue(json.contains("\"mode\":\"FCL\""), json)
        assertTrue(json.contains("\"tbrMins\":27"), json)
        assertTrue(json.contains("\"basalHistory\":[{\"t\":1000,\"p\":100},{\"t\":2000,\"p\":130}]"), json)
        assertTrue(json.contains("\"smbHistory\":[{\"t\":1500,\"u\":0.35},{\"t\":2500,\"u\":0.2}]"), json)
        verify(loopHub).getGlucoseValues(any(), eq(false))
        verify(loopHub, atLeastOnce()).glucoseUnit
        verify(loopHub, atLeastOnce()).currentTargetMgdl
        verify(loopHub, atLeastOnce()).activeTherapyMode
        verify(loopHub, atLeastOnce()).temporaryBasalRemainingMinutes
        verify(loopHub, atLeastOnce()).temporaryBasalHistory(any())
        verify(loopHub, atLeastOnce()).smbHistory(any())
    }

}
