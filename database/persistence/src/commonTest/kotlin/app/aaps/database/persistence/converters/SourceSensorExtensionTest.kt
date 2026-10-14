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

    /**
     * Going the other way cannot be an identity here, and that is by design.
     *
     * This fork kept the per-bridge entity entries that upstream merged away, so several of them
     * mean the same sensor to the rest of the app: `DEXCOM_NATIVE_UNKNOWN` and `DEXCOM_UNKNOWN`
     * both read as `DEXCOM_UNKNOWN`, and only one of the two can come back from it. The entries
     * stay because AIMI reads `DEXCOM_G6_NATIVE` and `DEXCOM_G7_NATIVE` to decide a correction for
     * the native feed, and because an old row should keep the name it was written with.
     *
     * What the boundary does owe is that the meaning survives: once a row is read, writing it back
     * and reading it again gives the same sensor.
     */
    @Test
    fun `an entity value keeps its meaning through a round trip`() {
        GlucoseValue.SourceSensor.entries.forEach { value ->
            val domain = value.fromDb()
            assertEquals(domain, domain.toDb().fromDb(), value.name)
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
