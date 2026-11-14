package app.aaps.pump.omnipod.dashctl

import app.aaps.core.interfaces.resources.TextRefValueRegistry

/**
 * Tells the text resolver where the texts of [DashStrings] are.
 *
 * Every other module with strings is registered by the shells from `StringOwnerModules` (buildSrc).
 * This one is not in that list on purpose: each shell registers every owner of the list, so the
 * Android app and the desktop would have to link this module, and only the iPhone uses it. So the
 * iOS plugin registers it itself when it is built (`IosDashDriverHost`). Calling it again is
 * harmless: the registry keeps one lookup per owner.
 *
 * Only English exists here, as for every module on the platforms without a resource table.
 */
fun registerDashStrings() {
    TextRefValueRegistry.register(DASH_STRINGS_OWNER) { name, locale -> DashStringsValues.textOf(name, locale) }
}

/** The owner name the generator writes into every [DashStrings] entry (`generateDashStrings`). */
const val DASH_STRINGS_OWNER: String = "dashctl"
