package app.aaps.plugins.main.skins

import app.aaps.plugins.main.R
import javax.inject.Inject

class SkinMinimal @Inject constructor() : SkinInterface {

    override val description: Int = R.string.minimal_skin_description
    override val mainGraphHeight: Int = 180
    override val secondaryGraphHeight: Int = 0
    val useDashboardLayout: Boolean = true
}
