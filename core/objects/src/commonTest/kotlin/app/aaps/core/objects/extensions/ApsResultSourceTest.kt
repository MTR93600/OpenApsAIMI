package app.aaps.core.objects.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.configuration.InitProgress
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ApsResultSourceTest {

    private val now = 1_700_000_000_000L
    private val config = Flags()
    private val deviceStatus = DeviceStatus()

    @Test
    fun master_returnsLocalValueUnchanged() {
        config.aps = true
        val local = TargetResult(85.0)
        deviceStatus.result = TargetResult(120.0)

        assertEquals(85.0, ApsResultSource.targetMgdl(config, deviceStatus, now) { local.targetBG })
        assertSame(local, ApsResultSource.displayed(config, deviceStatus, now) { local })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun master_localNull_returnsNull() {
        config.aps = true
        deviceStatus.result = TargetResult(120.0)

        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { null })
        assertNull(ApsResultSource.displayed(config, deviceStatus, now) { null })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun client_returnsDeviceStatusTarget() {
        client()
        val master = fresh(target = 80.0, ageMs = 2 * 60_000L)

        assertEquals(80.0, ApsResultSource.targetMgdl(config, deviceStatus, now) { 120.0 })
        assertSame(master, ApsResultSource.displayed(config, deviceStatus, now) { null })
    }

    @Test
    fun client_staleDeviceStatus_returnsNull() {
        client()
        fresh(target = 80.0, ageMs = 16 * 60_000L)

        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { 120.0 })
        assertNull(ApsResultSource.displayed(config, deviceStatus, now) { null })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun client_noDeviceStatus_returnsNull() {
        client()
        deviceStatus.openApsTimestampOverride = -1L

        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun client_zeroOrNaNTarget_returnsNull() {
        client()
        fresh(target = 0.0, ageMs = 60_000L)
        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 })

        fresh(target = Double.NaN, ageMs = 60_000L)
        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 })
    }

    @Test
    fun client_clockSkewWithin60s_returnsTarget() {
        client()
        val master = fresh(target = 80.0, ageMs = -ApsResultSource.CLIENT_FUTURE_SLACK_MS)

        assertSame(master, ApsResultSource.displayed(config, deviceStatus, now) { null })
        assertEquals(80.0, ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 })
    }

    @Test
    fun client_futureTimestamp_returnsNull() {
        client()
        fresh(target = 80.0, ageMs = -(ApsResultSource.CLIENT_FUTURE_SLACK_MS + 1_000L))

        assertNull(ApsResultSource.displayed(config, deviceStatus, now) { null })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun pumpControl_returnsLocal() {
        config.aps = false
        config.client = false
        deviceStatus.result = TargetResult(120.0)

        assertEquals(85.0, ApsResultSource.targetMgdl(config, deviceStatus, now) { 85.0 })
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun master_displayedIob_staysLocal() {
        config.aps = true
        fresh(target = 80.0, ageMs = 60_000L)
        deviceStatus.openAPSData.displayedIob = 1.5

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
        assertEquals(0, deviceStatus.reads)
    }

    @Test
    fun client_freshDisplayedIob_usesMaster() {
        client()
        fresh(target = 80.0, ageMs = 2 * 60_000L)
        deviceStatus.openAPSData.displayedIob = 1.25

        assertEquals(1.25, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_missingDisplayedIob_usesLocal() {
        client()
        fresh(target = 80.0, ageMs = 60_000L)

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_staleDisplayedIob_usesLocal() {
        client()
        fresh(target = 80.0, ageMs = 16 * 60_000L)
        deviceStatus.openAPSData.displayedIob = 1.25

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_futureDisplayedIob_usesLocal() {
        client()
        fresh(target = 80.0, ageMs = -(ApsResultSource.CLIENT_FUTURE_SLACK_MS + 1_000L))
        deviceStatus.openAPSData.displayedIob = 1.25

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_displayedIobZero_isUsed() {
        client()
        fresh(target = 80.0, ageMs = 60_000L)
        deviceStatus.openAPSData.displayedIob = 0.0

        assertEquals(0.0, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_displayedIobNotFinite_usesLocal() {
        client()
        fresh(target = 80.0, ageMs = 60_000L)
        deviceStatus.openAPSData.displayedIob = Double.NaN

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_unreadableSuggested_displayedIob_usesLocal() {
        client()
        deviceStatus.openAPSData.clockSuggested = now - 60_000L
        deviceStatus.openAPSData.clockSuggestedDecoded = now - 10 * 60_000L
        deviceStatus.openAPSData.displayedIob = 1.25

        assertEquals(0.4, ApsResultSource.displayedIob(config, deviceStatus, now, 0.4))
    }

    @Test
    fun client_lastSuggestedUnreadable_returnsNull() {
        client()
        deviceStatus.openAPSData.clockSuggested = now - 60_000L
        deviceStatus.openAPSData.clockSuggestedDecoded = now - 10 * 60_000L
        deviceStatus.result = TargetResult(80.0)

        assertNull(ApsResultSource.displayed(config, deviceStatus, now) { null })
        assertNull(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 })
        assertEquals(0, deviceStatus.reads)
    }

    private fun client() {
        config.aps = false
        config.client = true
        deviceStatus.reads = 0
    }

    private fun fresh(target: Double, ageMs: Long): APSResult {
        val ts = now - ageMs
        deviceStatus.openAPSData.clockSuggested = ts
        deviceStatus.openAPSData.clockSuggestedDecoded = ts
        deviceStatus.openApsTimestampOverride = null
        deviceStatus.reads = 0
        return TargetResult(target).also { deviceStatus.result = it }
    }
}

private class Flags : Config {
    var aps = false
    var client = false
    override val SUPPORTED_NS_VERSION = 0
    override val APS get() = aps
    override val AAPSCLIENT get() = client
    override val AAPSCLIENT1 = false
    override val AAPSCLIENT2 = false
    override val AAPSCLIENT3 = false
    override val PUMPCONTROL = false
    override val PUMPDRIVERS = false
    override val FLAVOR = ""
    override val VERSION_NAME = ""
    override val HEAD = ""
    override val COMMITTED = false
    override val BUILD_VERSION = ""
    override val REMOTE = ""
    override val BUILD_TYPE = ""
    override val VERSION = ""
    override val platform = AppPlatform.Android
    override val APPLICATION_ID = ""
    override val DEBUG = false
    override val currentDeviceModelString = ""
    override val deviceModelForUpload = ""
    override val deviceManufacturer = ""
    override val appName: TextRef = TextRef.Named("test", "unused")
    override val initProgressFlow = MutableStateFlow(InitProgress())
    override val initSnackbarFlow = MutableSharedFlow<String>()
    override fun updateInitProgress(step: String, current: Int, total: Int) = Unit
    override fun initCompleted() = Unit
    override fun initFailed(error: String) = Unit
    override fun showInitSnackbar(message: String) = Unit
    override fun beginReconfiguring() = Unit
    override fun endReconfiguring() = Unit
    override fun isDev() = false
    override fun isEngineeringModeOrRelease() = false
    override fun isEngineeringMode() = false
    override fun isEnabled(option: ExternalOptions) = false
}

private class DeviceStatus : ProcessedDeviceStatusData {
    var result: APSResult? = null
    var reads = 0
    var openApsTimestampOverride: Long? = null
    override var pumpData: ProcessedDeviceStatusData.PumpData? = null
    override var device: ProcessedDeviceStatusData.Device? = null
    override val uploaderMap = HashMap<String, ProcessedDeviceStatusData.Uploader>()
    override val activeUploaders: Map<String, ProcessedDeviceStatusData.Uploader> get() = uploaderMap
    override var openAPSData = ProcessedDeviceStatusData.OpenAPSData()
    override val openApsTimestamp: Long
        get() = openApsTimestampOverride ?: if (openAPSData.clockSuggested != 0L) openAPSData.clockSuggested else -1L
    override fun getAPSResult(): APSResult? {
        reads++
        return result
    }
    override val uploaderStatus = ""
}

private class TargetResult(override var targetBG: Double) : APSResult {
    override fun with(result: RT) = this
    override var date = 0L
    override var reason = ""
    override var rate = 0.0
    override var percent = 0
    override var duration = 0
    override var smb = 0.0
    override var usePercent = false
    override var carbsReq = 0
    override var carbsReqWithin = 0
    override var deliverAt = 0L
    override var hasPredictions = false
    override var variableSens: Double? = null
    override var isfMgdlForCarbs: Double? = null
    override var scriptDebug: List<String>? = null
    override var isHypoRisk = false
    override val predictionsAsGv = mutableListOf<GV>()
    override val latestPredictionsTime = 0L
    override suspend fun isChangeRequested() = false
    override var isTempBasalRequested = false
    override val carbsRequiredText = ""
    override var inputConstraints: Constraint<Double>? = null
    override var rateConstraint: Constraint<Double>? = null
    override var percentConstraint: Constraint<Int>? = null
    override var smbConstraint: Constraint<Double>? = null
    override var algorithm = APSResult.Algorithm.UNKNOWN
    override var autosensResult: AutosensResult? = null
    override var iobData: Array<IobTotal>? = null
    override var glucoseStatus: GlucoseStatus? = null
    override var currentTemp: CurrentTemp? = null
    override var oapsProfile: OapsProfile? = null
    override var oapsProfileAutoIsf: OapsProfileAutoIsf? = null
    override var oapsProfileAimi: OapsProfileAimi? = null
    override var mealData: MealData? = null
    override suspend fun resultAsString() = ""
    override fun newAndClone() = this
    override fun json(): JsonObject? = null
    override fun predictions(): Predictions? = null
    override fun rawData(): Any = this
}
