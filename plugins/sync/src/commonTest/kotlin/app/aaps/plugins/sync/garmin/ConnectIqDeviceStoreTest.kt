package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectIqDeviceStoreTest {

    private val watch = ConnectIqDevice(
        uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        modelName = "fenix 7",
        friendlyName = "Left",
        partNumber = "006-B",
    )

    @Test
    fun aDeviceRoundTrips() {
        val text = ConnectIqDeviceStore.encode(listOf(watch))
        assertEquals(
            """[{"uuid":"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE","modelName":"fenix 7","friendlyName":"Left","partNumber":"006-B"}]""",
            text,
        )
        assertEquals(listOf(watch.copy(uuid = watch.uuid.uppercase())), ConnectIqDeviceStore.decode(text))
    }

    @Test
    fun blankAndGarbageDecodeToNothing() {
        assertEquals(emptyList(), ConnectIqDeviceStore.decode(null))
        assertEquals(emptyList(), ConnectIqDeviceStore.decode(""))
        assertEquals(emptyList(), ConnectIqDeviceStore.decode("not json"))
        assertEquals(emptyList(), ConnectIqDeviceStore.decode("""{"uuid":"x"}"""))
    }

    @Test
    fun aRepeatedUuidKeepsTheLastRecordInTheFirstPosition() {
        val first = watch.copy(friendlyName = "Old")
        val again = watch.copy(friendlyName = "New")
        val other = watch.copy(uuid = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", friendlyName = "Other")
        val stored = ConnectIqDeviceStore.normalize(listOf(first, other, again, ConnectIqDevice("", "", "", "")))
        assertEquals(listOf("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "BBBBBBBB-BBBB-CCCC-DDDD-EEEEEEEEEEEE"), stored.map { it.uuid })
        assertEquals("New", stored[0].friendlyName)
    }

    @Test
    fun onlyAReadyWatchIsPushedTo() {
        assertFalse(ConnectIqDeviceStatus.UNKNOWN.canPush)
        assertFalse(ConnectIqDeviceStatus.CONNECTED.canPush)
        assertTrue(ConnectIqDeviceStatus.READY.canPush)
        assertFalse(ConnectIqDeviceStatus.NOT_CONNECTED.canPush)
        assertFalse(ConnectIqDeviceStatus.BLUETOOTH_NOT_READY.canPush)
        assertEquals(ConnectIqDeviceStatus.NOT_CONNECTED, ConnectIqDeviceStatus.fromWire("NOT_CONNECTED"))
        assertEquals(ConnectIqDeviceStatus.UNKNOWN, ConnectIqDeviceStatus.fromWire("later"))
    }

    @Test
    fun aPushStartsOnlyWhenTheWatchBecomesReady() {
        assertTrue(shouldPushOnStatus(ConnectIqDeviceStatus.CONNECTED, ConnectIqDeviceStatus.READY))
        assertTrue(shouldPushOnStatus(ConnectIqDeviceStatus.UNKNOWN, ConnectIqDeviceStatus.READY))
        assertFalse(shouldPushOnStatus(ConnectIqDeviceStatus.UNKNOWN, ConnectIqDeviceStatus.CONNECTED))
        assertFalse(shouldPushOnStatus(ConnectIqDeviceStatus.READY, ConnectIqDeviceStatus.READY))
        assertFalse(shouldPushOnStatus(ConnectIqDeviceStatus.READY, ConnectIqDeviceStatus.NOT_CONNECTED))
    }

    @Test
    fun theJsonSwiftWritesIsAcceptedWhateverTheKeyOrder() {
        val raw = """[{"partNumber":"006-B","friendlyName":"Left","modelName":"fenix 7","uuid":"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"}]"""
        assertEquals(listOf(watch.copy(uuid = watch.uuid.uppercase())), ConnectIqDeviceStore.decode(raw))
    }
}
