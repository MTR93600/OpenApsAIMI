package app.aaps.plugins.main.skins

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class DashboardHomeVariantResolverTest {

    @Test
    fun `fully qualified skin name resolves overview`() {
        val overview = OverviewTestSkin()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = overview.javaClass.name,
            availableSkins = listOf(SkinMinimal(), SkinGlass(), overview),
            fallbackSkin = SkinGlass(),
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.OVERVIEW)
    }

    @Test
    fun `simple skin name resolves dashboard v1`() {
        val dashboardV1 = SkinMinimal()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = dashboardV1.javaClass.simpleName,
            availableSkins = listOf(OverviewTestSkin(), dashboardV1, SkinGlass()),
            fallbackSkin = OverviewTestSkin(),
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.DASHBOARD_V1)
    }

    @Test
    fun `glass skin advertises and resolves glass`() {
        val glass = SkinGlass()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = glass.javaClass.name,
            availableSkins = listOf(OverviewTestSkin(), SkinMinimal(), glass),
            fallbackSkin = OverviewTestSkin(),
        )

        assertThat(glass.dashboardHomeVariant).isEqualTo(DashboardHomeVariant.GLASS)
        assertThat(result).isEqualTo(DashboardHomeVariant.GLASS)
    }

    @Test
    fun `unknown skin uses active skin fallback`() {
        val fallback = SkinGlass()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = "missing.skin.Name",
            availableSkins = listOf(OverviewTestSkin(), SkinMinimal()),
            fallbackSkin = fallback,
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.GLASS)
    }

    private class OverviewTestSkin : SkinInterface {
        override val description: Int = 0
        override val mainGraphHeight: Int = 0
        override val secondaryGraphHeight: Int = 0
    }
}
