package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString

/**
 * The pump line under the Glass charts: reservoir, "pump unreachable", site age and sensor age.
 * Port of `OverviewViewModel.buildPumpAdjustmentDisplay` on `dev_OAPSAIMI`. All texts are ready to
 * show; the warning flags only choose a color.
 *
 * @property unreachableText not null while the pump is unreachable (shown in red after the reservoir)
 * @property siteAgeWarning true when the cannula is older than 3 days (DEV showed it in yellow)
 * @property sensorAgeWarning true when the sensor is older than 8 days (DEV showed it in orange)
 */
@Immutable
data class GlassPumpStatusLine(
    val reservoirText: String,
    val unreachableText: String?,
    val siteAgeText: String,
    val siteAgeWarning: Boolean,
    val sensorAgeText: String,
    val sensorAgeWarning: Boolean,
)

/** Pure rules behind [GlassPumpStatusLine]. Same values as on `dev_OAPSAIMI`. */
internal object GlassPumpStatusRules {

    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
    const val SITE_WARNING_MS = 3 * DAY_MS
    const val SENSOR_WARNING_MS = 8 * DAY_MS

    fun siteAgeWarning(ageMs: Long?): Boolean = ageMs != null && ageMs > SITE_WARNING_MS
    fun sensorAgeWarning(ageMs: Long?): Boolean = ageMs != null && ageMs > SENSOR_WARNING_MS

    /**
     * True when the "pump unreachable" alert is on and the pump has not sent data for longer than
     * the alert threshold. Same test as DEV; it does not look at the alert itself.
     */
    fun isPumpUnreachable(alertEnabled: Boolean, lastConnectionMs: Long, thresholdMinutes: Int, nowMs: Long): Boolean =
        alertEnabled && lastConnectionMs + thresholdMinutes * 60_000L < nowMs

    /** How an age is shown: the two largest units, as DEV `DateUtil.age(…, useShortText = true)`. */
    sealed interface Age {
        data class DaysHours(val days: Int, val hours: Int) : Age
        data class HoursMinutes(val hours: Int, val minutes: Int) : Age
        data class Minutes(val minutes: Int) : Age
    }

    fun age(ageMs: Long): Age {
        val ms = ageMs.coerceAtLeast(0L)
        val days = (ms / DAY_MS).toInt()
        val hours = ((ms % DAY_MS) / HOUR_MS).toInt()
        val minutes = ((ms % HOUR_MS) / 60_000L).toInt()
        return when {
            days > 0  -> Age.DaysHours(days, hours)
            hours > 0 -> Age.HoursMinutes(hours, minutes)
            else      -> Age.Minutes(minutes)
        }
    }
}

private val PLACEHOLDER = Regex("""%%|%(?:(\d+)\$)?s""")

/**
 * Fills a string template (`%1$s`, `%s`, `%%`) with styled parts. The template comes from a string
 * resource, so the translator keeps control of order and separators; only the parts carry a style.
 * A placeholder with no matching part is kept as written.
 */
internal fun formatAnnotated(template: String, args: List<AnnotatedString>): AnnotatedString = buildAnnotatedString {
    var next = 0
    var last = 0
    PLACEHOLDER.findAll(template).forEach { match ->
        append(template.substring(last, match.range.first))
        if (match.value == "%%") append('%')
        else {
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            if (index in args.indices) append(args[index]) else append(match.value)
        }
        last = match.range.last + 1
    }
    append(template.substring(last))
}
