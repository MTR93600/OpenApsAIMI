package app.aaps.pump.omnipod.dashctl.session

/**
 * The expiration alert settings the pod was last programmed with: Android `PodState`
 * `expirationReminderEnabled`, `expirationReminderHours`, `expirationAlarmEnabled` and
 * `expirationAlarmHours`, which Android sets together (`updateExpirationAlertSettings`).
 *
 * @property reminderHours hours before the end of the pod life that the user reminder goes off.
 * @property alarmHours hours before the end of the pod life that the expiration alarm goes off.
 */
data class DashExpirationAlertSettings(
    val reminderEnabled: Boolean,
    val reminderHours: Int,
    val alarmEnabled: Boolean,
    val alarmHours: Int,
)

/**
 * The low reservoir alert settings the pod was last programmed with: Android `PodState`
 * `lowReservoirAlertEnabled` and `lowReservoirAlertUnits`, which Android sets together
 * (`updateLowReservoirAlertSettings`).
 *
 * @property units the alert goes off when this many units are left in the reservoir.
 */
data class DashLowReservoirAlertSettings(
    val enabled: Boolean,
    val units: Int,
)

/**
 * All the alert settings of the user, as Android `updateAlertConfiguration` reads them from the
 * preferences. The plugin (phase 4) builds it from the user settings.
 */
data class DashAlertSettings(
    val expiration: DashExpirationAlertSettings,
    val lowReservoir: DashLowReservoirAlertSettings,
)
