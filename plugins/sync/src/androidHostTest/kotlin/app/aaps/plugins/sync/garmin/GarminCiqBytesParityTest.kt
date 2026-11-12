package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The iPhone codec against Android's `GarminSerializer`, and the watch app ids against the private
 * map `GarminPlugin` actually pushes to. Neither production class on Android is changed.
 */
class GarminCiqBytesParityTest : TestBaseWithProfile() {

    @Mock private lateinit var loopHub: LoopHub
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    private lateinit var plugin: GarminPlugin

    @BeforeEach
    fun setup() {
        plugin = GarminPlugin(aapsLogger, rh, preferences, context, loopHub, persistenceLayer, config, notificationManager)
        plugin.clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    }

    @Test
    fun theGoldenGlucoseBytesAreWhatAndroidSerializes() {
        assertContentEquals(
            GarminCiqVectors.glucoseBytes(),
            GarminSerializer.serialize(GarminCiqVectors.glucoseMessage),
        )
        assertContentEquals(
            GarminSerializer.serialize(GarminCiqVectors.glucoseMessage),
            GarminCiqBytes.serialize(GarminCiqVectors.glucoseMessage),
        )
    }

    @Test
    fun aLiveGlucoseMessageHasTheSameBytesOnBothCodecs() {
        whenever(preferences.get(GarminStringKey.RequestKey)).thenReturn("abc")
        whenever(loopHub.currentProfileName).thenReturn("Default")
        whenever(loopHub.glucoseUnit).thenReturn(GlucoseUnit.MGDL)
        whenever(loopHub.insulinOnboard).thenReturn(1.25)
        whenever(loopHub.insulinBasalOnboard).thenReturn(0.5)
        whenever(loopHub.temporaryBasal).thenReturn(1.0)
        whenever(loopHub.isConnected).thenReturn(true)
        whenever(loopHub.getGlucoseValues(any(), any())).thenReturn(emptyList())

        val message = plugin.getGlucoseMessage()
        assertEquals(
            listOf(
                "key",
                "command",
                "profile",
                "encodedGlucose",
                "remainingInsulin",
                "remainingBasalInsulin",
                "glucoseUnit",
                "temporaryBasalRate",
                "connected",
                "timestamp",
            ),
            message.keys.toList(),
        )
        // The profile character is what Android has always sent: the first letter of the name.
        assertEquals("D", message["profile"])
        assertEquals("glucose", message["command"])
        assertContentEquals(GarminSerializer.serialize(message), GarminCiqBytes.serialize(message))
    }

    @Test
    fun theWatchAppIdsAreTheAndroidOnesInTheSameOrder() {
        val field = GarminPlugin::class.java.getDeclaredField("glucoseAppIds")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ids = field.get(plugin) as Map<String, String>
        assertEquals(GarminWatchApps.byId.keys.toList(), ids.keys.toList())
        assertEquals(GarminWatchApps.byId, ids)
    }
}
