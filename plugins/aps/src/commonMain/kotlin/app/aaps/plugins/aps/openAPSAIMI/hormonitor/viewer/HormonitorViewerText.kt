package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDateFormatter

/**
 * Portable date/time text for the Hormonitor viewer: the shaping logic without any
 * platform date API. The screen supplies the platform [AimiDateFormatter].
 */
internal fun shortDayText(dayLocal: String, fmt: AimiDateFormatter): String {
    val parsed = fmt.parse(dayLocal, "yyyy-MM-dd") ?: return dayLocal
    return fmt.format(parsed, "EEE d MMM")
}

internal fun timeSpanText(first: Long?, last: Long?, fmt: AimiDateFormatter): String {
    if (first == null || last == null) return "—"
    return "${fmt.format(first, "HH:mm")} – ${fmt.format(last, "HH:mm")}"
}
