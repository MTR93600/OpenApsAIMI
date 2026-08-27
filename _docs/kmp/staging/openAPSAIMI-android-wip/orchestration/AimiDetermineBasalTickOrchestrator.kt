package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2

/**
 * Point d’entrée documenté **P3b** : délègue au plugin pour conserver l’accès aux helpers `private` du tick.
 * Ordre des effets : § **Carte P3a** dans `AIMI_ORCHESTRATION_ROADMAP.md` (même séquence que [DetermineBasalaimiSMB2.runDetermineBasalTick]).
 *
 * @see DetermineBasalaimiSMB2.determine_basal
 */
object AimiDetermineBasalTickOrchestrator {

    fun run(plugin: DetermineBasalaimiSMB2, ctx: AimiTickContext): RT =
        plugin.runDetermineBasalTick(ctx)
}
