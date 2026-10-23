package app.aaps.plugins.constraints.dstHelper

/**
 * The daylight saving part of the UTC offset of [zoneId] at [epochMs], in milliseconds: 0 in
 * standard time, usually one hour in summer time.
 *
 * Only the daylight saving part, not the whole offset, and on purpose: this is what Android has
 * always compared (`Calendar.DST_OFFSET`), so a zone that changes its standard time is not taken for
 * a daylight saving change. Each platform answers it with its own time zone database.
 */
internal expect fun daylightSavingPartMs(zoneId: String, epochMs: Long): Long
