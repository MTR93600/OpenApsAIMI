package app.aaps.core.interfaces.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceHandoversCodecTest {

    @Test
    fun `fields come back the way they went in`() {
        val fields = mapOf("pin" to "1234", "address" to "AA:BB:CC:DD:EE:FF", "text" to "with \"quotes\" and \\ slash")

        assertEquals(fields, DeviceHandovers.decode(DeviceHandovers.encode(fields)))
    }

    @Test
    fun `text that is not a handover gives nothing`() {
        assertNull(DeviceHandovers.decode("not json"))
        assertNull(DeviceHandovers.decode("[1, 2]"))
        assertNull(DeviceHandovers.decode("""{"pin": {"nested": 1}}"""))
    }
}
