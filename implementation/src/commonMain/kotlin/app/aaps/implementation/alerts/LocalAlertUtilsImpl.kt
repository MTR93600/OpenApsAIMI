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
import app.aaps.core.interfaces.glucose.GlucoseCorrection
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.ui.CoreUiStrings
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * Created by adrian on 17/12/17.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class LocalAlertUtilsImpl(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val smsCommunicator: SmsCommunicator,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val notificationManager: NotificationManager,
    private val glucoseCorrection: GlucoseCorrection,
    private val appScope: CoroutineScope
) : LocalAlertUtils {

    init {
        preferences.registerPreferences(LocalAlertLongKey.entries)
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
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, CoreUiStrings.pump_unreachable, sound = AlarmSound.ALARM)
                if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS)
                    appScope.launch {
                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE.asAnnouncement(rh.gs(CoreUiStrings.pump_unreachable)),
                            timestamp = dateUtil.now(),
                            action = Action.CAREPORTAL,
                            source = Sources.Aaps,
                            note = rh.gs(CoreUiStrings.pump_unreachable),
                            listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                        )
                    }
            }
            if (preferences.get(BooleanKey.SmsReportPumpUnreachable))
                smsCommunicator.sendNotificationToAllNumbers(rh.gs(CoreUiStrings.pump_unreachable))
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
            notificationManager.post(NotificationId.BG_READINGS_MISSED, CoreUiStrings.missed_bg_readings, sound = AlarmSound.ALARM)
            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
                appScope.launch {
                    persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE.asAnnouncement(rh.gs(CoreUiStrings.missed_bg_readings)),
                        timestamp = dateUtil.now(),
                        action = Action.CAREPORTAL,
                        source = Sources.Aaps,
                        note = rh.gs(CoreUiStrings.missed_bg_readings),
                        listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                    )
                }
            }
        } else if (dateUtil.isOlderThan(bgReading.timestamp, 5).not()) {
            notificationManager.dismiss(NotificationId.BG_READINGS_MISSED)
        }
    }

    /**
     * Alarm threshold in mg/dL, the unit of every glucose value in this class.
     *
     * MUST stay [Preferences.getRaw]: `Preferences.get(UnitDoublePreferenceKey)` converts to the
     * user's display units. On an mmol/L phone a 250 mg/dL high threshold came back as 13.9 and was
     * compared with mg/dL values, so every reading looked "high" (an alarm every 30 min at any
     * glucose). The low threshold came back as 3.9, which no reading can reach, so the low alarm
     * never fired at all. mg/dL users saw nothing, because for them the conversion changes nothing.
     */
    private fun thresholdMgdl(key: UnitDoubleKey): Double = preferences.getRaw(key)

    /**
     * Value the app shows for a stored reading.
     *
     * MUST go through [GlucoseCorrection]: the alarms used to compare the plain sensor value from the
     * database, while the dashboard, the widget, the watch and the loop all work on the calibrated and
     * smoothed value. So the alarm could say "Low glucose: 56" while the screen showed 65. On `null`
     * (no corrected series yet, or a correction that failed its plausibility check) the stored value
     * is kept, so a broken correction can never silence a real alarm.
     */
    private fun shownValue(gv: GV): Double = glucoseCorrection.correctedMgdl(gv.timestamp, gv.value) ?: gv.value

    /**
     * Two callers can run this at the same time: the periodic pass and the "new reading" event.
     * Without the lock both could read the same "next alarm" time before either writes it, and post
     * the same alarm twice. [snoozeHypoAfterTreatment] takes it too.
     */
    private val glucoseAlertsLock = Mutex()

    override suspend fun checkGlucoseAlerts() = glucoseAlertsLock.withLock {
        val last = persistenceLayer.getLastGlucoseValue() ?: return@withLock
        val now = dateUtil.now()
        // Never alarm on stale data: the stale-data alarm owns that case.
        if (last.timestamp + missedReadingsThreshold() < now) return@withLock

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
                    CoreUiStrings.alert_hypo_message.withArgs(profileUtil.fromMgdlToStringWithUnits(bgMgdl)),
                    sound = AlarmSound.ALARM,
                    actions = listOf(
                        // "Treated": the user took carbs, so hold the alarm long enough for them to
                        // work, instead of alarming again after the shorter HYPO_REALARM_MS.
                        NotificationAction(CoreUiStrings.alert_hypo_treated) { snoozeHypoAfterTreatment() },
                        // Plain snooze: silence this alarm without saying it was treated.
                        NotificationAction(CoreUiStrings.snooze) { }
                    )
                )
            }
        } else if (bgMgdl >= threshold + HYPO_HYSTERESIS_MGDL) {
            notificationManager.dismiss(NotificationId.BG_HYPO)
            preferences.put(LocalAlertLongKey.NextHypoAlarm, 0L)
        }
    }

    /**
     * "Hypo treated" action: hold the low alarm for [HYPO_TREATED_SNOOZE_MS] (about the time carbs
     * need to raise glucose) and clear the current alarm.
     *
     * This only silences. The alarm comes back by itself when the hold runs out and glucose is still
     * low, and the recovery branch of [checkHypoAlert] clears the hold as soon as glucose is back
     * above the threshold.
     *
     * Runs under [glucoseAlertsLock], on the app scope because the caller (a notification tap) cannot
     * wait. Without the lock a check that is running at the moment of the tap could write its shorter
     * "next alarm" time after this one, and the alarm would come back after 15 minutes instead of 20.
     */
    override fun snoozeHypoAfterTreatment() {
        appScope.launch {
            glucoseAlertsLock.withLock {
                preferences.put(LocalAlertLongKey.NextHypoAlarm, dateUtil.now() + HYPO_TREATED_SNOOZE_MS)
                notificationManager.dismiss(NotificationId.BG_HYPO)
                aapsLogger.debug(LTag.CORE, "Hypo alarm snoozed after treatment for ${T.msecs(HYPO_TREATED_SNOOZE_MS).mins()} min")
            }
        }
    }

    private fun checkHyperAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertHyper)) return
        val threshold = thresholdMgdl(UnitDoubleKey.AlertHyperThreshold)
        if (bgMgdl >= threshold) {
            if (preferences.get(LocalAlertLongKey.NextHyperAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextHyperAlarm, now + HYPER_REALARM_MS)
                notificationManager.post(
                    NotificationId.BG_HYPER,
                    CoreUiStrings.alert_hyper_message.withArgs(profileUtil.fromMgdlToStringWithUnits(bgMgdl))
                )
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
        // Need at least two readings in the window, otherwise a data gap makes the slope meaningless.
        // The gap check reads the stored readings on purpose: a corrected series fills every five
        // minute slot, so it has no gap left to find. Only the values go through shownValue, so the
        // drop matches the curve the user sees.
        if (readings.size < 2) return
        val drop = shownValue(readings.first()) - shownValue(readings.last())
        // Always mg/dL, for both unit settings: the drop is a difference, not a glucose level.
        val dropThreshold = preferences.get(IntKey.AlertRapidFallDrop).toDouble()
        if (drop >= dropThreshold) {
            if (preferences.get(LocalAlertLongKey.NextRapidFallAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextRapidFallAlarm, now + RAPID_FALL_REALARM_MS)
                notificationManager.post(
                    NotificationId.BG_RAPID_FALL,
                    CoreUiStrings.alert_rapid_fall_message.withArgs(profileUtil.fromMgdlToStringWithUnits(drop), windowMinutes),
                    sound = AlarmSound.ALARM
                )
            }
        } else if (drop <= dropThreshold / 2.0) {
            // The fall has flattened: clear and re-arm for the next fall.
            notificationManager.dismiss(NotificationId.BG_RAPID_FALL)
            preferences.put(LocalAlertLongKey.NextRapidFallAlarm, 0L)
        }
    }

    companion object {

        // While a condition lasts, post the same alarm again at most this often.
        private val HYPO_REALARM_MS = T.mins(15).msecs()

        /** Hold after the user said they treated a hypo: about how long carbs need to work. */
        private val HYPO_TREATED_SNOOZE_MS = T.mins(20).msecs()

        private val HYPER_REALARM_MS = T.mins(30).msecs()
        private val RAPID_FALL_REALARM_MS = T.mins(15).msecs()

        // Hysteresis (mg/dL): dismiss only once the value has clearly left the alarm zone.
        private const val HYPO_HYSTERESIS_MGDL = 5.0
        private const val HYPER_HYSTERESIS_MGDL = 10.0
    }
}