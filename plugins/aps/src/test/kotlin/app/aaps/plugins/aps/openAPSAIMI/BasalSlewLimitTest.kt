package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Prouve le limiteur de pente montante de la basale ([DetermineBasalaimiSMB2.slewLimitBasalUp], anti-whiplash).
 * Règle : la HAUSSE par tick est bornée à `max(1.5 U/h, +100 % du taux courant, +2× basale profil)` ; la BAISSE
 * n'est jamais limitée (sécurité LGS).
 */
class BasalSlewLimitTest {

    private fun slew(prev: Double, proposed: Double, profile: Double) =
        DetermineBasalaimiSMB2.slewLimitBasalUp(prev, proposed, profile)

    @Test
    fun `descente jamais limitee - securite LGS instantanee`() {
        // Coupure franche 5 → 0 : passe telle quelle.
        assertEquals(0.0, slew(5.0, 0.0, 0.6), 1e-9)
        assertEquals(1.0, slew(5.0, 1.0, 0.6), 1e-9)
    }

    @Test
    fun `depuis zero - plafonne a la hausse mini absolue`() {
        // prev=0, profil 0.5 → maxIncrease = max(1.5, 0, 1.0) = 1.5 → 0 + 1.5.
        assertEquals(1.5, slew(0.0, 7.0, 0.5), 1e-9)
    }

    @Test
    fun `hausse plafonnee au doublement du taux courant`() {
        // prev=2, profil 0.5 → maxIncrease = max(1.5, 2.0, 1.0) = 2.0 → 2 + 2.
        assertEquals(4.0, slew(2.0, 10.0, 0.5), 1e-9)
    }

    @Test
    fun `proposition sous la limite - inchangee`() {
        // prev=2, cap = 2 + 2 = 4 ; proposé 3 < 4 → inchangé.
        assertEquals(3.0, slew(2.0, 3.0, 0.5), 1e-9)
    }

    @Test
    fun `gros profil - headroom proportionnel au profil`() {
        // profil 3.0 → maxIncrease = max(1.5, 1.0, 6.0) = 6.0 → 1 + 6 = 7 (≤ proposé 10).
        assertEquals(7.0, slew(1.0, 10.0, 3.0), 1e-9)
    }

    @Test
    fun `taux precedent non fini - pas de limitation`() {
        // prev NaN (pas de temp fiable) → on rend la proposition telle quelle.
        assertEquals(5.0, slew(Double.NaN, 5.0, 0.5), 1e-9)
    }
}
