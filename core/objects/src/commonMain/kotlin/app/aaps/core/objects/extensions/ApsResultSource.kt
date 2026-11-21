package app.aaps.core.objects.extensions

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData

/**
 * The APS result a screen should show.
 *
 * On a phone that loops, the caller passes the value it already showed. That value is returned as it is.
 * On a client the loop is forced off, so `loop.lastRun` is empty: the master's `openaps.suggested` is used
 * instead, and only while it is younger than [CLIENT_MAX_AGE_MS]. Up to [CLIENT_FUTURE_SLACK_MS] of
 * clock skew ahead is still shown. If a newer `suggested` could not be decoded, the previous result is not shown.
 */
object ApsResultSource {

    /** Same bound as the Essential home: two missed loop runs, then the master's target is not current. */
    const val CLIENT_MAX_AGE_MS = 15L * 60L * 1000L

    /** A master clock this far ahead of the client is still current. */
    const val CLIENT_FUTURE_SLACK_MS = 60_000L

    /**
     * One call reads the device status once. [ProcessedDeviceStatusData.getAPSResult] allocates a new
     * result on every call.
     */
    fun displayed(
        config: Config,
        deviceStatus: ProcessedDeviceStatusData,
        nowMs: Long,
        local: () -> APSResult?,
    ): APSResult? = when {
        config.AAPSCLIENT -> {
            val open = deviceStatus.openAPSData
            val ts = deviceStatus.openApsTimestamp
            val unreadable = open.clockSuggested > open.clockSuggestedDecoded
            if (unreadable || ts <= 0L || nowMs - ts !in -CLIENT_FUTURE_SLACK_MS..CLIENT_MAX_AGE_MS) null else deviceStatus.getAPSResult()
        }

        else              -> local()
    }

    /** The target the APS used, in mg/dL, or null when it is missing, zero or not a real number. */
    fun targetMgdl(
        config: Config,
        deviceStatus: ProcessedDeviceStatusData,
        nowMs: Long,
        local: () -> Double?,
    ): Double? =
        if (config.AAPSCLIENT) displayed(config, deviceStatus, nowMs) { null }?.targetBG?.takeIf { it.isFinite() && it > 0.0 }
        else local()

    /**
     * The IOB a screen should show.
     *
     * On a phone that loops, [local] is returned and the device status is not read.
     * On a client, `openaps.iob.displayed` is used while the master's suggested is still current,
     * with the same age rules as [displayed]. A missing, bad or old value falls back to [local].
     */
    fun displayedIob(
        config: Config,
        deviceStatus: ProcessedDeviceStatusData,
        nowMs: Long,
        local: Double,
    ): Double {
        if (!config.AAPSCLIENT) return local
        val open = deviceStatus.openAPSData
        val remote = open.displayedIob?.takeIf { it.isFinite() } ?: return local
        val ts = deviceStatus.openApsTimestamp
        val unreadable = open.clockSuggested > open.clockSuggestedDecoded
        if (unreadable || ts <= 0L || nowMs - ts !in -CLIENT_FUTURE_SLACK_MS..CLIENT_MAX_AGE_MS) return local
        return remote
    }
}
