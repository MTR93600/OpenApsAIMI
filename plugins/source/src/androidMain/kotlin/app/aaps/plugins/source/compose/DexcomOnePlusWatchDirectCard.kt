package app.aaps.plugins.source.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.source.DexcomOnePlusFeed
import app.aaps.plugins.source.DexcomOnePlusFeedState
import app.aaps.plugins.source.DexcomOnePlusWatchReport
import app.aaps.plugins.source.SourceStrings

/**
 * Direct-to-watch on the Status screen: what the watch last said. Drawn only while the feature is on
 * (its switch is in the settings) and the Wear plugin is on.
 *
 * @param available false while no production sensor can be handed over (none started, or this phone
 *   is not paired with it). The row then says the watch waits for a sensor, instead of "Off", which
 *   read as if the feature were off.
 * @param feed where the loop's glucose comes from now; null while the watch's readings do not go to
 *   the loop, then the line is not shown.
 * @param formatTime the time of day, for when the phone lost the sensor
 */
@Composable
internal fun DexcomOnePlusWatchDirectCard(
    available: Boolean,
    report: DexcomOnePlusWatchReport?,
    feed: DexcomOnePlusFeedState? = null,
    formatTime: (Long) -> String = { it.toString() },
) {
    CgmCard {
        CgmCardHeader(stringResource(SourceStrings.dexcom_oneplus_watch_direct))
        // A plain line, not a label and a value: the card title already says what this is about,
        // and the longer states ("Pairing: accept the request on the watch") broke badly in a row.
        Text(
            text = stringResource(
                if (available) watchStateText(report?.status?.state) else SourceStrings.dexcom_oneplus_watch_state_waiting_sensor
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (feed != null) {
            val since = feed.watchOnlySinceMs
            Text(
                text = when {
                    feed.feed == DexcomOnePlusFeed.PHONE                     -> stringResource(SourceStrings.dexcom_oneplus_feed_phone)
                    feed.feed == DexcomOnePlusFeed.WATCH_ONLY && since != null ->
                        stringResource(SourceStrings.dexcom_oneplus_feed_watch_only, formatTime(since))

                    else                                                    -> stringResource(SourceStrings.dexcom_oneplus_feed_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                // Not the normal case: the loop depends on the watch, or gets nothing.
                color = if (feed.feed == DexcomOnePlusFeed.PHONE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        if (available && report?.status?.batteryRestricted == true) {
            Text(
                text = stringResource(SourceStrings.dexcom_oneplus_watch_direct_battery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The phrase for what the watch reports. Null: the watch has said nothing yet. */
internal fun watchStateText(state: EventData.CgmWatchState?): TextRef = when (state) {
    null                                    -> SourceStrings.dexcom_oneplus_watch_state_unknown
    EventData.CgmWatchState.OFF              -> SourceStrings.dexcom_oneplus_watch_state_off
    EventData.CgmWatchState.NOT_SUPPORTED    -> SourceStrings.dexcom_oneplus_watch_state_not_supported
    EventData.CgmWatchState.NEEDS_PERMISSION -> SourceStrings.dexcom_oneplus_watch_state_permission
    EventData.CgmWatchState.BLUETOOTH_OFF    -> SourceStrings.dexcom_oneplus_watch_state_bluetooth_off
    EventData.CgmWatchState.PAIRING          -> SourceStrings.dexcom_oneplus_watch_state_pairing
    EventData.CgmWatchState.WAITING          -> SourceStrings.dexcom_oneplus_watch_state_waiting
    EventData.CgmWatchState.CONNECTED        -> SourceStrings.dexcom_oneplus_watch_state_connected
    EventData.CgmWatchState.REFUSED          -> SourceStrings.dexcom_oneplus_watch_state_refused
}
