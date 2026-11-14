package app.aaps.pump.omnipod.dashctl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.history.DashHistoryBolusType
import app.aaps.pump.omnipod.dashctl.history.DashHistoryInitialResult
import app.aaps.pump.omnipod.dashctl.history.DashHistoryRecord
import app.aaps.pump.omnipod.dashctl.history.DashHistoryResolvedResult
import app.aaps.pump.omnipod.dashctl.history.DashHistoryType

/** The name of each history line, the texts of Android `OmnipodCommandType`. */
val DashHistoryType.label: TextRef
    get() = when (this) {
        DashHistoryType.SET_BOLUS              -> DashStrings.dash_history_set_bolus
        DashHistoryType.CANCEL_BOLUS           -> DashStrings.dash_history_cancel_bolus
        DashHistoryType.SET_TEMPORARY_BASAL    -> DashStrings.dash_history_set_temp_basal
        DashHistoryType.CANCEL_TEMPORARY_BASAL -> DashStrings.dash_history_cancel_temp_basal
        DashHistoryType.SET_BASAL_PROFILE      -> DashStrings.dash_history_set_basal_profile
        DashHistoryType.SUSPEND_DELIVERY       -> DashStrings.dash_history_suspend_delivery
        DashHistoryType.STOP_DELIVERY          -> DashStrings.dash_history_stop_delivery
        DashHistoryType.RESUME_DELIVERY        -> DashStrings.dash_history_resume_delivery
        DashHistoryType.ACKNOWLEDGE_ALERTS     -> DashStrings.dash_history_acknowledge_alerts
        DashHistoryType.CONFIGURE_ALERTS       -> DashStrings.dash_history_configure_alerts
        DashHistoryType.PLAY_TEST_BEEP         -> DashStrings.dash_history_play_test_beep
        DashHistoryType.INITIALIZE_POD         -> DashStrings.dash_history_initialize_pod
        DashHistoryType.INSERT_CANNULA         -> DashStrings.dash_history_insert_cannula
        DashHistoryType.DEACTIVATE_POD         -> DashStrings.dash_history_deactivate_pod
        DashHistoryType.DISCARD_POD            -> DashStrings.dash_history_discard_pod
    }

/**
 * Android `formatValue` of the pod history screen: why a line failed, or the bolus (with its kind) or
 * the temp basal of a line that worked. Empty for the other lines (Android shows the basal profile;
 * the iPhone history does not keep it).
 */
fun dashHistoryDescription(record: DashHistoryRecord, rh: TextResolver, format: DashOverviewFormat): String {
    if (!record.isSuccess()) {
        return rh.gs(
            when {
                record.initialResult == DashHistoryInitialResult.FAILURE_SENDING -> DashStrings.dash_history_failed_to_send
                record.initialResult == DashHistoryInitialResult.NOT_SENT        -> DashStrings.dash_history_failed_to_send
                record.resolvedResult == DashHistoryResolvedResult.FAILURE       -> DashStrings.dash_history_not_received
                else                                                             -> DashStrings.dash_history_not_confirmed
            }
        )
    }
    return when (record.type) {
        DashHistoryType.SET_BOLUS           -> record.bolusUnits?.let { units ->
            val amount = format.insulin(PumpType.OMNIPOD_DASH.determineCorrectBolusSize(units))
            when (record.bolusType) {
                DashHistoryBolusType.SMB              -> rh.gs(DashStrings.dash_history_labeled_value, amount, rh.gs(DashStrings.dash_history_smb))
                DashHistoryBolusType.BASAL_CORRECTION -> rh.gs(DashStrings.dash_history_labeled_value, amount, rh.gs(DashStrings.dash_history_basal_correction))
                else                                  -> amount
            }
        } ?: ""

        DashHistoryType.SET_TEMPORARY_BASAL -> {
            val rate = record.tempBasalRate
            val minutes = record.tempBasalMinutes
            if (rate != null && minutes != null) rh.gs(DashStrings.dash_history_tbr_value, format.basalRate(rate), minutes) else ""
        }

        else                                -> ""
    }
}

/**
 * The pod history, the counterpart of Android `OmnipodPodHistoryScreen`: one card per command of the
 * last 5 days, newest first, with a tick or an error mark, the time, and what was sent. The filter
 * chips of Android are not here.
 */
@Composable
fun DashPodHistoryScreen(records: List<DashHistoryRecord>, rh: TextResolver, format: DashOverviewFormat, modifier: Modifier = Modifier) {
    if (records.isEmpty()) {
        Text(
            text = stringResource(DashStrings.dash_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(AapsSpacing.extraLarge)
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        items(records, key = { it.createdAtMs }) { record ->
            val success = record.isSuccess()
            AapsCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(AapsSpacing.large), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (success) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = stringResource(if (success) CoreUiStrings.success else CoreUiStrings.error),
                        tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(AapsSpacing.large))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(record.type.label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = format.dateAndTime(record.createdAtMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val description = dashHistoryDescription(record, rh, format)
                        if (description.isNotEmpty()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = AapsSpacing.small)
                            )
                        }
                        record.totalAmountDelivered?.let {
                            Text(
                                text = rh.gs(DashStrings.dash_history_total_delivered, format.insulin(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = AapsSpacing.extraSmall)
                            )
                        }
                    }
                }
            }
        }
    }
}
