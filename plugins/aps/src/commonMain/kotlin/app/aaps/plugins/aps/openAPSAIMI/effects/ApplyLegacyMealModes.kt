package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.basal.FclMealBasal
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority

/**
 * Tick members [applyLegacyMealModes] already holds when the reference enters the function.
 * These are not a preference snapshot: each preference is still read through [Preferences]
 * at the line the reference reads it.
 */
internal class LegacyMealTickState(
    val mealTime: Boolean,
    val mealRuntimeMin: Long,
    val bfastTime: Boolean,
    val bfastRuntimeMin: Long,
    val lunchTime: Boolean,
    val lunchRuntimeMin: Long,
    val dinnerTime: Boolean,
    val dinnerRuntimeMin: Long,
    val highCarbTime: Boolean,
    val highCarbRuntimeMin: Long,
    val snackTime: Boolean,
    val snackRuntimeMin: Long,
    val fclTime: Boolean,
    val sportTime: Boolean,
    val fclRuntimeMin: Long,
    val iob: Float,
    val maxIob: Double,
    val bg: Double,
    val hasHypoRecovery: Boolean,
    val postHypo: PostHypoDeliveryAuthority.Decision,
    var lastBolusSmbUnit: Float,
    var lastSmbCapped: Double,
    var lastSmbFinal: Double,
)

