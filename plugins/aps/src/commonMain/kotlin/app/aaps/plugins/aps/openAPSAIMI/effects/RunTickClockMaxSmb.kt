package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.UE
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.NGRConfig
import app.aaps.plugins.aps.openAPSAIMI.aimiCivilClock
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.smb.MaxSmbLadder
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class AimiCachedSmb(
    val timestamp: Long,
    val amount: Double,
)

internal data class AimiTirWarmupView(
    val tir1DayAbove: Double,
    val tir1DayInRange: Double,
    val currentTirLow: Double,
    val currentTirRange: Double,
    val currentTirAbove: Double,
    val lastHourTirLow: Double,
    val lastHourTirAbove: Double?,
    val lastHourTirLow100: Double,
    val lastHourTirAbove170: Double,
    val lastHourTirAbove120: Double,
    val tirBasal3InRange: Double?,
    val tirBasal3Below: Double?,
    val tirBasal3Above: Double?,
    val tirBasalHourAbove: Double?,
)

internal data class AimiCarbContextView(
    val lastCarbTimestamp: Long,
    val lastCarbAgeMin: Int,
    val futureCarbs: Float,
    val effectiveCob: Float,
    val recentNotes: List<UE>,
)

internal data class AimiTickClockTirCarbGlucoseBootstrap(
    val honeymoon: Boolean,
    val ngrConfig: NGRConfig,
    val tir1DAYIR: Double,
    val lastHourTIRAbove: Double?,
    val tirbasal3IR: Double?,
    val tirbasal3B: Double?,
    val tirbasal3A: Double?,
    val tirbasalhAP: Double?,
    val circadianMinute: Int,
    val circadianSecond: Int,
    val bgAcceleration: Float,
)

/** Champs lus et écrits à la ligne où la référence le fait. */
internal interface AimiTickClockState {
    fun nowMs(): Long
    fun setNow(ms: Long)
    fun setHourOfDay(hour: Int)
    fun setBg(bg: Double)
    fun bg(): Double
    fun setTickCombinedDelta(delta: Float)
    fun internalLastSmbMillis(): Long
    fun setLastBolusSmbUnit(unit: Float)
    fun setLastSmbTime(minutes: Int)
    fun pendingLegacyPrebolusUnit(): Float
    fun setPendingLegacyPrebolusUnit(unit: Float)
    fun pendingLegacyPrebolusExpiry(): Long
    fun setPendingLegacyPrebolusExpiry(ms: Long)
    fun internalLastLegacyPrebolusMillis(): Long
    fun setMaxIob(value: Double)
    fun maxIob(): Double
    fun setMaxSmb(value: Double)
    fun maxSmb(): Double
    fun setMaxSmbHb(value: Double)
    fun maxSmbHb(): Double
    fun targetBg(): Double
    fun cob(): Float
    fun setCob(value: Float)
    fun setLastSlope(value: Double?)
    fun setLastShortAvg(value: Double?)
    fun setLadderBranch(branch: String?)
    fun ladderBranch(): String?
    fun setTir1DayAbove(value: Double)
    fun setCurrentTirLow(value: Double)
    fun setCurrentTirRange(value: Double)
    fun setCurrentTirAbove(value: Double)
    fun setLastHourTirLow(value: Double)
    fun setLastHourTirLow100(value: Double)
    fun setLastHourTirAbove170(value: Double)
    fun setLastHourTirAbove120(value: Double)
    fun setWeekend(value: Int)
    fun setLastCarbAgeMin(value: Int)
    fun lastCarbAgeMin(): Int
    fun setFutureCarbs(value: Float)
    fun setRecentNotes(notes: List<UE>?)
    fun setTags0to60(value: String)
    fun setTags60to120(value: String)
    fun setTags120to180(value: String)
    fun setTags180to240(value: String)
    fun setDelta(value: Float)
    fun setShortAvgDelta(value: Float)
    fun setLongAvgDelta(value: Float)
    fun setBgAcc(value: Double)
}

