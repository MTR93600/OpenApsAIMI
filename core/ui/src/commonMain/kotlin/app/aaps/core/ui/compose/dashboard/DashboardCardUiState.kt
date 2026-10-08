package app.aaps.core.ui.compose.dashboard

import app.aaps.core.interfaces.notifications.Notification

/**
 * Plain UI state for the dashboard cards ported from `plugins/main`.
 *
 * The Android `DashboardEmbeddedComposeState` (mutable state holder) and
 * `AdjustmentCardState` / `NotificationStore.NotificationComposeItem` are
 * replaced by these immutable data classes so the cards stay in commonMain.
 * The platform maps its own state onto these.
 */

/** State for [DashboardAdjustmentComposeCard]. */
data class DashboardAdjustmentCardUiState(
    val glycemiaLine: String,
    val predictionLine: String,
    val iobActivityLine: String,
    val decisionLine: String,
    val modeLine: String? = null,
    val pumpReservoirPlain: String = "",
    val pumpSitePlain: String = "",
    val pumpSensorPlain: String = "",
    val safetyLine: String = "",
    val adjustments: List<String> = emptyList(),
    val onOpenAdjustmentDetails: (() -> Unit)? = null,
    val onRunLoopRequested: (() -> Unit)? = null,
)

/** State for [DashboardGraphComposeControls]. */
data class DashboardGraphControlsUiState(
    val selectedRangeHours: Int,
    val onSelectRange: ((Int) -> Unit)? = null,
)

/** A single notification row for [DashboardNotificationsComposeList]. */
data class DashboardNotificationUiItem(
    val id: Int,
    val text: String,
    val dismissText: String = "",
    /** One of [Notification.URGENT], [Notification.NORMAL], [Notification.LOW], etc. */
    val level: Int = Notification.NORMAL,
)

/** Resolved strings for the dashboard cards; the platform supplies them. */
data class DashboardCardStrings(
    val unavailableShort: String,
    val adjustmentsTitle: String,
    val pumpReservoir: String,
    val pumpReservoirA11y: String,
    val pumpSite: String,
    val pumpSiteA11y: String,
    val pumpSensor: String,
    val pumpSensorA11y: String,
    val noAdjustments: String,
    val runLoop: String,
    val statsButton: String,
    val range6h: String,
    val range12h: String,
    val range18h: String,
    val range24h: String,
    val treatmentsLabel: String,
    val notificationsCompactHeader: String,
    val snooze: String,
    val stagingTitleWarmup: String,
    val stagingWarmupCountdown: String,
    val stagingTitleSettling: String,
    val stagingReadings: String,
    val stagingReady: String,
    val stagingPromoteButton: String,
)
