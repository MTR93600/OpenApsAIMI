package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The glucose encoding the watch reads, on every target. The iPhone must send the watch the same
 * text as Android; the expected value is the one `GarminPluginTest` checks on Android.
 */
class DeltaVarEncodedListCommonTest {

    @Test
    fun oneGlucoseValueEncodesToTheSameTextAsOnAndroid() {
        val list = DeltaVarEncodedList(16, 2)
        list.add(1_000, 93)

        assertEquals("0A+6AQ==", list.encodedBase64())
    }

    @Test
    fun encodedValuesDecodeBackToTheSameValues() {
        val values = intArrayOf(1511636926, 137, 1511637226, 138, 1511637526, 136, 1511637826, 140)
        val list = DeltaVarEncodedList(4, 2)
        for (i in values.indices step 2) list.add(values[i], values[i + 1])

        assertContentEquals(values, list.toArray())
    }

    @Test
    fun anEmptyBufferGrowsInsteadOfFailing() {
        val list = DeltaVarEncodedList(0, 2)
        list.add(1_000, 93)

        assertEquals("0A+6AQ==", list.encodedBase64())
    }
}
