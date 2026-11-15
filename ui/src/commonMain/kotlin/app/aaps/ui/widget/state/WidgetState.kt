package app.aaps.ui.widget.state

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.IsfStep

/**
 * What every widget shows, on every platform: the Android home screen widgets, the iOS Live Activity
 * and later the iOS widgets.
 *
 * It holds no platform colour and no platform icon. A colour is a class ([WidgetBgRange],
 * [WidgetTargetChip], [runningMode]) and an icon is a kind ([WidgetTargetIcon],
 * [WidgetSensitivityIcon], [WidgetTbrState], [WidgetLoopIcon]). Each platform maps them to its own
 * colours and pictures. On Android that is `WidgetRenderState`.
 *
 * The texts are finished and translated. The numbers next to them are for a platform that draws its
 * own layout (the Live Activity): they are raw values, glucose always in mg/dL, with [units] to say
 * how to show them.
 *
 * Safety: an old reading must never look fresh. [isStale] is judged only once, when the state is
 * built. A reader that shows the state later (a Live Activity on the lock screen, an iOS widget) must
 * judge the age again at the time of showing: use [isStaleAt] and [bgColorClass] with the current
 * time, and [staleAt] as the moment the reading turns old. They use the same 9 minute rule as the app
 * ([WidgetStateRules.isStale]), so no platform needs its own copy of the rule.
 *
 * Texts are frozen at build time too. [timeAgoText] stops counting when the app stops, so a later
 * reader shows the age from [bgTimestamp] instead (on iOS for example `Text(date, style: .relative)`).
 */
data class WidgetState(
    // ---- Glucose ----
    /** The reading in the user's units, or the "no value" text. */
    val bgText: String,
    /** The newest reading in mg/dL. Null when there is no reading at all. */
    val bgMgdl: Double?,
    /** Time of the newest reading (epoch ms). Null without a reading. */
    val bgTimestamp: Long?,
    val units: GlucoseUnit,
    /** Low, in range or high against the overview marks. Says nothing about the age. */
    val bgRange: WidgetBgRange,
    /**
     * True when the reading was too old to trust, or there was none, at the time the state was built.
     * The Android screen strikes it through. A reader that shows the state later uses [isStaleAt].
     */
    val isStale: Boolean,
    /** The trend from the calculation. Null when unknown, also while a reading exists. */
    val trendArrow: TrendArrow?,
    /** Spoken name of the arrow. Null without a reading. */
    val arrowDescription: String?,
    /** The reading as spoken. Adds "old reading" when [isStale]. */
    val bgSpoken: String,
    val deltaText: String,
    /** Change over the last 5 minutes in mg/dL. Null when unknown. */
    val deltaMgdl: Double?,
    /** "3 min ago", frozen at build time. A later reader shows the age from [bgTimestamp]. */
    val timeAgoText: String,

    // ---- Insulin and carbs ----
    val iobText: String,
    /** Bolus IOB plus basal IOB in U, rounded to 0.001. */
    val iob: Double,
    val iobActive: Boolean,
    val iobLabel: String,
    /**
     * COB text for the Android widgets. Has a second line when the loop asks for carbs. A platform
     * that draws its own layout uses [cob], [carbsRequired] and [carbsRequiredGrams] instead.
     */
    val cobText: String,
    /** Carbs on board in g. Null when unknown. */
    val cob: Double?,
    val cobActive: Boolean,
    val cobLabel: String,
    /** The loop asks for carbs. The only COB state that colours its chip. */
    val carbsRequired: Boolean,
    /** Grams the loop asks for. 0 when it asks for none. */
    val carbsRequiredGrams: Int,

    // ---- Target ----
    val tempTargetText: String,
    val targetChip: WidgetTargetChip,
    /** Reason of the running temp target. Null when none runs. Gives the chip its colour. */
    val tempTargetReason: TT.Reason?,
    val targetIcon: WidgetTargetIcon,

    // ---- Profile ----
    val profileText: String,
    val profileModified: Boolean,

    // ---- Loop ----
    val runningMode: RM.Mode,
    val runningModeText: String,
    val loopIcon: WidgetLoopIcon,
    /** Any mode other than closed loop asks for attention. */
    val runningModeActive: Boolean,
    /** Time of the last loop run (epoch ms). Null when this phone does not run the loop. */
    val lastLoopTime: Long?,

    // ---- Sensitivity, the same values as the overview chip ----
    val sensitivityIcon: WidgetSensitivityIcon,
    /** The autosens percentage, empty at 100 %. */
    val sensitivityText: String,
    /** The profile ISF, empty when the algorithm uses it unchanged. */
    val isfFromText: String,
    /** The ISF in use, empty when the algorithm uses the profile ISF unchanged. */
    val isfToText: String,
    val isfStep: IsfStep,
    /** Spoken meaning of the step. Null when the two values are the same. */
    val isfStepDescription: String?,

    // ---- Temp basal ----
    val tbrState: WidgetTbrState,
    val tbrDescription: String,

    // ---- Chart ----
    /** Recent readings, oldest first. Empty unless the caller asked for them. */
    val chartPoints: List<WidgetChartPoint>
) {

    /** Time (epoch ms) from which the reading is too old to trust. Null without a reading. */
    val staleAt: Long?
        get() = bgTimestamp?.let { it + WidgetStateRules.STALE_AFTER_MS }

    /** True when the reading is too old at [now], or was already when the state was built, or there is none. */
    fun isStaleAt(now: Long): Boolean = isStale || WidgetStateRules.isStale(bgTimestamp, now)

    /**
     * The colour class of the reading when it is shown at [now]. An old reading, or none, is
     * [WidgetBgColorClass.STALE], whatever the range.
     */
    fun bgColorClass(now: Long): WidgetBgColorClass =
        WidgetStateRules.bgColorClass(bgRange, isStaleAt(now), bgMgdl != null)
}

/** Where the reading is against the overview low and high marks. */
enum class WidgetBgRange { LOW, IN_RANGE, HIGH }

/** The colour class a platform paints the reading with. */
enum class WidgetBgColorClass { LOW, IN_RANGE, HIGH, STALE }

/** What the target chip shows, and so how it is coloured. */
enum class WidgetTargetChip {

    /** No profile, so no target at all. */
    NONE,

    /** The profile target. Needs no attention. */
    PROFILE,

    /** The loop works to another target than the profile one. */
    ADJUSTED,

    /** A temp target runs. Its colour comes from [WidgetState.tempTargetReason]. */
    TEMP
}

/** Picture on the target chip. */
enum class WidgetTargetIcon { PROFILE_TARGET, EATING_SOON, ACTIVITY, HYPOGLYCEMIA, MANUAL }

/** Picture on the sensitivity chip: the autosens direction, crossed out when autosens is off. */
enum class WidgetSensitivityIcon { ABOVE, BELOW, EQUAL, OFF_ABOVE, OFF_BELOW, OFF_EQUAL }

/** Temp basal against the profile basal. */
enum class WidgetTbrState { NONE, ABOVE_PROFILE, BELOW_PROFILE }

/** Picture for the running mode. Several modes share one picture. */
enum class WidgetLoopIcon { OPEN, CLOSED, LGS, DISABLED, SUPER_BOLUS, DISCONNECTED, PAUSED }

/** One reading on the small chart. Glucose in mg/dL. */
data class WidgetChartPoint(val timestamp: Long, val mgdl: Double)
