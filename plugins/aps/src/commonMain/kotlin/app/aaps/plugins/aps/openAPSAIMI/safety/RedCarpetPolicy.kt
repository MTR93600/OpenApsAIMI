package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * When Red Carpet may put back an SMB that the minor guards (throttle, refractory, damping) had cut.
 *
 * Only for a meal the person declared: a meal mode, carbs they entered, or a manual action. Never
 * for a meal the loop only guesses from a rise (the implicit meal context, or a "confirmed high rise").
 *
 * ## Why
 *
 * With no carbs entered and no meal mode running, every rise above 140 mg/dL could be read as a meal.
 * Red Carpet then put back what the guards had cut, even while Harmonia vetoed the meal
 * (`HARMONIA_MEAL_VETO` next to `RED_CARPET_ELIGIBLE` in the log), and it used the high-BG MaxSMB
 * whatever the BG was. On a rise at night that meant several SMBs in a row at the high ceiling while
 * the guards allowed only a fraction of it, and a low some hours later.
 *
 * A guessed meal still gets its SMB through the normal guards. It only loses the restore.
 */
object RedCarpetPolicy {

    /**
     * @param explicitUserAction the person asked for this insulin (a button, the advisor one-shot).
     * @param declaredMealMode a meal mode the person started is running.
     * @param declaredCarbsRise carbs the person entered are on board and BG rises fast with them.
     */
    fun allowed(explicitUserAction: Boolean, declaredMealMode: Boolean, declaredCarbsRise: Boolean): Boolean =
        explicitUserAction || declaredMealMode || declaredCarbsRise
}
