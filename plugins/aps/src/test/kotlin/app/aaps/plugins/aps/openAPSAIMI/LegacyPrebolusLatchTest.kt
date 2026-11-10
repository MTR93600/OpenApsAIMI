package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prouve la logique du verrou one-shot par tag prébolus ([DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks]) :
 * un tag (LUNCH_P1, …) tire UNE fois par activation, sans dépendre d'une valeur (fin du bug « P1 ne part pas »),
 * et sans double même sur capteur rapide.
 *
 * Convention: `blocks == true` ⇒ le prébolus est refusé (déjà tiré cette activation).
 */
class LegacyPrebolusLatchTest {

    private val now = 1_700_000_000_000L
    private fun min(m: Long) = m * 60_000L

    @Test
    fun `jamais tire (firedAt null) ne bloque pas - P1 part au 1er tick`() {
        // Aucun tir enregistré → autorisé, quel que soit le runtime.
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(null, now, 0))
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(null, now, 2))
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(null, now, 17))
    }

    @Test
    fun `meme activation apres tir - bloque le re-tir (pas de double)`() {
        // P1 tiré à runtime 2 (donc firedAt = now - 4min quand on ré-évalue à runtime 6). Doit bloquer.
        val firedAt = now - min(4)
        assertTrue(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(firedAt, now, 6))
    }

    @Test
    fun `capteur rapide - deux ticks dans l'onset - bloque quand meme le double`() {
        // Tir il y a 2 min, ré-évaluation à runtime 3 (capteur qui rafraîchit < 5 min). Doit bloquer.
        val firedAt = now - min(2)
        assertTrue(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(firedAt, now, 3))
    }

    @Test
    fun `nouvelle activation (runtime repart a zero) - autorise le tir`() {
        // Tir d'hier (24 h) mais nouvelle activation → runtime petit (2). (now-firedAt) ≫ fenêtre ⇒ autorisé.
        val firedAt = now - min(24 * 60)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(firedAt, now, 2))
    }

    @Test
    fun `P2 (15-24) autorise apres P1 - tir plus vieux que le runtime P2`() {
        // À runtime 17, un tir P2 antérieur daterait forcément de > 17 min si c'était une autre activation.
        // Ici on simule P2 jamais tiré cette activation via un firedAt très ancien → autorisé.
        val firedAt = now - min(60)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(firedAt, now, 17))
    }

    @Test
    fun `borne - tir exactement a la limite de la fenetre n'est plus bloque`() {
        // fenêtre = runtime*60s + 90s. À runtime 6 → 450s. Un tir à 450s pile n'est plus « même activation ».
        val firedAt = now - (min(6) + 90_000L)
        assertFalse(DetermineBasalaimiSMB2.legacyPrebolusLatchBlocks(firedAt, now, 6))
    }
}
