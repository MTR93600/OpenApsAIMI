package app.aaps.database

import app.aaps.database.di.MIGRATION_35_TO_36_PAIRS
import app.aaps.database.entities.GlucoseValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `sourceSensor` column holds the entry name. Entries that were merged into one no longer exist
 * under their old name, so reading an old row depends on [LEGACY_SOURCE_SENSOR_NAMES]. Without it
 * those rows would come back as `UNKNOWN`, losing the sensor and also pulling old readings into the
 * flat check.
 *
 * In `commonTest`, so this runs through Kotlin/Native as well as the JVM.
 */
class ConvertersSourceSensorTest {

    private val converters = Converters()

    @Test
    fun `current names read back unchanged`() {
        GlucoseValue.SourceSensor.entries.forEach { entry ->
            assertEquals(entry, converters.toSourceSensor(entry.name), entry.name)
        }
    }

    /**
     * A stored name is never lost, whichever design does the work.
     *
     * Upstream merged the per-bridge entries and reads old rows through
     * [LEGACY_SOURCE_SENSOR_NAMES]. This fork kept the entries instead, because AIMI reads
     * `DEXCOM_G6_NATIVE` and `DEXCOM_G7_NATIVE` to decide a correction that applies to the native
     * feed and not to the same sensor read through a bridge. An old row therefore resolves to its
     * own entry here, and `SourceSensorExtension.fromDb` collapses the names that carry no meaning
     * for dosing.
     *
     * The two designs owe the same guarantee, and that is what is asserted: a name that was once
     * written never comes back as `UNKNOWN`, which would lose the sensor and also pull months of
     * old readings into the flat check.
     */
    @Test
    fun `a name written by an older version is never read back as unknown`() {
        LEGACY_SOURCE_SENSOR_NAMES.keys.forEach { name ->
            assertTrue(converters.toSourceSensor(name) != GlucoseValue.SourceSensor.UNKNOWN, name)
        }
    }

    /**
     * In this fork the map is a fallback that cannot fire, because every name in it is still an
     * entry of its own. Keeping it is deliberate: it is what would save old rows if an entry were
     * ever removed, so this pins the relationship rather than letting it drift unnoticed.
     */
    @Test
    fun `every legacy name is still an entry of its own in this fork`() {
        val current = GlucoseValue.SourceSensor.entries.map { it.name }.toSet()
        LEGACY_SOURCE_SENSOR_NAMES.keys.forEach { assertTrue(it in current, it) }
    }

    /**
     * `migration35to36` rewrites the column so that the queries comparing it as text keep matching.
     * It spells its pairs out, because a migration has to keep doing the same thing forever, so this
     * checks the two agree today rather than letting them drift apart unnoticed.
     *
     * Two names are left alone on purpose, and that is the whole point of this test: see
     * [KEPT_BY_THIS_FORK].
     */
    @Test
    fun `the migration rewrites every legacy name except the two this fork keeps`() {
        val migrated = MIGRATION_35_TO_36_PAIRS
        assertEquals(LEGACY_SOURCE_SENSOR_NAMES.size - KEPT_BY_THIS_FORK.size, migrated.size)
        LEGACY_SOURCE_SENSOR_NAMES.forEach { (legacy, entry) ->
            if (legacy in KEPT_BY_THIS_FORK) {
                assertNull(migrated[legacy], legacy)
            } else {
                assertEquals(entry.name, migrated[legacy], legacy)
            }
        }
    }

    /**
     * A row already written with one of these keeps its name, so the reading stays recognisable.
     *
     * AIMI reads `DEXCOM_G6_NATIVE` and `DEXCOM_G7_NATIVE` to decide a correction that applies to
     * the native BYODA feed and not to the same sensor read through a bridge. Rewriting them would
     * erase that distinction for every reading already recorded.
     */
    @Test
    fun `a name this fork keeps is still an entry so the row stays matchable`() {
        KEPT_BY_THIS_FORK.forEach { name ->
            assertEquals(GlucoseValue.SourceSensor.valueOf(name), converters.toSourceSensor(name), name)
        }
    }

    @Test
    fun `an unknown name does not throw`() {
        assertEquals(GlucoseValue.SourceSensor.UNKNOWN, converters.toSourceSensor("SOMETHING_REMOVED_LATER"))
        assertNull(converters.toSourceSensor(null))
    }

    private companion object {

        /** The names `migration35to36` must not rewrite. See the comment beside its pairs. */
        val KEPT_BY_THIS_FORK = setOf("DEXCOM_G6_NATIVE", "DEXCOM_G7_NATIVE")
    }
}
