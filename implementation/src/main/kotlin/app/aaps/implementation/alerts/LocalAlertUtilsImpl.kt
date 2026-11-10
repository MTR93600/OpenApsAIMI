package app.aaps.implementation.alerts

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.glucose.GlucoseCorrection
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.ui.R
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Created by adrian on 17/12/17.
 */
@Singleton
class LocalAlertUtilsImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val smsCommunicator: SmsCommunicator,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val notificationManager: NotificationManager,
    private val glucoseCorrection: GlucoseCorrection,
    @ApplicationScope private val appScope: CoroutineScope
) : LocalAlertUtils {

    init {
        preferences.registerPreferences(LocalAlertLongKey::class.java)
    }

    private fun missedReadingsThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsStaleDataThreshold).toLong()).msecs()
    }

    private fun pumpUnreachableThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
    }

    override fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean) {
        val alarmTimeoutExpired = isAlarmTimeoutExpired(lastConnection, pumpUnreachableThreshold())
        val nextAlarmOccurrenceReached = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()
        if (config.APS && isStatusOutdated && alarmTimeoutExpired && nextAlarmOccurrenceReached && !isDisconnected) {
            if (preferences.get(BooleanKey.AlertPumpUnreachable)) {
                aapsLogger.debug(LTag.CORE, "Generating pump unreachable alarm. lastConnection: " + dateUtil.dateAndTimeString(lastConnection) + " isStatusOutdated: true")
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + pumpUnreachableThreshold())
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, R.string.pump_unreachable, soundRes = R.raw.alarm)
                if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS)
                    appScope.launch {
                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE.asAnnouncement(rh.gs(R.string.pump_unreachable)),
                            timestamp = dateUtil.now(),
                            action = Action.CAREPORTAL,
                            source = Sources.Aaps,
                            note = rh.gs(R.string.pump_unreachable),
                            listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                        )
                    }
            }
            if (preferences.get(BooleanKey.SmsReportPumpUnreachable))
                smsCommunicator.sendNotificationToAllNumbers(rh.gs(R.string.pump_unreachable))
        }
        if (!isStatusOutdated && !alarmTimeoutExpired) notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
    }

    private fun isAlarmTimeoutExpired(lastConnection: Long, unreachableThreshold: Long): Boolean {
        return if (activePlugin.activePump.pumpDescription.hasCustomUnreachableAlertCheck) {
            activePlugin.activePump.isUnreachableAlertTimeoutExceeded(unreachableThreshold)
        } else {
            lastConnection + pumpUnreachableThreshold() < dateUtil.now()
        }
    }

    /*Pre-snoozes the alarms with 5 minutes if no snooze exists.
     * Call only at startup!
     */
    override fun preSnoozeAlarms() {
        if (preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
        if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
    }

    override fun shortenSnoozeInterval() { //shortens alarm times in case of setting changes or future data
        var nextMissedReadingsAlarm = preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)
        nextMissedReadingsAlarm = min(dateUtil.now() + missedReadingsThreshold(), nextMissedReadingsAlarm)
        preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, nextMissedReadingsAlarm)
        var nextPumpDisconnectedAlarm = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)
        nextPumpDisconnectedAlarm = min(dateUtil.now() + pumpUnreachableThreshold(), nextPumpDisconnectedAlarm)
        preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, nextPumpDisconnectedAlarm)
    }

    override suspend fun reportPumpStatusRead() {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (profile != null) {
            val lastConnection = pump.lastDataTime.value
            val earliestAlarmTime = lastConnection + pumpUnreachableThreshold()
            if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < earliestAlarmTime) {
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, earliestAlarmTime)
            }
        }
    }

    override suspend fun checkStaleBGAlert() {
        val bgReading = persistenceLayer.getLastGlucoseValue() ?: return
        if (preferences.get(BooleanKey.AlertMissedBgReading)
            && bgReading.timestamp + missedReadingsThreshold() < dateUtil.now()
            && preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()
        ) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + missedReadingsThreshold())
            notificationManager.post(NotificationId.BG_READINGS_MISSED, R.string.missed_bg_readings, soundRes = R.raw.alarm)
            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
                appScope.launch {
                    persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE.asAnnouncement(rh.gs(R.string.missed_bg_readings)),
                        timestamp = dateUtil.now(),
                        action = Action.CAREPORTAL,
                        source = Sources.Aaps,
                        note = rh.gs(R.string.missed_bg_readings),
                        listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                    )
                }
            }
        } else if (dateUtil.isOlderThan(bgReading.timestamp, 5).not()) {
            notificationManager.dismiss(NotificationId.BG_READINGS_MISSED)
        }
    }

    /**
     * Alarm threshold in mg/dL, the unit every glucose value in this class is expressed in.
     *
     * MUST stay [Preferences.getRaw]: `Preferences.get(UnitDoublePreferenceKey)` converts to the
     * user's display units, so on an mmol/L phone a 250 mg/dL hyper threshold came back as 13.9 and
     * was compared against mg/dL values. Every reading then looked "high" (alarm every 30 min at any
     * glucose), while the hypo threshold came back as 3.9 and no reading could ever reach it — the
     * hypo alarm never fired at all. mg/dL users saw nothing, because for them the conversion is
     * the identity.
     */
    private fun thresholdMgdl(key: UnitDoubleKey): Double = preferences.getRaw(key)

    /**
     * Value the app shows for a stored reading.
     *
     * MUST go through [GlucoseCorrection]: the alarms used to compare the plain sensor value from the
     * database, while the dashboard, the widget, the watch and the loop all work on the calibrated and
     * smoothed value. So the alarm could announce "Low glucose: 56" while the screen was showing 65.
     * On `null` (no corrected series yet, or a correction that failed its plausibility check) the
     * stored value is kept, so a broken correction can never silence a real alarm.
     */
    private fun shownValue(gv: GV): Double = glucoseCorrection.correctedMgdl(gv.timestamp, gv.value) ?: gv.value

    override suspend fun checkGlucoseAlerts() {
        val last = persistenceLayer.getLastGlucoseValue() ?: return
        val now = dateUtil.now()
        // Freshness guard: never alarm on stale data — the stale-data alarm owns that case.
        if (last.timestamp + missedReadingsThreshold() < now) return

        val bgMgdl = shownValue(last)
        checkHypoAlert(bgMgdl, now)
        checkHyperAlert(bgMgdl, now)
        checkRapidFallAlert(now)
    }

    private fun checkHypoAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertHypo)) return
        val threshold = thresholdMgdl(UnitDoubleKey.AlertHypoThreshold)
        if (bgMgdl <= threshold) {
            if (preferences.get(LocalAlertLongKey.NextHypoAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextHypoAlarm, now + HYPO_REALARM_MS)
                notificationManager.post(
                    NotificationId.BG_HYPO,
                    R.string.alert_hypo_message,
                    profileUtil.fromMgdlToStringWithUnits(bgMgdl),
                    soundRes = R.raw.alarm,
                    actions = listOf(
                        // "Treated": the user ate carbs, so hold the alarm long enough for them to
                        // work instead of re-alarming after the shorter HYPO_REALARM_MS.
                        NotificationAction(R.string.alert_hypo_treated) { snoozeHypoAfterTreatment() },
                        // Plain snooze, unchanged: silence this alarm without claiming a treatment.
                        NotificationAction(R.string.snooze) { }
                    )
                )
            }
        } else if (bgMgdl >= threshold + HYPO_HYSTERESIS_MGDL) {
            notificationManager.dismiss(NotificationId.BG_HYPO)
            preferences.put(LocalAlertLongKey.NextHypoAlarm, 0L)
        }
    }

    /**
     * "Hypo treated" action: hold the hypo alarm for [HYPO_TREATED_SNOOZE_MS] — about the time carbs
     * need to raise glucose — and clear the current alert.
     *
     * Only silences: the alarm re-arms by itself once the snooze runs out and glucose is still low,
     * and the recovery branch of [checkHypoAlert] clears the snooze as soon as glucose is back above
     * the threshold.
     */
    override fun snoozeHypoAfterTreatment() {
        preferences.put(LocalAlertLongKey.NextHypoAlarm, dateUtil.now() + HYPO_TREATED_SNOOZE_MS)
        notificationManager.dismiss(NotificationId.BG_HYPO)
        aapsLogger.debug(LTag.CORE, "Hypo alarm snoozed after treatment for ${T.msecs(HYPO_TREATED_SNOOZE_MS).mins()} min")
    }

    private fun checkHyperAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertHyper)) return
        val threshold = thresholdMgdl(UnitDoubleKey.AlertHyperThreshold)
        if (bgMgdl >= threshold) {
            if (preferences.get(LocalAlertLongKey.NextHyperAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextHyperAlarm, now + HYPER_REALARM_MS)
                notificationManager.post(NotificationId.BG_HYPER, R.string.alert_hyper_message, profileUtil.fromMgdlToStringWithUnits(bgMgdl))
            }
        } else if (bgMgdl <= threshold - HYPER_HYSTERESIS_MGDL) {
            notificationManager.dismiss(NotificationId.BG_HYPER)
            preferences.put(LocalAlertLongKey.NextHyperAlarm, 0L)
        }
    }

    private suspend fun checkRapidFallAlert(now: Long) {
        if (!preferences.get(BooleanKey.AlertRapidFall)) return
        val windowMinutes = preferences.get(IntKey.AlertRapidFallWindow)
        val readings = persistenceLayer.getBgReadingsDataFromTime(now - T.mins(windowMinutes.toLong()).msecs(), ascending = true)
        // Need at least two readings spanning the window; otherwise a data gap makes the slope meaningless.
        // The gap check reads the stored readings on purpose: the corrected series fills every five
        // minute slot, so it has no gap left to find. Only the values are taken from the corrected
        // series, so the drop matches the curve the user sees and single noisy readings cannot fake one.
        if (readings.size < 2) return
        val drop = shownValue(readings.first()) - shownValue(readings.last())
        val dropThreshold = thresholdMgdl(UnitDoubleKey.AlertRapidFallDrop)
        if (drop >= dropThreshold) {
            if (preferences.get(LocalAlertLongKey.NextRapidFallAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextRapidFallAlarm, now + RAPID_FALL_REALARM_MS)
                notificationManager.post(
                    NotificationId.BG_RAPID_FALL,
                    R.string.alert_rapid_fall_message,
                    profileUtil.fromMgdlToStringWithUnits(drop),
                    windowMinutes,
                    soundRes = R.raw.alarm
                )
            }
        } else if (drop <= dropThreshold / 2.0) {
            // Slope has flattened — clear and re-arm for the next fall episode.
            notificationManager.dismiss(NotificationId.BG_RAPID_FALL)
            preferences.put(LocalAlertLongKey.NextRapidFallAlarm, 0L)
        }
    }

    companion object {

        // Re-alarm throttles: while a condition persists, re-post at most this often (anti-spam).
        private val HYPO_REALARM_MS = T.mins(15).msecs()

        /** Hold after the user reported treating a hypo — roughly how long carbs need to work. */
        private val HYPO_TREATED_SNOOZE_MS = T.mins(20).msecs()

        private val HYPER_REALARM_MS = T.mins(30).msecs()
        private val RAPID_FALL_REALARM_MS = T.mins(15).msecs()

        // Hysteresis bands (mg/dL): dismiss only once the value has clearly left the alarm zone.
        private const val HYPO_HYSTERESIS_MGDL = 5.0
        private const val HYPER_HYSTERESIS_MGDL = 10.0
    }
}