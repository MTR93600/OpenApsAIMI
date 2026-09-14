package app.aaps.plugins.main.skins

/** Deterministic skin-name resolver shared by the Compose home entry points. */
@Suppress("DEPRECATION")
object DashboardHomeVariantResolver {

    fun resolve(
        storedSkinName: String,
        availableSkins: List<SkinInterface>,
        fallbackSkin: SkinInterface,
    ): DashboardHomeVariant {
        val selectedSkin = availableSkins.firstOrNull { it.javaClass.name == storedSkinName }
            ?: availableSkins.firstOrNull { it.javaClass.simpleName == storedSkinName }
            ?: fallbackSkin
        return selectedSkin.dashboardHomeVariant
    }
}
