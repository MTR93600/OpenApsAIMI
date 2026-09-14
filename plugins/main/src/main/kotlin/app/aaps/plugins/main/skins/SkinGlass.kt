package app.aaps.plugins.main.skins

import app.aaps.plugins.main.R
import javax.inject.Inject

/** Opt-in skin for the ported Glass Compose dashboard. */
class SkinGlass @Inject constructor() : SkinInterface {

    override val description: Int = R.string.glass_skin_description
    override val mainGraphHeight: Int = 180
    override val secondaryGraphHeight: Int = 0
    override val dashboardHomeVariant: DashboardHomeVariant get() = DashboardHomeVariant.GLASS
}
