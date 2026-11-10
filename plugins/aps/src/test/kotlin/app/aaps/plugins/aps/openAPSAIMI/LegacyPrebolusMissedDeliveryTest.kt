package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prouve la détection « prébolus demandé mais jamais confirmé par la pompe »
 * ([DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery], Option A — information seule) :
 * détecte uniquement pour l'activation courante, après le délai de confirmation
 * ([DetermineBasalaimiSMB2.LEGACY_PREBOLUS_CONFIRM_DELAY_MS], couvre le retard du cache SMB
 * asynchrone), et seulement si aucun bolus SMB confirmé pompe n'existe depuis le tir.
 *
 * Convention : `missed == true` ⇒ alerte « non délivré » justifiée.
 */
class LegacyPrebolusMissedDeliveryTest {

    private val now = 1_700_000_000_000L
    private fun min(m: Long) = m * 60_000L

    @Test
    fun `jamais tire (firedAt null) - pas d'alerte`() {
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(null, null, now, 0))
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(null, now - min(3), now, 12))
    }

    @Test
    fun `tire mais delai de confirmation pas ecoule - pas d'alerte (protege carry-forward)`() {
        // Tir il y a 5 min < 25 min de délai : le carry-forward peut encore retenter.
        val firedAt = now - min(5)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, null, now, 6))
    }

    @Test
    fun `tire, delai ecoule, aucun SMB depuis - alerte`() {
        // P1 tiré, ré-évaluation après le délai de confirmation, aucun SMB en base depuis.
        val firedAt = now - DetermineBasalaimiSMB2.LEGACY_PREBOLUS_CONFIRM_DELAY_MS - min(1)
        assertTrue(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, null, now, 28))
        // Idem avec un SMB confirmé ANTÉRIEUR au tir (vieux bolus d'un repas précédent).
        assertTrue(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, firedAt - min(120), now, 28))
    }

    @Test
    fun `tire et SMB confirme apres le tir - pas d'alerte (delivrance prouvee)`() {
        val firedAt = now - DetermineBasalaimiSMB2.LEGACY_PREBOLUS_CONFIRM_DELAY_MS - min(1)
        val smbConfirmed = firedAt + min(1)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, smbConfirmed, now, 28))
    }

    @Test
    fun `tir d'une activation precedente - pas d'alerte`() {
        // Tir d'il y a 60 min mais runtime 3 (nouvelle activation) : hors fenêtre d'activation.
        val firedAt = now - min(60)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, null, now, 3))
    }

    @Test
    fun `borne - detection possible juste apres le delai de confirmation`() {
        // Tir exactement au délai de confirmation, runtime assez grand pour rester dans l'activation.
        val firedAt = now - DetermineBasalaimiSMB2.LEGACY_PREBOLUS_CONFIRM_DELAY_MS
        assertTrue(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt, null, now, 28))
        // Une milliseconde avant le délai → pas encore.
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusMissedDelivery(firedAt + 1, null, now, 28))
    }
}
