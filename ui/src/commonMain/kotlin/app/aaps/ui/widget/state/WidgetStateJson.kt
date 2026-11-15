package app.aaps.ui.widget.state

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * [WidgetState] as JSON, for a widget that Kotlin does not draw: the iOS Live Activity and the iOS
 * home and lock screen widgets. The Swift struct `AapsActivityAttributes.ContentState` decodes
 * exactly these keys. Change both together and raise [VERSION] when a key changes meaning.
 *
 * The JSON carries raw numbers and texts. A text that depends on the time of showing is never sent:
 * there is no "3 min ago" (`timeAgoText` stops counting when the app stops). The reader shows the age
 * from `bgTime` itself, and judges the age with `staleAt`. All other texts come from format string
 * resources, made from the raw numbers here, so the Swift side needs no translations of its own.
 *
 * Keys (glucose always in mg/dL, times in epoch ms):
 * - `v`: [VERSION].
 * - `bgText`: the reading in the user's units, or the "no value" text. `bgMgdl`, `bgTime`: the raw
 *   reading, null without one.
 * - `staleAt`: the moment the reading turns too old to trust (the app's 9 minute rule,
 *   [WidgetStateRules.STALE_AFTER_MS]). Null without a reading, which is always stale. `stale`: true
 *   when it was already too old when this JSON was built.
 * - `range`: `LOW`, `IN_RANGE` or `HIGH`. Says nothing about the age; the reader paints a stale value
 *   grey whatever the range.
 * - `trend`: a `TrendArrow` name, null when unknown or without a reading. `trendText`: its spoken name.
 * - `unitText`, `deltaMgdl`, `deltaText`: the change over the last 5 minutes.
 * - `iob`, `iobText`, `iobLabel`; `cob`, `cobText`, `cobLabel`.
 *
 * A number that is not a real number (NaN, infinity) is sent as null with the "no value" text, because
 * it is not valid JSON. A reading that is not a real number is sent as no reading: no `bgMgdl`,
 * `bgTime`, `staleAt` or trend, and `stale` true. A chart point that is not a real number is left out.
 * - `carbsReqGrams` (0 when none) and `carbsReqText` (null when the loop asks for no carbs).
 * - `lastLoopTime` (null when this phone does not run the loop) and `loopLabel`.
 * - `mode` (a `RM.Mode` name), `modeText`, `modeActive` (any mode other than closed loop).
 * - `staleText`: the word added for a screen reader when the value is old.
 * - `chart`: `[[time, mgdl], ...]`, oldest first, empty unless the state was built with the chart.
 *
 * Details, only with `withDetails` (the iOS home and lock screen widgets, which show what the Android
 * widget shows; the Live Activity leaves them out to stay small). The Swift struct `AapsWidgetDetails`
 * decodes them. Enum values are Kotlin names; the reader maps them to its own colours and pictures.
 * - `targetText`, `targetChip` (a [WidgetTargetChip] name), `ttReason` (a `TT.Reason` name, null when
 *   no temp target runs), `targetIcon` (a [WidgetTargetIcon] name).
 * - `profileText`, `profileModified`.
 * - `loopIcon` (a [WidgetLoopIcon] name).
 * - `sensIcon` (a [WidgetSensitivityIcon] name), `sensText`, `isfFromText`, `isfToText`, `isfStep`
 *   (an `IsfStep` name), `isfStepText` (null when the two values are the same).
 * - `tbr` (a [WidgetTbrState] name), `tbrText`.
 * - `iobActive`, `cobActive`: the chip asks for attention.
 */
object WidgetStateJson {

    const val VERSION = 1

