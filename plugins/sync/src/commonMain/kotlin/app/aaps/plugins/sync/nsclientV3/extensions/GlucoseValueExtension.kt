package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.xdrip.XdripSourceResolver
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits

fun NSSgvV3.toGV(): GV {
    return GV(
        timestamp = date ?: throw IllegalArgumentException(),
        value = sgv,
        noise = noise,
        raw = filtered,
        trendArrow = TrendArrow.fromString(direction?.nsName),
        ids = IDs(nightscoutId = identifier),
        // A Nightscout record may have been uploaded by xDrip, whose device field carries a prefix.
        sourceSensor = XdripSourceResolver.fromNightscoutDevice(device),
        isValid = isValid,
        utcOffset = T.mins(utcOffset ?: 0L).msecs()
    )
}

/**
 * @param sgvOverride value the app really shows for this reading (calibrated and smoothed), or `null`
 *   when there is none. Without it Nightscout, and every follower reading from it, would show the plain
 *   sensor value while the phone shows a corrected one. The sensor value is then kept in `unfiltered`,
 *   the Nightscout field meant for it, so nothing is lost.
 */
fun GV.toNSSvgV3(sgvOverride: Double? = null): NSSgvV3 =
    NSSgvV3(
        isValid = isValid,
        date = timestamp,
        utcOffset = T.msecs(utcOffset).mins(),
        filtered = raw,
        unfiltered = if (sgvOverride != null) value else 0.0,
        sgv = sgvOverride ?: value,
        units = NsUnits.MG_DL,
        direction = Direction.fromString(trendArrow.text),
        noise = noise,
        device = sourceSensor.text,
        identifier = ids.nightscoutId
    )
