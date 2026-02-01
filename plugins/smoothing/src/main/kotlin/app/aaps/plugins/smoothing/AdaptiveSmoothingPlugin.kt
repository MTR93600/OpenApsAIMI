package app.aaps.plugins.smoothing

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smoothing.Smoothing
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ADAPTIVE SMOOTHING PLUGIN
 * 
 * Algorithme de lissage intelligent qui adapte dynamiquement son comportement
 * en fonction du contexte glycémique :
 * - Type de montée/descente (rapide vs lente)
 * - Niveau de variabilité du capteur (CV%)
 * - Zone glycémique (hypo, cible, hyper)
 * 
 * Objectif : Minimiser le lag tout en filtrant le bruit capteur
 * 
 * @author Lyra - Senior++ Kotlin & Product Expert
 */
@Singleton
class AdaptiveSmoothingPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val iobCobCalculator: IobCobCalculator,
    private val preferences: Preferences
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.SMOOTHING)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_timeline_24)
        .pluginName(R.string.adaptive_smoothing_name)
        .shortName(R.string.smoothing_shortname)
        .description(R.string.description_adaptive_smoothing),
    aapsLogger, rh
), Smoothing {

    /**
     * Contexte glycémique calculé à partir des données récentes
     */
    private data class GlycemicContext(
        val delta: Double,              // mg/dL/5min - tendance linéaire
        val acceleration: Double,       // dérivée seconde - courbure
        val cv: Double,                 // % - coefficient de variation (stabilité)
        val zone: GlycemicZone,         // zone glycémique actuelle
        val currentBg: Double,          // mg/dL - glycémie actuelle
        val sensorNoise: Double,        // estimation du bruit capteur
        val iob: Double,                // U - Insuline active (Contextual Validation)
        val isNight: Boolean            // Mode nuit actif
    )

    /**
     * Zones glycémiques pour adaptation du comportement
     */
    private enum class GlycemicZone {
        HYPO,       // < 70 mg/dL - Sécurité critique
        LOW_NORMAL, // 70-90 mg/dL - Prudence
        TARGET,     // 90-180 mg/dL - Normal
        HYPER       // > 180 mg/dL - Contrôle actif
    }

    /**
     * Modes de lissage adaptatifs
     */
    private enum class SmoothingMode {
        COMPRESSION_BLOCK, // 🛑 ARTIFACT: Chute Impossible (Capteur écrasé)
        RAPID_RISE,     // Montée rapide : lissage minimal pour réactivité max
        RAPID_FALL,     // Descente rapide : lissage asymétrique (sécurité hypo)
        STABLE,         // Stable : lissage standard
        NOISY,          // Bruit élevé : lissage agressif
        HYPO_SAFE       // Hypo : pas de lissage (données brutes)
    }

    override fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
        if (data.size < 4) {
            aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Not enough values (${data.size}), skipping")
            return data
        }

        // 1. Calculer le contexte glycémique
        val context = calculateGlycemicContext(data)

        // 2. Déterminer le mode de lissage adaptatif
        val mode = determineMode(context)

        aapsLogger.info(
            LTag.GLUCOSE,
            "AdaptiveSmoothing: Mode=$mode | BG=${context.currentBg.toInt()} | Δ=${String.format("%.1f", context.delta)} | " +
                "IOB=${String.format("%.1f", context.iob)} | Night=${context.isNight} | " +
                "Zone=${context.zone}"
        )

        // 3. Appliquer le lissage contextualisé
        return when (mode) {
            SmoothingMode.COMPRESSION_BLOCK -> applyCompressionProtection(data, context)
            SmoothingMode.RAPID_RISE -> applyMinimalSmoothing(data, context)
            SmoothingMode.RAPID_FALL -> applyAsymmetricSmoothing(data, context)
            SmoothingMode.STABLE -> applyStandardSmoothing(data, context)
            SmoothingMode.NOISY -> applyAggressiveSmoothing(data, context)
            SmoothingMode.HYPO_SAFE -> applyNoSmoothing(data)
        }
    }

    /**
     * Calcule le contexte glycémique à partir des données récentes
     */
    private fun calculateGlycemicContext(data: List<InMemoryGlucoseValue>): GlycemicContext {
        val recentValues = data.take(3.coerceAtMost(data.size)) // 15 dernières minutes max

        // Glycémie actuelle
        val currentBg = recentValues[0].value

        // Delta moyen (mg/dL/5min)
        val avgDelta = if (recentValues.size >= 3) {
            (recentValues[0].value - recentValues[2].value) / 2.0 * 5.0
        } else {
            0.0
        }

        // Accélération (dérivée seconde)
        val acceleration = if (recentValues.size >= 3) {
            recentValues[0].value - 2 * recentValues[1].value + recentValues[2].value
        } else {
            0.0
        }

        // Coefficient de variation (stabilité)
        val mean = recentValues.map { it.value }.average()
        val variance = recentValues.map { (it.value - mean).pow(2) }.average()
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) (stdDev / mean) * 100.0 else 0.0

        // Zone glycémique
        val zone = when {
            currentBg < 70 -> GlycemicZone.HYPO
            currentBg < 90 -> GlycemicZone.LOW_NORMAL
            currentBg < 180 -> GlycemicZone.TARGET
            else -> GlycemicZone.HYPER
        }

        // Estimation du bruit capteur (modèle Dexcom : ~10% du BG)
        val sensorNoise = currentBg * 0.10

        // Contextes Injectés (Safety)
        // Calculate simplistic total IOB (Bolus + TBR) for safety check
        val bolusIob = iobCobCalculator.calculateIobFromBolus().iob
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().iob
        val iob = bolusIob + basalIob
        
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = preferences.get(BooleanKey.OApsAIMInight) == true || (hour < 7 || hour >= 23)

        return GlycemicContext(
            delta = avgDelta,
            acceleration = acceleration,
            cv = cv,
            zone = zone,
            currentBg = currentBg,
            sensorNoise = sensorNoise,
            iob = iob.toDouble(),
            isNight = isNight
        )
    }

    /**
     * Détermine le mode de lissage adaptatif en fonction du contexte
     */
    private fun determineMode(context: GlycemicContext): SmoothingMode = when {
        
        // 🛑 COMPRESSION PROTECTION (Smoothie Logic)
        // Si chute impossible (>20mg/5min) avec peu d'IOB la nuit -> ARTIFACT
        isCompressionArtifactCandidate(context) -> {
            aapsLogger.warn(LTag.GLUCOSE, "AdaptiveSmoothing: COMPRESSION LOW DETECTED (Impossible Drop)")
            SmoothingMode.COMPRESSION_BLOCK
        }
        
        // HYPO : Sécurité absolue - pas de lissage
        context.zone == GlycemicZone.HYPO -> {
            aapsLogger.warn(LTag.GLUCOSE, "AdaptiveSmoothing: HYPO detected, no smoothing applied")
            SmoothingMode.HYPO_SAFE
        }

        // Descente rapide en zone basse : sécurité hypo
        context.delta < -4.0 && context.zone == GlycemicZone.LOW_NORMAL -> {
            SmoothingMode.RAPID_FALL
        }

        // Montée rapide : lissage minimal pour réactivité
        // Seuils : delta > +5 mg/dL/5min ET accélération > +2 mg/dL
        context.delta > 5.0 && context.acceleration > 2.0 -> {
            SmoothingMode.RAPID_RISE
        }

        // Descente rapide (hors zone basse)
        context.delta < -4.0 -> {
            SmoothingMode.RAPID_FALL
        }

        // Variabilité élevée : lissage agressif
        // Seuil : CV > 15% (capteur instable)
        context.cv > 15.0 -> {
            SmoothingMode.NOISY
        }

        // Stable : lissage standard
        else -> {
            SmoothingMode.STABLE
        }
    }

    private fun isCompressionArtifactCandidate(ctx: GlycemicContext): Boolean {
        // 1. Chute massive ( > -15 la nuit, ou > -25 le jour)
        val dropThreshold = if (ctx.isNight) -15.0 else -25.0
        val isImpossibleDrop = ctx.delta < dropThreshold
        
        // 2. Contexte "Safe" (Pas d'explication physiologique)
        // Si IOB > 3U, la chute peut être réelle (insuline active)
        val isPhysiologicallyUnlikely = ctx.iob < 3.0 && ctx.acceleration > -5.0 // Pas d'accélération massive continue
        
        return isImpossibleDrop && isPhysiologicallyUnlikely
    }

    /**
     * Mode COMPRESSION_BLOCK
     * Ignore la chute brutale. Projette la dernière valeur stable ou lisse fortement.
     */
    private fun applyCompressionProtection(
        data: MutableList<InMemoryGlucoseValue>,
        context: GlycemicContext
    ): MutableList<InMemoryGlucoseValue> {
        // Stratégie : On ignore le point actuel (le creux) et on renvoie le point précédent
        // Ou mieux : Zero-Order Hold (Maintien de la valeur précédente)
        
        for (i in data.lastIndex - 1 downTo 0) { // On traite tout le monde au cas où
             // Si on détecte une chute brutale locale > 15 mg entre i+1 et i
             // On écrase i par i+1 (ordre chronologique inverse ici? data[0] est le plus récent)
             // Wait, usually list[0] is newest.
        }
        
        // Correction simple sur le point le plus récent (0)
        // On remplace sa valeur 'smoothed' par la valeur précédente (1)
        if (data.size > 1 && isValid(data[1].value)) {
             data[0].smoothed = data[1].value // Hold previous value
             data[0].trendArrow = TrendArrow.FLAT
             aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Holding Value ${data[1].value} over ${data[0].value}")
        }
        
        return data
    }

    /**
     * Mode RAPID_RISE : Lissage minimal pour réactivité maximale
     * Fenêtre réduite à 2 points (10 min) avec poids vers le présent
     */
    private fun applyMinimalSmoothing(
        data: MutableList<InMemoryGlucoseValue>,
        context: GlycemicContext
    ): MutableList<InMemoryGlucoseValue> {
        aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Applying MINIMAL smoothing (rapid rise)")

        for (i in data.lastIndex - 1 downTo 1) {
            if (isValid(data[i].value) && isValid(data[i - 1].value)) {
                // Poids 70% présent, 30% passé (favorise la réactivité)
                data[i].smoothed = 0.7 * data[i].value + 0.3 * data[i - 1].value
                data[i].trendArrow = TrendArrow.NONE
            }
        }

        return data
    }

    /**
     * Mode RAPID_FALL : Lissage asymétrique pour sécurité hypo
     * On privilégie la valeur MIN pour éviter un retard dangereux en descente
     */
    private fun applyAsymmetricSmoothing(
        data: MutableList<InMemoryGlucoseValue>,
        context: GlycemicContext
    ): MutableList<InMemoryGlucoseValue> {
        aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Applying ASYMMETRIC smoothing (rapid fall - hypo safety)")

        for (i in data.lastIndex - 1 downTo 1) {
            if (isValid(data[i].value) && isValid(data[i - 1].value) && isValid(data[i + 1].value)) {
                // Prendre la valeur MIN des 3 points (sécurité hypo)
                val minValue = minOf(data[i - 1].value, data[i].value, data[i + 1].value)

                // Pondération : 60% MIN, 40% actuel
                data[i].smoothed = 0.6 * minValue + 0.4 * data[i].value
                data[i].trendArrow = TrendArrow.NONE
            }
        }

        return data
    }

    /**
     * Mode STABLE : Lissage standard (moyenne mobile à 3 points)
     * Équilibre entre filtrage du bruit et réactivité
     */
    private fun applyStandardSmoothing(
        data: MutableList<InMemoryGlucoseValue>,
        context: GlycemicContext
    ): MutableList<InMemoryGlucoseValue> {
        aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Applying STANDARD smoothing (stable)")

        for (i in data.lastIndex - 1 downTo 1) {
            if (isValid(data[i].value) && isValid(data[i - 1].value) && isValid(data[i + 1].value)
                && abs(data[i].timestamp - data[i - 1].timestamp - (data[i + 1].timestamp - data[i].timestamp)) < 30_000
            ) {
                // Moyenne simple à 3 points
                data[i].smoothed = (data[i - 1].value + data[i].value + data[i + 1].value) / 3.0
                data[i].trendArrow = TrendArrow.NONE
            }
        }

        return data
    }

    /**
     * Mode NOISY : Lissage agressif pour filtrer le bruit capteur
     * Fenêtre large (5 points = 25 min) avec pondération gaussienne
     */
    private fun applyAggressiveSmoothing(
        data: MutableList<InMemoryGlucoseValue>,
        context: GlycemicContext
    ): MutableList<InMemoryGlucoseValue> {
        aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: Applying AGGRESSIVE smoothing (high noise)")

        if (data.size < 5) {
            // Fallback sur lissage standard si pas assez de données
            return applyStandardSmoothing(data, context)
        }

        for (i in data.lastIndex - 2 downTo 2) {
            if (data.subList(i - 2, i + 3).all { isValid(it.value) }) {
                // Poids gaussiens : [0.06, 0.24, 0.4, 0.24, 0.06] (somme = 1.0)
                data[i].smoothed =
                    0.06 * data[i - 2].value +
                        0.24 * data[i - 1].value +
                        0.40 * data[i].value +
                        0.24 * data[i + 1].value +
                        0.06 * data[i + 2].value
                data[i].trendArrow = TrendArrow.NONE
            }
        }

        return data
    }

    /**
     * Mode HYPO_SAFE : Pas de lissage en hypo (données brutes)
     * Sécurité maximale
     */
    private fun applyNoSmoothing(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue> {
        aapsLogger.warn(LTag.GLUCOSE, "AdaptiveSmoothing: NO smoothing applied (HYPO safety)")
        // Les données smoothed restent à null, AIMI utilisera les valeurs brutes
        return data
    }

    /**
     * Validation de la valeur glycémique
     * Dexcom : < 39 = LOW, > 401 = HI
     */
    private fun isValid(value: Double): Boolean {
        return value in 39.0..401.0
    }
}