    /**
     * @param withDetails also add the detail keys listed above, for the iOS home screen widgets.
     */
    fun encode(state: WidgetState, rh: TextResolver, withDetails: Boolean = false): JsonObject {
        val unavailable = rh.gs(CoreUiStrings.value_unavailable_short)
        val unitText = rh.gs(if (state.units == GlucoseUnit.MGDL) CoreUiStrings.mgdl else CoreUiStrings.mmol)
        // A reading that is not a real number (NaN) is sent as no reading: no value, no time, always
        // stale. `bgIsNumber` is true for a real number and for no reading at all.
        val bgMgdl = state.bgMgdl.finiteOrNull()
        val bgIsNumber = state.bgMgdl == null || bgMgdl != null
        val bgTime = if (bgIsNumber) state.bgTimestamp else null
        val staleAt = if (bgIsNumber) state.staleAt else null
        val deltaMgdl = state.deltaMgdl.finiteOrNull()
        val deltaText = if (deltaMgdl != null) rh.gs(CoreUiStrings.value_with_unit, state.deltaText, unitText) else unavailable
        val iob = state.iob.finiteOrNull()
        val iobText = iob?.let { rh.gs(InterfacesStrings.format_insulin_units, it) } ?: unavailable
        val cob = state.cob.finiteOrNull()
        val cobText = cob?.let { rh.gs(InterfacesStrings.format_carbs, it.roundToInt()) } ?: unavailable
        val carbsReqText = if (state.carbsRequired)
            rh.gs(UiStrings.widget_carbs_required, rh.gs(InterfacesStrings.format_carbs, state.carbsRequiredGrams))
        else null

        return buildJsonObject {
            put("v", VERSION)
            put("bgText", if (bgIsNumber) state.bgText else unavailable)
            put("bgMgdl", bgMgdl)
            put("bgTime", bgTime)
            put("staleAt", staleAt)
            put("stale", state.isStale || !bgIsNumber)
            put("range", state.bgRange.name)
            put("trend", if (bgIsNumber) state.trendArrow?.name else null)
            put("trendText", if (bgIsNumber) state.arrowDescription else null)
            put("unitText", unitText)
            put("deltaMgdl", deltaMgdl)
            put("deltaText", deltaText)
            put("iob", iob)
            put("iobText", iobText)
            put("iobLabel", state.iobLabel)
            put("cob", cob)
            put("cobText", cobText)
            put("cobLabel", state.cobLabel)
            put("carbsReqGrams", state.carbsRequiredGrams)
            put("carbsReqText", carbsReqText)
            put("lastLoopTime", state.lastLoopTime)
            put("loopLabel", rh.gs(CoreUiStrings.loop))
            put("mode", state.runningMode.name)
            put("modeText", state.runningModeText)
            put("modeActive", state.runningModeActive)
            put("staleText", rh.gs(UiStrings.a11y_bg_old_reading))
            put("chart", chart(state.chartPoints))
            if (withDetails) {
                put("targetText", state.tempTargetText)
                put("targetChip", state.targetChip.name)
                put("ttReason", state.tempTargetReason?.name)
                put("targetIcon", state.targetIcon.name)
                put("profileText", state.profileText)
                put("profileModified", state.profileModified)
                put("loopIcon", state.loopIcon.name)
                put("sensIcon", state.sensitivityIcon.name)
                put("sensText", state.sensitivityText)
                put("isfFromText", state.isfFromText)
                put("isfToText", state.isfToText)
                put("isfStep", state.isfStep.name)
                put("isfStepText", state.isfStepDescription)
                put("tbr", state.tbrState.name)
                put("tbrText", state.tbrDescription)
                put("iobActive", state.iobActive)
                put("cobActive", state.cobActive)
            }
        }
    }

    private fun chart(points: List<WidgetChartPoint>): JsonArray = buildJsonArray {
        for (point in points) {
            if (!point.mgdl.isFinite()) continue
            add(buildJsonArray {
                add(JsonPrimitive(point.timestamp))
                add(JsonPrimitive(point.mgdl))
            })
        }
    }

    /**
     * Null for NaN and the infinities. kotlinx writes them as bare `NaN` / `Infinity`, which is not
     * JSON: Swift's JSONDecoder refuses it and the whole update would be lost without a word.
     */
    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }
}