internal fun interface AimiTickSmbCache {
    fun latest(): AimiCachedSmb?
}

/** Bolus depuis [sinceMs] couvrant au moins [minAmount]. Une exception est journalisée, le repli est faux. */
internal fun interface AimiLegacyPrebolusDelivered {
    fun anyMatching(sinceMs: Long, minAmount: Float): Boolean
}

internal fun interface AimiMaxIobPhrase {
    fun append(rT: RT, maxIob: Double)
}

internal fun interface AimiTirWarmupRead {
    fun latest(): AimiTirWarmupView
}

internal fun interface AimiBadDayDeletion {
    fun automate(tir1DayInRange: Int)
}

internal fun interface AimiCarbContextRead {
    fun latest(nowMs: Long, mealDataLastCarbTime: Long, cobNow: Float): AimiCarbContextView
}

internal fun interface AimiNoteTags {
    fun parse(startMinAgo: Int, endMinAgo: Int): String
}

/**
 * `runTickClockMaxSmbTirCarbAndGlucoseCopy`.
 *
 * Le `try/catch` du prébolus legacy garde le repli faux. L'échec est
 * `RBT legacyPrebolusDelivered failed …`.
 */
internal fun decideTickClockMaxSmb(
    profile: OapsProfileAimi,
    autosens: AutosensResult,
    glucoseStatus: GlucoseStatusAIMI,
    mealLastCarbTime: Long,
    mealSlope: Double,
    rT: RT,
    combinedDelta: Float,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    state: AimiTickClockState,
    smbCache: AimiTickSmbCache,
    prebolus: AimiLegacyPrebolusDelivered,
    maxIobPhrase: AimiMaxIobPhrase,
    nightGrowth: AimiNightGrowthConfig,
    tir: AimiTirWarmupRead,
    badDay: AimiBadDayDeletion,
    carbs: AimiCarbContextRead,
    notes: AimiNoteTags,
): AimiTickClockTirCarbGlucoseBootstrap {
    val civilNow = aimiCivilClock(aimiWallClockMs())
    state.setHourOfDay(civilNow.hour)
    val circadianMinute = civilNow.minute
    val circadianSecond = civilNow.second
    val dayOfWeek = civilNow.calendarDayOfWeek
    val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
    state.setBg(glucoseStatus.glucose)
    state.setTickCombinedDelta(combinedDelta)
    val cachedSmb = smbCache.latest()
    val cacheSmbTimestamp = cachedSmb?.timestamp ?: 0L
    if (cacheSmbTimestamp >= state.internalLastSmbMillis()) {
        state.setLastBolusSmbUnit(cachedSmb?.amount?.toFloat() ?: 0.0f)
        val diff = abs(state.nowMs() - cacheSmbTimestamp)
        state.setLastSmbTime((diff / (60 * 1000)).toInt())
    } else {
        val diff = abs(state.nowMs() - state.internalLastSmbMillis())
        state.setLastSmbTime((diff / (60 * 1000)).toInt())
    }
    if (state.pendingLegacyPrebolusUnit() > 0.0f && state.nowMs() > state.pendingLegacyPrebolusExpiry()) {
        state.setPendingLegacyPrebolusUnit(0.0f)
        state.setPendingLegacyPrebolusExpiry(0L)
    }
    if (state.pendingLegacyPrebolusUnit() > 0.0f && state.internalLastLegacyPrebolusMillis() > 0L) {
        val pendingUnit = state.pendingLegacyPrebolusUnit()
        val prebolusDelivered = readRbtOptional(
            source = "legacyPrebolusDelivered",
            consoleLog = consoleLog,
        ) {
            prebolus.anyMatching(state.internalLastLegacyPrebolusMillis(), pendingUnit * 0.35f)
        }.valueOrNull() ?: false
        if (prebolusDelivered) {
            consoleLog.add("🍱 PREBOLUS_DELIVERED_CONFIRMED: clearing pending ${pendingUnit}U")
            state.setPendingLegacyPrebolusUnit(0.0f)
            state.setPendingLegacyPrebolusExpiry(0L)
        }
    }
    state.setMaxIob(preferences.get(DoubleKey.ApsSmbMaxIob))
    state.setMaxIob(state.maxIob())
    maxIobPhrase.append(rT, state.maxIob())
    consoleLog.add("MAX_IOB_STATIC: Pref=${state.maxIob()} (Dynamic disabled by request)")
    state.setMaxSmb(preferences.get(DoubleKey.OApsAIMIMaxSMB))
    state.setMaxSmbHb(preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB))
    @Suppress("UNUSED_VARIABLE")
    val enableUAM = profile.enableUAM
    state.setMaxSmbHb(preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB))
    state.setLastSlope(mealSlope.takeIf { it.isFinite() })
    state.setLastShortAvg(glucoseStatus.shortAvgDelta.takeIf { it.isFinite() })
    val ladder = MaxSmbLadder.decide(
        bgMgdl = state.bg(),
        combinedDelta = combinedDelta.toDouble(),
        slopeFromMinDeviation = mealSlope,
        shortAvgDeltaMgdl5m = glucoseStatus.shortAvgDelta,
        honeymoon = honeymoon,
        maxSmb = state.maxSmb(),
        maxSmbHighBg = state.maxSmbHb(),
    )
    state.setLadderBranch(ladder.branch)
    consoleLog.add(
        when (ladder.branch) {
            MaxSmbLadder.LADDER_PLATEAU_CRITICAL ->
                "MAXSMB_PLATEAU_CRITICAL BG=${state.bg().roundToInt()} Δ=${aimiFmt1(combinedDelta)} slope=${aimiFmt2(mealSlope)} -> maxSMBHB=${aimiFmt2(state.maxSmbHb())}U (plateau)"
            MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH ->
                "MAXSMB_SLOPE_HIGH BG=${state.bg().roundToInt()} slope=${aimiFmt2(mealSlope)} Δ=${aimiFmt1(combinedDelta)} -> maxSMBHB=${aimiFmt2(state.maxSmbHb())}U (confirmed rise)"
            MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA ->
                "MAXSMB_DELTA_HIGH BG=${state.bg().roundToInt()} shortAvgDelta=${aimiFmt2(glucoseStatus.shortAvgDelta)} slope=${aimiFmt2(mealSlope)} Δ=${aimiFmt1(combinedDelta)} -> maxSMBHB=${aimiFmt2(state.maxSmbHb())}U (confirmed rise by delta)"
            MaxSmbLadder.LADDER_SENSITIVE_85 ->
                "MAXSMB_SLOPE_SENSITIVE BG=${state.bg().roundToInt()} slope=${aimiFmt2(mealSlope)} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (85% maxSMBHB - confirmed rise)"
            MaxSmbLadder.LADDER_PLATEAU_MODERATE_75 ->
                "MAXSMB_PLATEAU_MODERATE BG=${state.bg().roundToInt()} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (75% maxSMBHB)"
            MaxSmbLadder.LADDER_FALLING_60 ->
                "MAXSMB_FALLING BG=${state.bg().roundToInt()} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (60% maxSMBHB)"
            else ->
                "MAXSMB_STANDARD BG=${state.bg().roundToInt()} -> ${aimiFmt2(ladder.ceilingU)}U"
        },
    )
    state.setMaxSmb(ladder.ceilingU)
    val stdMaxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
    if (state.bg() < 120.0 && state.maxSmb() > stdMaxSMB) {
        state.setMaxSmb(stdMaxSMB)
        state.setLadderBranch(
            when (state.ladderBranch()) {
                MaxSmbLadder.LADDER_PLATEAU_CRITICAL -> MaxSmbLadder.LADDER_PLATEAU_CRITICAL_CLAMPED
                MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH -> MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_CLAMPED
                MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA ->
                    MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA_CLAMPED
                MaxSmbLadder.LADDER_SENSITIVE_85 -> MaxSmbLadder.LADDER_SENSITIVE_85_CLAMPED
                MaxSmbLadder.LADDER_PLATEAU_MODERATE_75 -> MaxSmbLadder.LADDER_PLATEAU_MODERATE_75_CLAMPED
                MaxSmbLadder.LADDER_FALLING_60 -> MaxSmbLadder.LADDER_FALLING_60_CLAMPED
                else -> MaxSmbLadder.LADDER_STANDARD
            },
        )
        consoleLog.add("🔒 STRICT CLAMP: BG<120 -> Forced Standard MaxSMB (${aimiFmt2(stdMaxSMB)}U)")
    }
    val ngrConfig = nightGrowth.buildNightGrowthResistanceConfig(profile, autosens, glucoseStatus, state.targetBg())
    val tirSnapshot = tir.latest()
    state.setTir1DayAbove(tirSnapshot.tir1DayAbove)
    val tir1DAYIR = tirSnapshot.tir1DayInRange
    state.setCurrentTirLow(tirSnapshot.currentTirLow)
    state.setCurrentTirRange(tirSnapshot.currentTirRange)
    state.setCurrentTirAbove(tirSnapshot.currentTirAbove)
    state.setLastHourTirLow(tirSnapshot.lastHourTirLow)
    val lastHourTIRAbove = tirSnapshot.lastHourTirAbove
    state.setLastHourTirLow100(tirSnapshot.lastHourTirLow100)
    state.setLastHourTirAbove170(tirSnapshot.lastHourTirAbove170)
    state.setLastHourTirAbove120(tirSnapshot.lastHourTirAbove120)
    state.setNow(aimiWallClockMs())
    badDay.automate(tir1DAYIR.toInt())
    // Calendar.SUNDAY = 1, Calendar.SATURDAY = 7, the numbering aimiCivilClock already uses.
    state.setWeekend(if (dayOfWeek == 1 || dayOfWeek == 7) 1 else 0)
    val carbSnapshot = carbs.latest(state.nowMs(), mealLastCarbTime, state.cob())
    state.setLastCarbAgeMin(carbSnapshot.lastCarbAgeMin)
    state.setFutureCarbs(carbSnapshot.futureCarbs)
    if (state.lastCarbAgeMin() < 15 && state.cob() == 0.0f) {
        state.setCob(carbSnapshot.effectiveCob)
    }
    state.setRecentNotes(carbSnapshot.recentNotes)
    state.setTags0to60(notes.parse(0, 60))
    state.setTags60to120(notes.parse(60, 120))
    state.setTags120to180(notes.parse(120, 180))
    state.setTags180to240(notes.parse(180, 240))
    state.setDelta(glucoseStatus.delta.toFloat())
    state.setShortAvgDelta(glucoseStatus.shortAvgDelta.toFloat())
    state.setLongAvgDelta(glucoseStatus.longAvgDelta.toFloat())
    val bgAcceleration = glucoseStatus.bgAcceleration.toFloat()
    state.setBgAcc(bgAcceleration.toDouble())
    return AimiTickClockTirCarbGlucoseBootstrap(
        honeymoon = honeymoon,
        ngrConfig = ngrConfig,
        tir1DAYIR = tir1DAYIR,
        lastHourTIRAbove = lastHourTIRAbove,
        tirbasal3IR = tirSnapshot.tirBasal3InRange,
        tirbasal3B = tirSnapshot.tirBasal3Below,
        tirbasal3A = tirSnapshot.tirBasal3Above,
        tirbasalhAP = tirSnapshot.tirBasalHourAbove,
        circadianMinute = circadianMinute,
        circadianSecond = circadianSecond,
        bgAcceleration = bgAcceleration,
    )
}
