package app.aaps.ui.widget.state

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.IsfStep
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JSON the iOS Live Activity decodes. The keys are a contract with the Swift struct
 * `AapsActivityAttributes.ContentState`, so a renamed key must fail here.
 */
class WidgetStateJsonTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun freshReadingCarriesRawNumbersAndStaleAt() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now - 2 * min, isStale = false), FakeResolver)

        assertEquals(WidgetStateJson.VERSION, json.int("v"))
        assertEquals("120", json.text("bgText"))
        assertEquals(120.0, json.double("bgMgdl"))
        assertEquals(now - 2 * min, json.long("bgTime"))
        // The same 9 minute rule as the app, so iOS needs no copy of it.
        assertEquals(now - 2 * min + WidgetStateRules.STALE_AFTER_MS, json.long("staleAt"))
        assertFalse(json.bool("stale"))
        assertEquals("HIGH", json.text("range"))
        assertEquals("SINGLE_UP", json.text("trend"))
        assertEquals("rising", json.text("trendText"))
    }

    @Test
    fun staleReadingIsMarkedAndKeepsItsTime() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now - 20 * min, isStale = true), FakeResolver)

        assertTrue(json.bool("stale"))
        assertEquals(now - 20 * min, json.long("bgTime"))
        assertEquals(now - 20 * min + WidgetStateRules.STALE_AFTER_MS, json.long("staleAt"))
        assertEquals("a11y_bg_old_reading", json.text("staleText"))
    }

    @Test
    fun noReadingHasNoTimesAndIsStale() {
        val json = WidgetStateJson.encode(
            state(bgTimestamp = null, bgMgdl = null, isStale = true, trend = null, delta = null),
            FakeResolver
        )

        assertTrue(json["bgMgdl"] is JsonNull)
        assertTrue(json["bgTime"] is JsonNull)
        assertTrue(json["staleAt"] is JsonNull)
        assertTrue(json["trend"] is JsonNull)
        assertTrue(json.bool("stale"))
        assertEquals("value_unavailable_short", json.text("deltaText"))
    }

    @Test
    fun textsAreMadeFromRawNumbersWithFormatResources() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false), FakeResolver)

        assertEquals("value_with_unit(+3, mgdl)", json.text("deltaText"))
        assertEquals(3.0, json.double("deltaMgdl"))
        assertEquals("format_insulin_units(1.25)", json.text("iobText"))
        assertEquals(1.25, json.double("iob"))
        assertEquals("format_carbs(13)", json.text("cobText"))
        assertEquals(12.6, json.double("cob"))
        assertEquals("widget_carbs_required(format_carbs(20))", json.text("carbsReqText"))
        assertEquals(20, json.int("carbsReqGrams"))
        assertEquals(now - min, json.long("lastLoopTime"))
        assertEquals("loop", json.text("loopLabel"))
        assertEquals("CLOSED_LOOP", json.text("mode"))
        assertFalse(json.bool("modeActive"))
        // Never the frozen texts of the Android widgets.
        assertFalse(json.containsKey("timeAgoText"))
        assertFalse(json.containsKey("cobText") && json.text("cobText").contains("\n"))
    }

    @Test
    fun carbsRequiredUsesItsOwnTemplate() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false), FakeResolver)
        // Not the generic "value unit" template: "required" is not a unit, and a translator must be
        // able to put the words in their own order.
        assertEquals("widget_carbs_required(format_carbs(20))", json.text("carbsReqText"))
    }

    /**
     * Swift's JSONDecoder refuses a bare NaN or Infinity, and would then drop the whole update. A
     * value that is not a real number must travel as "no value", and a reading that is not a real
     * number must show as no reading, never as a fresh one.
     */
    @Test
    fun nonFiniteNumbersAreSentAsNoValue() {
        val points = listOf(
            WidgetChartPoint(now - 10 * min, 100.0),
            WidgetChartPoint(now - 5 * min, Double.NaN),
            WidgetChartPoint(now, Double.POSITIVE_INFINITY)
        )
        val json = WidgetStateJson.encode(
            state(
                bgTimestamp = now, isStale = false, bgMgdl = Double.NaN, delta = Double.NEGATIVE_INFINITY,
                iob = Double.NaN, cob = Double.POSITIVE_INFINITY, chart = points
            ),
            FakeResolver
        )
        val text = json.toString()

        assertFalse(text.contains("NaN"), text)
        assertFalse(text.contains("Infinity"), text)
        assertTrue(json["bgMgdl"] is JsonNull)
        assertTrue(json["bgTime"] is JsonNull)
        assertTrue(json["staleAt"] is JsonNull)
        assertTrue(json["trend"] is JsonNull)
        assertTrue(json.bool("stale"))
        assertEquals("value_unavailable_short", json.text("bgText"))
        assertTrue(json["deltaMgdl"] is JsonNull)
        assertEquals("value_unavailable_short", json.text("deltaText"))
        assertTrue(json["iob"] is JsonNull)
        assertEquals("value_unavailable_short", json.text("iobText"))
        assertTrue(json["cob"] is JsonNull)
        assertEquals("value_unavailable_short", json.text("cobText"))
        assertEquals(1, json["chart"]!!.jsonArray.size)
    }

    @Test
    fun noCarbsRequestedGivesNullText() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false, carbsRequired = false), FakeResolver)
        assertTrue(json["carbsReqText"] is JsonNull)
        assertEquals(0, json.int("carbsReqGrams"))
    }

    @Test
    fun mmolUsesTheMmolUnitText() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false, units = GlucoseUnit.MMOL), FakeResolver)
        assertEquals("mmol", json.text("unitText"))
    }

    @Test
    fun chartIsPairsOfTimeAndMgdlOldestFirst() {
        val points = listOf(WidgetChartPoint(now - 10 * min, 100.0), WidgetChartPoint(now - 5 * min, 110.5))
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false, chart = points), FakeResolver)

        val chart = json["chart"]!!.jsonArray
        assertEquals(2, chart.size)
        assertEquals(now - 10 * min, (chart[0] as JsonArray)[0].jsonPrimitive.long)
        assertEquals(110.5, (chart[1] as JsonArray)[1].jsonPrimitive.double)
    }

    @Test
    fun chartIsEmptyWhenNotAsked() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false), FakeResolver)
        assertEquals(0, json["chart"]!!.jsonArray.size)
    }

    @Test
    fun fullStateStaysWellBelowTheLiveActivityLimit() {
        // ActivityKit refuses content above 4 KB. A full chart is the largest state.
        val points = (0 until WidgetStateRules.CHART_MAX_POINTS).map { WidgetChartPoint(now - it * 5 * min, 123.456) }
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false, chart = points), FakeResolver)
        assertTrue(json.toString().length < 3_000, "size ${json.toString().length}")
    }

    @Test
    fun liveActivityStateHasNoDetailKeys() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false), FakeResolver)
        for (key in detailKeys) assertFalse(json.containsKey(key), key)
    }

    @Test
    fun detailsCarryTheAndroidWidgetChipsAsNames() {
        val json = WidgetStateJson.encode(
            state(bgTimestamp = now, isStale = false).copy(
                tempTargetText = "Eating soon 90",
                targetChip = WidgetTargetChip.TEMP,
                tempTargetReason = TT.Reason.EATING_SOON,
                targetIcon = WidgetTargetIcon.EATING_SOON,
                profileText = "Day (110%)",
                profileModified = true,
                runningMode = RM.Mode.OPEN_LOOP,
                loopIcon = WidgetLoopIcon.OPEN,
                sensitivityIcon = WidgetSensitivityIcon.OFF_BELOW,
                sensitivityText = "85%",
                isfFromText = "40",
                isfToText = "47",
                isfStep = IsfStep.UP,
                isfStepDescription = "ISF up",
                tbrState = WidgetTbrState.BELOW_PROFILE,
                tbrDescription = "Temp basal below profile",
                iobActive = false,
                cobActive = true
            ),
            FakeResolver,
            withDetails = true
        )

        for (key in detailKeys) assertTrue(json.containsKey(key), key)
        assertEquals("Eating soon 90", json.text("targetText"))
        assertEquals("TEMP", json.text("targetChip"))
        assertEquals("EATING_SOON", json.text("ttReason"))
        assertEquals("EATING_SOON", json.text("targetIcon"))
        assertEquals("Day (110%)", json.text("profileText"))
        assertTrue(json.bool("profileModified"))
        assertEquals("OPEN", json.text("loopIcon"))
        assertEquals("OPEN_LOOP", json.text("mode"))
        assertEquals("OFF_BELOW", json.text("sensIcon"))
        assertEquals("85%", json.text("sensText"))
        assertEquals("40", json.text("isfFromText"))
        assertEquals("47", json.text("isfToText"))
        assertEquals("UP", json.text("isfStep"))
        assertEquals("ISF up", json.text("isfStepText"))
        assertEquals("BELOW_PROFILE", json.text("tbr"))
        assertEquals("Temp basal below profile", json.text("tbrText"))
        assertFalse(json.bool("iobActive"))
        assertTrue(json.bool("cobActive"))
    }

    @Test
    fun detailsWithoutTempTargetOrIsfChangeSendNull() {
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false), FakeResolver, withDetails = true)

        assertEquals(JsonNull, json["ttReason"])
        assertEquals(JsonNull, json["isfStepText"])
        assertEquals("NONE", json.text("targetChip"))
        assertEquals("NONE", json.text("tbr"))
    }

    @Test
    fun detailsDoNotChangeTheLiveActivityKeys() {
        // The home widgets read the same base keys as the Live Activity, so both judge the age alike.
        val base = WidgetStateJson.encode(state(bgTimestamp = now - 20 * min, isStale = true), FakeResolver)
        val full = WidgetStateJson.encode(state(bgTimestamp = now - 20 * min, isStale = true), FakeResolver, withDetails = true)
        for ((key, value) in base) assertEquals(value, full[key], key)
        assertTrue(full.bool("stale"))
    }

    @Test
    fun fullStateWithDetailsStaysSmall() {
        // The widget reads it from a file on every timeline; it must stay a small read.
        val points = (0 until WidgetStateRules.CHART_MAX_POINTS).map { WidgetChartPoint(now - it * 5 * min, 123.456) }
        val json = WidgetStateJson.encode(state(bgTimestamp = now, isStale = false, chart = points), FakeResolver, withDetails = true)
        assertTrue(json.toString().length < 4_000, "size ${json.toString().length}")
    }

    private val detailKeys = listOf(
        "targetText", "targetChip", "ttReason", "targetIcon", "profileText", "profileModified", "loopIcon",
        "sensIcon", "sensText", "isfFromText", "isfToText", "isfStep", "isfStepText", "tbr", "tbrText",
        "iobActive", "cobActive"
    )

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean

    /** Answers the string name, and the arguments in brackets, so a test sees which resource made a text. */
    private object FakeResolver : TextResolver {

        override fun gs(ref: TextRef): String = name(ref)

        override fun gs(ref: TextRef, vararg args: Any?): String =
            if (args.isEmpty()) name(ref) else "${name(ref)}(${args.joinToString(", ")})"

        override fun gsNotLocalised(ref: TextRef): String = name(ref)

        private fun name(ref: TextRef): String = when (ref) {
            is TextRef.Named      -> ref.name
            is TextRef.AndroidRes -> ref.id.toString()
            is TextRef.Literal    -> ref.text
        }
    }

    private fun state(
        bgTimestamp: Long?,
        isStale: Boolean,
        bgMgdl: Double? = 120.0,
        trend: TrendArrow? = TrendArrow.SINGLE_UP,
        delta: Double? = 3.0,
        iob: Double = 1.25,
        cob: Double? = 12.6,
        carbsRequired: Boolean = true,
        units: GlucoseUnit = GlucoseUnit.MGDL,
        chart: List<WidgetChartPoint> = emptyList()
    ) = WidgetState(
        bgText = "120",
        bgMgdl = bgMgdl,
        bgTimestamp = bgTimestamp,
        units = units,
        bgRange = WidgetBgRange.HIGH,
        isStale = isStale,
        trendArrow = trend,
        arrowDescription = trend?.let { "rising" },
        bgSpoken = "120",
        deltaText = if (delta != null) "+3" else "---",
        deltaMgdl = delta,
        timeAgoText = "2 min ago",
        iobText = "1.25 U",
        iob = iob,
        iobActive = true,
        iobLabel = "IOB",
        cobText = "13 g\n20 g req",
        cob = cob,
        cobActive = true,
        cobLabel = "COB",
        carbsRequired = carbsRequired,
        carbsRequiredGrams = if (carbsRequired) 20 else 0,
        tempTargetText = "",
        targetChip = WidgetTargetChip.NONE,
        tempTargetReason = null,
        targetIcon = WidgetTargetIcon.PROFILE_TARGET,
        profileText = "",
        profileModified = false,
        runningMode = RM.Mode.CLOSED_LOOP,
        runningModeText = "Closed loop",
        loopIcon = WidgetLoopIcon.CLOSED,
        runningModeActive = false,
        lastLoopTime = now - min,
        sensitivityIcon = WidgetSensitivityIcon.EQUAL,
        sensitivityText = "",
        isfFromText = "",
        isfToText = "",
        isfStep = IsfStep.SAME,
        isfStepDescription = null,
        tbrState = WidgetTbrState.NONE,
        tbrDescription = "",
        chartPoints = chart
    )
}