internal fun applyLegacyMealModes(
    profile: OapsProfileAimi,
    rT: RT,
    currenttemp: CurrentTemp,
    modeTbrLimit: Double,
    preferences: Preferences,
    dateUtil: DateUtil,
    texts: TextResolver,
    notifications: NotificationManager,
    effects: AimiEffectSink,
    smbAction: AimiSmbActionType,
    latestSmb: AimiLatestSmbCached,
    log: MutableList<String>,
    state: LegacyMealTickState,
): RT? {
    fun rbf(key: DoubleKey) = preferences.get(key)

    fun prebolusAlreadyFiredThisActivation(tag: String, runtimeMin: Long): Boolean =
        legacyPrebolusLatchBlocks(LegacyPrebolusMemory.firedAt[tag], dateUtil.now(), runtimeMin)

    fun markLegacyMealDecision() {
        recordSmbActionType(smbAction, if ((rT.units ?: 0.0) > 0.0) "smb" else "none")
        val units = rT.units ?: 0.0
        if (units > 0.0) {
            state.lastBolusSmbUnit = units.toFloat()
            state.lastSmbCapped = units
            state.lastSmbFinal = units
            LegacyPrebolusMemory.setLastSmbMillis(preferences, dateUtil.now())
            LegacyPrebolusMemory.setPendingUnit(preferences, units.toFloat())
            LegacyPrebolusMemory.setPendingExpiry(preferences, dateUtil.now() + LegacyPrebolusMemory.DELIVERY_TTL_MS)
            LegacyPrebolusMemory.setLastLegacyPrebolusMillis(preferences, dateUtil.now())
            LegacyPrebolusMemory.lastCarryRetryFireMillis = dateUtil.now()
        }
    }

    fun setLegacyPrebolusUnits(
        units: Double,
        logTag: String,
        runtimeMin: Long,
        onAllowed: (Double) -> Unit,
    ) {
        if (prebolusAlreadyFiredThisActivation(logTag, runtimeMin)) {
            log.add("🔒 LEGACY prebolus tag=$logTag déjà délivré cette activation — TBR seul")
            rT.units = 0.0
            return
        }
        if (state.iob > state.maxIob) {
            log.add("🛡️ LEGACY prebolus tag=$logTag: IOB ${aimiFmt2(state.iob)}U > MaxIOB — TBR seul")
            rT.units = 0.0
            return
        }
        if (state.bg <= SEVERE_HYPO_MEAL_OVERRIDE_MGDL && legacyPrebolusBlockedByPostHypo(state.postHypo, log, logTag)) {
            rT.units = 0.0
            return
        }
        if (state.hasHypoRecovery) {
            log.add("🍬 CTX_HYPO_RECOVERY: legacy prebolus suppressed tag=$logTag (was ${aimiFmt2(units)}U)")
            rT.units = 0.0
            return
        }
        effects.applySmbUnits(rT, units, "LegacyMealModes")
        rT.deliverAt = dateUtil.now()
        LegacyPrebolusMemory.firedAt[logTag] = dateUtil.now()
        onAllowed(units)
    }

    fun manualMealModeTbr(runtimeMin: Long, logTag: String, overrideSafetyLimits: Boolean) {
        if (runtimeMin < 0 || runtimeMin >= 30) return
        val rateUh = modeTbrLimit.coerceAtLeast(0.05)
        effects.setTempBasal(
            rateUh,
            30,
            profile,
            rT,
            currenttemp,
            overrideSafetyLimits = overrideSafetyLimits,
            forceExact = true,
            adaptiveMultiplier = 1.0,
        )
        log.add("MEAL_TBR_MANUAL[$logTag] rate=${aimiFmt2(rateUh)}U/h dur=30m rt=${runtimeMin}m")
    }

    val fclDeclared = FclMealBasal.declared(
        fclNoteActive = state.fclTime,
        sportNoteActive = state.sportTime,
        tempTargetSet = profile.temptargetSet,
        targetBgMgdl = profile.target_bg,
    )

    checkLegacyPrebolusDeliveryAndAlert(
        rT = rT,
        preferences = preferences,
        dateUtil = dateUtil,
        texts = texts,
        notifications = notifications,
        latestSmb = latestSmb,
        log = log,
        state = state,
    )

    if (LegacyPrebolusMemory.pendingUnit(preferences) > 0.0f &&
        dateUtil.now() < LegacyPrebolusMemory.pendingExpiry(preferences)
    ) {
        val activeModeRuntime = when {
            state.mealTime -> state.mealRuntimeMin
            state.bfastTime -> state.bfastRuntimeMin
            state.lunchTime -> state.lunchRuntimeMin
            state.dinnerTime -> state.dinnerRuntimeMin
            state.highCarbTime -> state.highCarbRuntimeMin
            state.snackTime -> state.snackRuntimeMin
            fclDeclared -> state.fclRuntimeMin
            else -> null
        }
        if (activeModeRuntime != null) {
            manualMealModeTbr(activeModeRuntime, "MAINT_PB1_PRIORITY", overrideSafetyLimits = false)
            if (state.iob > state.maxIob) {
                log.add("🛡️ LEGACY_PB1_PRIORITY_CARRY: IOB ${aimiFmt2(state.iob)}U > MaxIOB — TBR seul")
                rT.units = 0.0
                return rT
            }
            val timeSinceLastCarryRetry = dateUtil.now() - LegacyPrebolusMemory.lastCarryRetryFireMillis
            val carryOnCooldown = LegacyPrebolusMemory.lastCarryRetryFireMillis > 0L &&
                timeSinceLastCarryRetry < LegacyPrebolusMemory.CARRY_RETRY_COOLDOWN_MS
            if (!carryOnCooldown) {
                effects.applySmbUnits(rT, LegacyPrebolusMemory.pendingUnit(preferences).toDouble(), "LegacyPrebolus")
                rT.deliverAt = dateUtil.now()
                LegacyPrebolusMemory.lastCarryRetryFireMillis = dateUtil.now()
                log.add(
                    "🍱 LEGACY_PB1_PRIORITY_CARRY: re-propose ${LegacyPrebolusMemory.pendingUnit(preferences)}U (non confirmé en base)",
                )
            } else {
                log.add("⏳ LEGACY_PB1_PRIORITY_CARRY: cooldown (retry il y a ${timeSinceLastCarryRetry / 1000}s)")
            }
            return rT
        }
    }

    if (state.mealRuntimeMin in 0..7 && state.mealTime) {
        manualMealModeTbr(state.mealRuntimeMin, "MEAL_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIMealPrebolus), "MEAL_P1", state.mealRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.manual_meal_prebolus, u))
            log.add("🍱 LEGACY_MODE_MEAL P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.bfastRuntimeMin in 0..7 && state.bfastTime) {
        manualMealModeTbr(state.bfastRuntimeMin, "BF_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIBFPrebolus), "BF_P1", state.bfastRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_bfast1, u))
            log.add("🍱 LEGACY_MODE_BFAST P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.bfastRuntimeMin in 15..29 && state.bfastTime) {
        manualMealModeTbr(state.bfastRuntimeMin, "BF_P2", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIBFPrebolus2), "BF_P2", state.bfastRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_bfast2, u))
            log.add("🍱 LEGACY_MODE_BFAST P2=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.lunchRuntimeMin in 0..7 && state.lunchTime) {
        manualMealModeTbr(state.lunchRuntimeMin, "LUNCH_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMILunchPrebolus), "LUNCH_P1", state.lunchRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_lunch1, u))
            log.add("🍱 LEGACY_MODE_LUNCH P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.lunchRuntimeMin in 15..24 && state.lunchTime) {
        manualMealModeTbr(state.lunchRuntimeMin, "LUNCH_P2", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMILunchPrebolus2), "LUNCH_P2", state.lunchRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_lunch2, u))
            log.add("🍱 LEGACY_MODE_LUNCH P2=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.dinnerRuntimeMin in 0..7 && state.dinnerTime) {
        manualMealModeTbr(state.dinnerRuntimeMin, "DINNER_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIDinnerPrebolus), "DINNER_P1", state.dinnerRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_dinner1, u))
            log.add("🍱 LEGACY_MODE_DINNER P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.dinnerRuntimeMin in 15..24 && state.dinnerTime) {
        manualMealModeTbr(state.dinnerRuntimeMin, "DINNER_P2", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIDinnerPrebolus2), "DINNER_P2", state.dinnerRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_dinner2, u))
            log.add("🍱 LEGACY_MODE_DINNER P2=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.highCarbRuntimeMin in 0..7 && state.highCarbTime) {
        manualMealModeTbr(state.highCarbRuntimeMin, "HC_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIHighCarbPrebolus), "HC_P1", state.highCarbRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_highcarb, u))
            log.add("🍱 LEGACY_MODE_HIGHCARB P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.highCarbRuntimeMin in 15..23 && state.highCarbTime) {
        manualMealModeTbr(state.highCarbRuntimeMin, "HC_P2", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIHighCarbPrebolus2), "HC_P2", state.highCarbRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_highcarb, u))
            log.add("🍱 LEGACY_MODE_HIGHCARB P2=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (state.snackRuntimeMin in 0..7 && state.snackTime) {
        manualMealModeTbr(state.snackRuntimeMin, "SNACK_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMISnackPrebolus), "SNACK_P1", state.snackRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.reason_prebolus_snack, u))
            log.add("🍱 LEGACY_MODE_SNACK P1=${aimiFmt2(u)}U")
        }
        markLegacyMealDecision()
        return rT
    }
    if (fclDeclared && state.fclRuntimeMin in 0..7 && !prebolusAlreadyFiredThisActivation("FCL_P1", state.fclRuntimeMin)) {
        manualMealModeTbr(state.fclRuntimeMin, "FCL_P1", overrideSafetyLimits = false)
        setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIautodrivePrebolus), "FCL_P1", state.fclRuntimeMin) { u ->
            rT.reason.append(texts.gs(ApsStrings.fcl_prebolus, u))
            log.add("🍽️ FCL_PREBOLUS P1=${aimiFmt2(u)}U rt=${state.fclRuntimeMin}m")
        }
        markLegacyMealDecision()
        return rT
    }

    val legacyMealMaint = when {
        state.mealTime && state.mealRuntimeMin in 0..29 -> state.mealRuntimeMin to "MEAL_MAINT"
        state.bfastTime && state.bfastRuntimeMin in 0..29 -> state.bfastRuntimeMin to "BF_MAINT"
        state.lunchTime && state.lunchRuntimeMin in 0..29 -> state.lunchRuntimeMin to "LUNCH_MAINT"
        state.dinnerTime && state.dinnerRuntimeMin in 0..29 -> state.dinnerRuntimeMin to "DINNER_MAINT"
        state.highCarbTime && state.highCarbRuntimeMin in 0..29 -> state.highCarbRuntimeMin to "HC_MAINT"
        state.snackTime && state.snackRuntimeMin in 0..29 -> state.snackRuntimeMin to "SNACK_MAINT"
        else -> null
    }
    if (legacyMealMaint != null) {
        manualMealModeTbr(legacyMealMaint.first, legacyMealMaint.second, overrideSafetyLimits = false)
        rT.units = null
        log.add("🍱 LEGACY_MEAL_TBR_MAINT[${legacyMealMaint.second}] rt=${legacyMealMaint.first}m (no prebolus this tick)")
        return rT
    }
    return null
}

private fun legacyPrebolusBlockedByPostHypo(
    postHypo: PostHypoDeliveryAuthority.Decision,
    log: MutableList<String>,
    logTag: String,
): Boolean {
    if (!postHypo.active || !postHypo.suppressMealDelivery) return false
    log.add(PostHypoDeliveryAuthority.formatLogLine(postHypo))
    log.add("${PostHypoDeliveryAuthority.LOG_PREFIX}: legacy_prebolus_blocked tag=$logTag")
    return true
}

private fun checkLegacyPrebolusDeliveryAndAlert(
    rT: RT,
    preferences: Preferences,
    dateUtil: DateUtil,
    texts: TextResolver,
    notifications: NotificationManager,
    latestSmb: AimiLatestSmbCached,
    log: MutableList<String>,
    state: LegacyMealTickState,
) {
    val activeModeTags: Pair<Long, List<Pair<String, DoubleKey>>> = when {
        state.mealTime && state.mealRuntimeMin in 0..29 -> state.mealRuntimeMin to listOf(
            "MEAL_P1" to DoubleKey.OApsAIMIMealPrebolus,
        )
        state.bfastTime && state.bfastRuntimeMin in 0..29 -> state.bfastRuntimeMin to listOf(
            "BF_P1" to DoubleKey.OApsAIMIBFPrebolus,
            "BF_P2" to DoubleKey.OApsAIMIBFPrebolus2,
        )
        state.lunchTime && state.lunchRuntimeMin in 0..29 -> state.lunchRuntimeMin to listOf(
            "LUNCH_P1" to DoubleKey.OApsAIMILunchPrebolus,
            "LUNCH_P2" to DoubleKey.OApsAIMILunchPrebolus2,
        )
        state.dinnerTime && state.dinnerRuntimeMin in 0..29 -> state.dinnerRuntimeMin to listOf(
            "DINNER_P1" to DoubleKey.OApsAIMIDinnerPrebolus,
            "DINNER_P2" to DoubleKey.OApsAIMIDinnerPrebolus2,
        )
        state.highCarbTime && state.highCarbRuntimeMin in 0..29 -> state.highCarbRuntimeMin to listOf(
            "HC_P1" to DoubleKey.OApsAIMIHighCarbPrebolus,
            "HC_P2" to DoubleKey.OApsAIMIHighCarbPrebolus2,
        )
        state.snackTime && state.snackRuntimeMin in 0..29 -> state.snackRuntimeMin to listOf(
            "SNACK_P1" to DoubleKey.OApsAIMISnackPrebolus,
        )
        else -> return
    }
    val (runtimeMin, tags) = activeModeTags
    val lastSmbConfirmedMs = latestSmb.latestSmbCached()
    val now = dateUtil.now()
    for ((tag, prefKey) in tags) {
        val firedAt = LegacyPrebolusMemory.firedAt[tag] ?: continue
        if (legacyPrebolusLatchBlocks(LegacyPrebolusMemory.missAlertedAt[tag], now, runtimeMin)) continue
        if (!legacyPrebolusMissedDelivery(firedAt, lastSmbConfirmedMs, now, runtimeMin)) continue
        LegacyPrebolusMemory.missAlertedAt[tag] = now
        val requestedU = preferences.get(prefKey)
        val msg = texts.gs(
            ApsStrings.aimi_prebolus_not_delivered,
            tag,
            texts.gs(InterfacesStrings.format_insulin_units, requestedU),
        )
        log.add(
            "⚠️ PREBOLUS_NOT_DELIVERED tag=$tag requested=${aimiFmt2(requestedU)}U " +
                "firedAt=${dateUtil.timeString(firedAt)} lastSmbConfirmed=${lastSmbConfirmedMs?.let { dateUtil.timeString(it) } ?: "none"}",
        )
        rT.reason.append(" | ").append(msg)
        try {
            notifications.post(
                id = NotificationId.AUTOMATION_MESSAGE,
                text = msg,
            )
        } catch (_: Exception) {
        }
    }
}
