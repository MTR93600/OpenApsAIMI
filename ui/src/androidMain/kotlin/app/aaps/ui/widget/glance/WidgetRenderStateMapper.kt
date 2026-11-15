package app.aaps.ui.widget.glance

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.IsfStep
import app.aaps.core.ui.compose.DarkGeneralColors
import app.aaps.core.ui.compose.loopColor
import app.aaps.core.ui.compose.ttReasonColor
import app.aaps.ui.R
import app.aaps.ui.widget.directionToDrawableRes
import app.aaps.ui.widget.state.WidgetBgRange
import app.aaps.ui.widget.state.WidgetLoopIcon
import app.aaps.ui.widget.state.WidgetSensitivityIcon
import app.aaps.ui.widget.state.WidgetState
import app.aaps.ui.widget.state.WidgetTargetChip
import app.aaps.ui.widget.state.WidgetTargetIcon
import app.aaps.ui.widget.state.WidgetTbrState

/**
 * Maps the shared [WidgetState] to the Android colours and drawables. The only place where the widget
 * classes turn into `@ColorInt` and `@DrawableRes` values.
 *
 * The reading keeps the colour of its range when it is old, as it always did on Android: an old
 * reading is struck through instead. `WidgetState.bgColorClass(now)` is for platforms that grey it out.
 */
fun WidgetState.toRenderState(@ColorInt backgroundColor: Int): WidgetRenderState =
    WidgetRenderState(
        bgText = bgText,
        bgColor = bgRange.toColor(),
        strikeThrough = isStale,
        arrowResId = bgMgdl?.let { (trendArrow ?: TrendArrow.FLAT).directionToDrawableRes() },
        trendArrow = trendArrow,
        deltaText = deltaText,
        timeAgoText = timeAgoText,
        iobText = iobText,
        iobActive = iobActive,
        cobText = cobText,
        cobActive = cobActive,
        carbsRequired = carbsRequired,
        tempTargetText = tempTargetText,
        tempTargetColor = targetChipColor(),
        tempTargetActive = targetChip == WidgetTargetChip.TEMP || targetChip == WidgetTargetChip.ADJUSTED,
        tempTargetIconResId = targetIcon.toDrawable(),
        profileText = profileText,
        profileModified = profileModified,
        profileIconResId = R.drawable.ic_widget_profile,
        iobIconResId = R.drawable.ic_widget_bolus,
        cobIconResId = R.drawable.ic_widget_carbs,
        runningModeText = runningModeText,
        runningModeColor = runningMode.loopColor(DarkGeneralColors).toArgb(),
        runningModeIconResId = loopIcon.toDrawable(),
        runningModeActive = runningModeActive,
        sensitivityIconResId = sensitivityIcon.toDrawable(),
        sensitivityText = sensitivityText,
        isfFromText = isfFromText,
        isfToText = isfToText,
        isfStepIconResId = isfStep.toDrawable(),
        isfStepDescription = isfStepDescription,
        tbrIconResId = tbrState.toDrawable(),
        backgroundColor = backgroundColor,
        arrowDescription = arrowDescription,
        tbrDescription = tbrDescription,
        iobLabel = iobLabel,
        cobLabel = cobLabel,
        bgSpoken = bgSpoken
    )

@ColorInt
internal fun WidgetBgRange.toColor(): Int = when (this) {
    WidgetBgRange.LOW      -> BgGraphColors.WIDGET.low
    WidgetBgRange.HIGH     -> BgGraphColors.WIDGET.high
    WidgetBgRange.IN_RANGE -> BgGraphColors.WIDGET.inRange
}

@ColorInt
private fun WidgetState.targetChipColor(): Int = when (targetChip) {
    WidgetTargetChip.TEMP     -> tempTargetReason.ttReasonColor(DarkGeneralColors).toArgb()
    WidgetTargetChip.ADJUSTED -> DarkGeneralColors.adjusted.toArgb()
    WidgetTargetChip.PROFILE,
    WidgetTargetChip.NONE     -> WidgetTextMuted
}

@DrawableRes
private fun WidgetTargetIcon.toDrawable(): Int = when (this) {
    WidgetTargetIcon.PROFILE_TARGET -> R.drawable.ic_widget_target
    WidgetTargetIcon.EATING_SOON    -> R.drawable.ic_widget_tt_eating_soon
    WidgetTargetIcon.ACTIVITY       -> R.drawable.ic_widget_tt_activity
    WidgetTargetIcon.HYPOGLYCEMIA   -> R.drawable.ic_widget_tt_hypo
    WidgetTargetIcon.MANUAL         -> R.drawable.ic_widget_tt_manual
}

@DrawableRes
private fun WidgetSensitivityIcon.toDrawable(): Int = when (this) {
    WidgetSensitivityIcon.ABOVE     -> R.drawable.ic_widget_as_above
    WidgetSensitivityIcon.BELOW     -> R.drawable.ic_widget_as_below
    WidgetSensitivityIcon.EQUAL     -> R.drawable.ic_widget_as_equal
    WidgetSensitivityIcon.OFF_ABOVE -> R.drawable.ic_widget_as_x_above
    WidgetSensitivityIcon.OFF_BELOW -> R.drawable.ic_widget_as_x_below
    WidgetSensitivityIcon.OFF_EQUAL -> R.drawable.ic_widget_as_x_equal
}

@DrawableRes
private fun IsfStep.toDrawable(): Int = when (this) {
    IsfStep.UP   -> R.drawable.ic_widget_arrow_fortyfiveup
    IsfStep.DOWN -> R.drawable.ic_widget_arrow_fortyfivedown
    IsfStep.SAME -> R.drawable.ic_widget_arrow_flat
}

@DrawableRes
private fun WidgetTbrState.toDrawable(): Int = when (this) {
    WidgetTbrState.NONE          -> R.drawable.ic_widget_no_tbr
    WidgetTbrState.ABOVE_PROFILE -> R.drawable.ic_widget_tbr_high
    WidgetTbrState.BELOW_PROFILE -> R.drawable.ic_widget_tbr_low
}

@DrawableRes
private fun WidgetLoopIcon.toDrawable(): Int = when (this) {
    WidgetLoopIcon.OPEN         -> R.drawable.ic_widget_loop_open
    WidgetLoopIcon.CLOSED       -> R.drawable.ic_widget_loop_closed
    WidgetLoopIcon.LGS          -> R.drawable.ic_widget_loop_lgs
    WidgetLoopIcon.DISABLED     -> R.drawable.ic_widget_loop_disabled
    WidgetLoopIcon.SUPER_BOLUS  -> R.drawable.ic_widget_loop_superbolus
    WidgetLoopIcon.DISCONNECTED -> R.drawable.ic_widget_loop_disconnected
    WidgetLoopIcon.PAUSED       -> R.drawable.ic_widget_loop_paused
}
