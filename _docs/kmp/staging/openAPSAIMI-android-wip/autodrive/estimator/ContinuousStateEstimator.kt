package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.exp
import kotlin.math.max

/**
 * 🧠 Continuous State Estimator (PSE)
 * 
 * Utilise une variante simplifiée du modèle de Bergman (Minimal Model) couplée à un
 * filtre de Kalman Étendu (EKF) ou Unscented (UKF) pour déduire les états cachés :
 * - SI (Insulin Sensitivity)
 * - Ra (Rate of Appearance des glucides)
 * 
 * Ces variables ne sont pas directement mesurables mais sont déduites de l'erreur entre
 * la prédiction de glycémie et la vraie mesure CGM.
 */
@SingleIn(AppScope::class)
class ContinuousStateEstimator @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    // Paramètres d'état internes persistants (Covariance, etc.)
    private var pSi = 0.1      // Incertitude sur la Sensibilité
    private var pRa = 1.0      // Incertitude sur l'Absorption
    private var lastSi = 1.0   // Dernier SI estimé
    private var lastRa = 0.0   // Dernier Ra estimé

    /** Instant de la dernière mise à jour, pour l'étape de prédiction de [lastRa]. */
    private var lastUpdateMs: Long = 0L

    /**
     * Identifiant du tick déjà pris en compte, ou 0 si aucun.
     *
     * Voir [updateAndPredict] : c'est ce qui garantit une seule avance par tick, quel que soit le
     * nombre de chemins de contrôle qui appellent l'estimateur.
     */
    private var lastTickId: Long = 0L

    /**
     * Nombre d'avances réelles depuis le démarrage du process.
     *
     * N'est **pas** incrémenté quand [updateAndPredict] rejoue un tick déjà pris en compte, donc il
     * compte les avances, pas les appels. Ne pas réutiliser [lastUpdateMs] à sa place : il est
     * alimenté par `System.currentTimeMillis()` alors que le tick raisonne en `dateUtil.now()`, et
     * mélanger deux horloges dans une garde est la façon la plus sûre de laisser passer un double
     * appel.
     */
    var runCount: Long = 0L
        private set

    /** Nombre d'appels rejoués sur un tick déjà pris en compte, depuis le démarrage. Diagnostic. */
    var replayedCallCount: Long = 0L
        private set

    /** Covariance du filtre fantôme, indépendante de [pRa]. */
    private var pRaShadow = 1.0

    /**
     * Ra qu'on obtiendrait si le terme insuline de l'estimateur était aligné sur celui du contrôleur,
     * en mg/dL/min.
     *
     * Calculé à chaque avance, lu **uniquement** par l'export. Il répond à la seule question encore
     * ouverte sur `InsulinActionModel.ESTIMATOR_TAU_MIN` : est-ce que l'alignement pousse vraiment Ra
     * au-dessus des portes 0,6 / 0,7 / 0,8 en permanence, ou est-ce que la projection faite à partir
     * des médianes était trop pessimiste ? Un tick réel tranche.
     */
    var lastRaAlignedTauShadow: Double = 0.0
        private set

    companion object {

        /**
         * Constante de temps de décroissance de Ra en l'absence de preuve nouvelle, en minutes.
         *
         * Sans elle, l'estimateur n'avait **pas d'étape de prédiction** : `innovation` retranchait
         * `lastRa` lui-même, donc dès que Ra expliquait la vitesse observée l'innovation tombait à
         * zéro et Ra restait figé. Mesuré en production le 07/08/2026 : Ra bloqué à 1,89 pendant
         * deux heures, puis 1,24, puis 1,18 pendant trois heures et demie — un escalier descendant
         * au lieu d'un retour au repos. Conséquence : les seuils 0,6 / 0,7 / 0,8 utilisés comme
         * portes étaient franchis sur 82 % des ticks, donc ne filtraient plus rien.
         *
         * Un taux d'apparition doit retourner à zéro quand plus rien n'est digéré. 45 minutes est
         * un premier calibrage : après 30 min sans support, Ra est divisé par deux ; après 90 min,
         * par sept. À valider sur le corpus de rejeu.
         */
        const val RA_DECAY_TAU_MIN: Double = 45.0

        /** Borne sur l'intervalle entre deux ticks, pour qu'une longue coupure n'annule pas Ra d'un coup. */
        const val RA_DECAY_MAX_DT_MIN: Double = 30.0
    }

    /**
     * Reçoit le nouvel état réel depuis la pompe/capteur et met à jour l'estimation
     * des variables physiologiques cachées (Principalement Ra = Rate of Appearance).
     * 
     * [FCL 11.0] Modification : state.estimatedSI contient DÉJÀ la super-sensibilité
     * calculée par AIMI V1 (WCycle + Heart Rate + Autosens). Le PSE va donc l'utiliser
     * comme "truth" robuste et se focaliser sur l'estimation du repas fantôme (Ra).
     */
    fun updateAndPredict(
        actualState: AutoDriveState,
        nowMs: Long = System.currentTimeMillis(),
        tickId: Long = 0L,
    ): AutoDriveState {
        // ⏱️ Une seule avance par tick, quel que soit le nombre de chemins qui appellent.
        //
        // `AutodriveEngine.tick()` est atteint depuis trois endroits : la branche Autodrive engagée,
        // le tick fantôme T3C, et `proposeBasalOnlyTbr` qui délègue lui aussi à `tick()`. Sur un tick
        // T3C avec Autodrive engagé, l'estimateur était donc appelé deux à trois fois de suite.
        //
        // Ce n'est pas anodin : entre deux appels `dtMin ≈ 0`, donc `raDecay ≈ 1` et l'étape de
        // prédiction ne sépare rien. La même observation était réappliquée, `pRa` refaisait un tour
        // de `+ qRa` puis de `(1 - kRa)` par appel, et le gain de Kalman effectif doublait ou
        // triplait. Le taux d'avance de Ra dépendait donc des préférences activées.
        //
        // La garde vit ici plutôt que chez l'appelant : déplacer un appel ne protège pas du prochain
        // site d'appel ajouté. Avec `tickId = 0` (tests, appels hors tick) le comportement reste
        // l'ancien — chaque appel avance.
        if (tickId != 0L && tickId == lastTickId) {
            replayedCallCount++
            aapsLogger.debug(
                LTag.APS,
                "👽 [PSE UKF] Tick $tickId déjà observé — Ra=${lastRa.format(2)} relu, pas de nouvelle avance."
            )
            return actualState.copy(estimatedRa = lastRa)
        }
        lastTickId = tickId
        runCount++

        // 1. Modèle Interne Prédictif (Ce qu'on PENSE qui aurait dû se passer sans repas)
        // dBG/dt = - [p1 + SI * IOB]*(BG - BG_target) + Ra
        val p1 = 0.015 // Efficacité du glucose à retourner à la basale
        val bgTarget = 100.0
        
        // Dynamique naturelle attendue en mg/dL/min: G' = -p1(G-Gb) - (SI * multiplier)*I*G + Ra
        val insulinEffect = InsulinActionModel.effectMgdlPerMin(
            isfMgdlPerU = InsulinActionModel.isfFromStateSi(actualState.estimatedSI),
            iobU = actualState.iob,
            bgMgdl = actualState.bg,
            // Not MPC_TAU_MIN — raising it here inflates Ra by nearly the same amount and would put
            // it above the 0.6 / 0.7 / 0.8 gates on any tick with insulin on board. See the constant.
            tauMin = InsulinActionModel.ESTIMATOR_TAU_MIN,
        )
        val expectedNaturalDelta = -p1 * (actualState.bg - bgTarget) - insulinEffect
        
        // 🚀 LEAD COMPENSATOR (Phase 10 - Hardware-Awareness)
        // Le capteur Dexcom G6 possède un lag matériel (lissage natif) qui écrase et retarde la dérivée.
        // Si détecté, on booste l'accélération perçue pour réagir en temps réel comme le One+
        val isG6 = actualState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE
        val hardwareCompensatedVelocity = if (isG6 && actualState.bgVelocity > 0.5) {
            actualState.bgVelocity * 1.25 // +25% lead (Total lead with orchestrator = +50%)
        } else {
            actualState.bgVelocity // Transmission directe temps réel (One+ / G7 / Libre)
        }

        // 1bis. ÉTAPE DE PRÉDICTION de Ra (elle manquait : l'état était propagé à l'identique).
        // Les glucides d'un repas s'épuisent ; en l'absence de preuve nouvelle, Ra doit revenir
        // vers zéro. Voir [RA_DECAY_TAU_MIN].
        val dtMin = if (lastUpdateMs == 0L) 5.0
        else ((nowMs - lastUpdateMs) / 60_000.0).coerceIn(0.0, RA_DECAY_MAX_DT_MIN)
        val raDecay = exp(-dtMin / RA_DECAY_TAU_MIN)
        val raPredicted = lastRa * raDecay
        lastUpdateMs = nowMs

        // 2. Erreur d'Innovation (L'écart avec la réalité : La vitesse BG réelle)
        // bgVelocity est en mg/dL/min. On retranche la PRÉDICTION de Ra, pas sa valeur précédente :
        // si le repas continue vraiment, l'innovation redevient positive et l'étape de mise à jour
        // remonte Ra ; sinon il redescend.
        val innovation = hardwareCompensatedVelocity - (expectedNaturalDelta + raPredicted)
        
        // 🚀 DAWN GUARD DAMPENING (Prevent Cortisol Over-Correction)
        // Entre 5h et 9h, si peu d'activité physique (pas de marche), on suspecte le cortisol.
        // On augmente le scepticisme (rVariance) pour ne pas sauter sur chaque variation du capteur.
        val isDawnWindow = actualState.hour in 4..10
        val isLowActivity = actualState.steps < 200
        val legacyDawn = isDawnWindow && isLowActivity && (actualState.cob < 0.1)
        val isDawnGuardActive = (actualState.physioExtendedDawnGuard && actualState.cob < 0.1) || legacyDawn

        // 🛡️ POST-HYPO RECOVERY GUARD (rescue-carbs rebound vs meal absorption)
        // Wired from DetermineBasalAIMI2: min BG < 70 in last 75 min, no meal modes / COB / recent carb estimate.
        val isHypoRecoveryGuard = actualState.applyHypoRecoveryRaDampening

        // 3. Matrice de Bruit de Processus Q & Matrice de Mesure R
        val rVariance = when {
            isDawnGuardActive -> 10.0
            isHypoRecoveryGuard -> 8.0
            else -> 2.0
        }
        
        // 🚀 DYNAMIC MANEUVER DETECTION (Phase 11 - Agile Tracking)
        // confirmation par combinedDelta si > 2.0 ou uamConfidence > 0.5
        val risingConfirmed = actualState.combinedDelta > 2.0 || actualState.uamConfidence > 0.5
        val qRa = processNoise(
            innovation = innovation,
            risingConfirmed = risingConfirmed,
            uamConfidence = actualState.uamConfidence,
            isDawnGuardActive = isDawnGuardActive,
            isHypoRecoveryGuard = isHypoRecoveryGuard,
        )

        // 4. Prédiction de Covariance (P_k|k-1)
        // Propagation linéaire cohérente avec l'étape de prédiction : F = raDecay, donc P <- F^2 P + Q.
        pRa = raDecay * raDecay * pRa + qRa

        // 5. Calcul du Gain de Kalman (K) pour Ra
        val sRa = pRa + rVariance 
        val kRa = pRa / sRa 

        // 6. Mise à jour de l'état caché (Rate of Appearance), à partir de la prédiction
        var estimatedRa = raPredicted + (kRa * innovation)

        // 7. Règles physiologiques d'Écrêtage (Clipping)
        // On limite le maximum de Ra possible (Vitesse d'absorption) pendant le Dawn Guard
        val baseMaxRa = (actualState.patientWeightKg / 10.0) * 1.5
        val inflammationRecovery = actualState.physiologicalStressMask.getOrNull(1)?.coerceIn(0.0, 1.0) ?: 0.0
        val hormonalCircadian = actualState.physiologicalStressMask.getOrNull(2)?.coerceIn(0.0, 1.0) ?: 0.0
        val weightRaFactor = (75.0 / actualState.patientWeightKg).coerceIn(0.84, 1.08)
        val physioRaFactor = (1.0 - inflammationRecovery * 0.18 - hormonalCircadian * 0.12).coerceIn(0.72, 1.02)
        val maxBiologicalRa = when {
            isHypoRecoveryGuard -> baseMaxRa * weightRaFactor * physioRaFactor * 0.3
            isDawnGuardActive -> baseMaxRa * weightRaFactor * physioRaFactor * 0.5
            else -> baseMaxRa * weightRaFactor * physioRaFactor
        }
        estimatedRa = estimatedRa.coerceIn(0.0, maxBiologicalRa)

        // Désamorçage rapide (Decay)
        if (innovation < -0.5 && hardwareCompensatedVelocity <= 0.0) {
            estimatedRa *= 0.25 
        }

        // 8. Mise à jour de la Covariance (P_k|k)
        pRa = (1 - kRa) * pRa

        // 🔎 SHADOW — le même filtre avec le terme insuline aligné sur celui du contrôleur.
        //
        // Rien d'autre ne change : même vitesse compensée, même décroissance, même bruit de mesure,
        // même écrêtage. Seul `tauMin` diffère, donc l'écart entre `lastRa` et `lastRaAlignedTauShadow`
        // mesure exactement ce que coûterait l'alignement — la seule question qui reste ouverte sur
        // `InsulinActionModel.ESTIMATOR_TAU_MIN`.
        //
        // Aucun consommateur : cette valeur n'est lue que par l'export. Elle ne doit jamais atteindre
        // une porte ni une dose tant qu'elle n'a pas été validée sur des repas réels.
        run {
            val alignedInsulinEffect = InsulinActionModel.effectMgdlPerMin(
                isfMgdlPerU = InsulinActionModel.isfFromStateSi(actualState.estimatedSI),
                iobU = actualState.iob,
                bgMgdl = actualState.bg,
                tauMin = InsulinActionModel.MPC_TAU_MIN,
            )
            val shadowExpected = -p1 * (actualState.bg - bgTarget) - alignedInsulinEffect
            val shadowPredicted = lastRaAlignedTauShadow * raDecay
            val shadowInnovation = hardwareCompensatedVelocity - (shadowExpected + shadowPredicted)
            val shadowQ = processNoise(
                innovation = shadowInnovation,
                risingConfirmed = risingConfirmed,
                uamConfidence = actualState.uamConfidence,
                isDawnGuardActive = isDawnGuardActive,
                isHypoRecoveryGuard = isHypoRecoveryGuard,
            )
            pRaShadow = raDecay * raDecay * pRaShadow + shadowQ
            val shadowGain = pRaShadow / (pRaShadow + rVariance)
            var shadowRa = shadowPredicted + shadowGain * shadowInnovation
            shadowRa = shadowRa.coerceIn(0.0, maxBiologicalRa)
            if (shadowInnovation < -0.5 && hardwareCompensatedVelocity <= 0.0) shadowRa *= 0.25
            pRaShadow = (1 - shadowGain) * pRaShadow
            lastRaAlignedTauShadow = shadowRa
        }

        // Sauvegarde d'État Externe
        lastRa = estimatedRa

        aapsLogger.debug(
            LTag.APS,
            "👽 [PSE UKF] dBG_attendu=${expectedNaturalDelta.format(2)} | dBG_vrai=${actualState.bgVelocity.format(2)} (G6?=$isG6) | Innov=${innovation.format(2)} | hypoRec=$isHypoRecoveryGuard | maxRa=${maxBiologicalRa.format(2)} || 🍽️ Ra_estimé = ${estimatedRa.format(2)} mg/dL/min"
        )

        // Renvoie l'état enrichi avec le modèle de digestion fantôme calculé
        return actualState.copy(
            estimatedRa = estimatedRa
        )
    }

    /**
     * Process noise for Ra, as a function of how surprising the observation is.
     *
     * Extracted so the shadow estimate can use the same rule with its own innovation — otherwise the
     * two would differ for a reason that has nothing to do with the insulin term being compared.
     */
    private fun processNoise(
        innovation: Double,
        risingConfirmed: Boolean,
        uamConfidence: Double,
        isDawnGuardActive: Boolean,
        isHypoRecoveryGuard: Boolean,
    ): Double {
        val baseQ = when {
            innovation > 3.0 || (innovation > 2.0 && risingConfirmed) -> 5.0   // Repas lourd confirmé
            innovation > 1.0                                          -> 0.5 + (innovation - 1.0) * 0.75
            innovation < -1.0                                         -> 1.0  // Désamorçage
            else                                                      -> 0.2
        }
        // Boost Ra si UAM détecté par le cerveau ML
        val finalBaseQ = baseQ * (if (uamConfidence > 0.7) 1.5 else 1.0)
        // Sous Dawn Guard ou post-hypo: réduire l'acceptation d'une montée comme "repas".
        return when {
            isDawnGuardActive && isHypoRecoveryGuard && innovation > 0 -> finalBaseQ * 0.25
            isHypoRecoveryGuard && innovation > 0                      -> finalBaseQ * 0.25
            isDawnGuardActive && innovation > 0                        -> finalBaseQ * 0.4
            else                                                       -> finalBaseQ
        }
    }

    fun getLastRa(): Double = lastRa

    /**
     * T9 — Compensation lag matériel G6 (Lead Compensator standalone)
     *
     * Expose la correction de délai Dexcom G6 (+25% sur la vélocité, +50% combiné avec l'orchestrateur)
     * dans un appel indépendant du pipeline Autodrive V3.
     * Utilisable depuis le chemin AIMI V2 pour que la compensation s'applique partout.
     *
     * @param velocity Vélocité BG brute du capteur (mg/dL/min)
     * @param isG6     Vrai si le capteur est un Dexcom G6 natif
     * @return Vélocité corrigée (ou identique si non-G6)
     */
    fun applyG6LeadCompensation(velocity: Double, isG6: Boolean): Double {
        return if (isG6 && velocity > 0.5) {
            val compensated = velocity * 1.25
            aapsLogger.debug(
                LTag.APS,
                "👽 [G6-Lead-V2] v_raw=${velocity.format(2)} → v_comp=${compensated.format(2)} (+25% lead corr)"
            )
            compensated
        } else {
            velocity
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
