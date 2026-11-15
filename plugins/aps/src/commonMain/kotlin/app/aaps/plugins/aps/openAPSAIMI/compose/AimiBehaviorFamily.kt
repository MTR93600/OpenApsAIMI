package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

/**
 * The five families of AIMI settings. The manifest gives every setting one family; the Expert area
 * groups its pages by family and the audit screen tags each row with it (step S2).
 *
 * The families were the sliders of the Control Center. That screen and its ladders are gone, so a
 * family is only a group now: no family level is read or written anywhere.
 */
internal enum class AimiBehaviorFamilyId {
    Protection,
    MealCapture,
    Stability,
    Physio,
    Autonomy,
}

/** Title of a family. */
internal fun AimiBehaviorFamilyId.title(): TextRef =
    when (this) {
        AimiBehaviorFamilyId.Protection  -> ApsStrings.aimi_control_center_protection_title
        AimiBehaviorFamilyId.MealCapture -> ApsStrings.aimi_control_center_meal_title
        AimiBehaviorFamilyId.Stability   -> ApsStrings.aimi_control_center_stability_title
        AimiBehaviorFamilyId.Physio      -> ApsStrings.aimi_control_center_physio_title
        AimiBehaviorFamilyId.Autonomy    -> ApsStrings.aimi_control_center_autonomy_title
    }
