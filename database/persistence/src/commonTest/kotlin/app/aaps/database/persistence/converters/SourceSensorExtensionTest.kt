package app.aaps.database.persistence.converters

import app.aaps.core.data.model.SourceSensor
import app.aaps.database.entities.GlucoseValue
import kotlin.test.Test
import kotlin.test.assertEquals

internal class SourceSensorExtensionTest {

    @Test
    fun `domain toDb then fromDb is stable for all values`() {
        SourceSensor.entries.forEach { value ->
            assertEquals(value, value.toDb().fromDb())
        }
    }

    @Test
    fun `entity fromDb then toDb is stable for all values`() {
        GlucoseValue.SourceSensor.entries.forEach { value ->
            assertEquals(value, value.fromDb().toDb())
        }
    }

    @Test
    fun `representative mappings map by name`() {
        assertEquals(GlucoseValue.SourceSensor.DEXCOM_G6, SourceSensor.DEXCOM_G6.toDb())
        assertEquals(SourceSensor.LIBRE_2, GlucoseValue.SourceSensor.LIBRE_2.fromDb())
        assertEquals(GlucoseValue.SourceSensor.UNKNOWN, SourceSensor.UNKNOWN.toDb())
        assertEquals(SourceSensor.ZT_PREDICTION, GlucoseValue.SourceSensor.ZT_PREDICTION.fromDb())
        // Fork-only sensors. The two round-trip tests above already cover them by iterating every
        // value; these two are kept as named cases, as they were before this test moved to commonTest.
        assertEquals(GlucoseValue.SourceSensor.DEXCOM_ONEPLUS_NATIVE, SourceSensor.DEXCOM_ONEPLUS_NATIVE.toDb())
        assertEquals(SourceSensor.LIBRE_3_NATIVE, GlucoseValue.SourceSensor.LIBRE_3_NATIVE.fromDb())
    }
}
