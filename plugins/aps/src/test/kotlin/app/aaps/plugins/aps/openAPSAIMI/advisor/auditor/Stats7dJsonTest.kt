package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A 7-day value that was not measured must reach the auditor as JSON null, never as a number. */
class Stats7dJsonTest {

    private val keys = listOf("tir", "hypoPct", "hyperPct", "meanBG", "cv", "tdd7dAvg", "basalPct", "bolusPct")

    @Test
    fun `missing values are written as null and the keys stay`() {
        val json = Stats7d(null, null, null, null, null, null, null, null).toJSON()
        keys.forEach { key ->
            assertTrue(json.has(key), key)
            assertTrue(json.isNull(key), key)
            assertEquals(JSONObject.NULL, json.get(key), key)
        }
    }

    @Test
    fun `measured values are written as numbers`() {
        val json = Stats7d(80.0, 1.0, 19.0, 130.0, 30.0, 40.0, 50.0, 50.0).toJSON()
        assertEquals(80.0, json.getDouble("tir"), 1e-9)
        assertEquals(40.0, json.getDouble("tdd7dAvg"), 1e-9)
    }
}
