package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * TAP-G site branch (RFC G.3): bounded extra peak delay from cannula age.
 */
object TapSitePeakShift {

    fun minutesForSiteAge(siteAgeDays: Float): Double {
        if (siteAgeDays < 2f) return 0.0
        return ((siteAgeDays - 2f) * 0.45).toDouble().coerceIn(0.0, 5.0)
    }
}
