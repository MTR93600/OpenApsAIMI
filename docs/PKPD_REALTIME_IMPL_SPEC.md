# REAL-TIME INSULIN OBSERVER — ARCHITECTURE & IMPLÉMENTATION

**Date:** 2025-12-18  
**Mission:** Système intelligent pilotant SMB vs TBR basé sur l'activité insulinique réelle  
**Status:** 🎯 SPÉCIFICATION COMPLÈTE

---

## 🎯 OBJECTIF

Créer un observateur temps réel de l'action insulinique qui détecte :
- **Onset réel** : Quand l'insuline entre vraiment en action (vs théorique)
- **Activité instantané** : Intensité actuelle de l'effet insulinique
- **Time-to-Peak / Time-to-End** : Prédiction de quand l'effet va culminer/disparaître
- **Résiduel** : Combien d'effet utile reste (aire sous courbe restante)

Puis utiliser ces signaux pour piloter intelligemment:
- **SMB** quand pertinent (fin d'action, pression faible, montée confirmée)
- **TBR** quand nécessaire (onset non confirmé, near peak, forte activité)

**Sans jamais bloquer** : Soft throttle, pas de blocage brutal.

---

## 📋 PARTIE B — ANALYSE CSV (Résumé)

### Schema CSV Déduit (oapsaimi_pkpd_records.csv)

```
Col  Field              Type    Description
---  -----              ----    -----------
0    dateStr            String  Timestamp (YYYY-MM-DD HH:mm:ss)
1    bg                 Double  BG actuel (mg/dL)
2    delta              Double  Delta BG (mg/dL/5min)
3    iob                Double  IOB actuel (U)
4    diaH               Double  DIA adaptatif (heures)
5    peakMin            Double  Peak time adaptatif (minutes)
6    tailFrac           Double  Tail fraction (0-1)
7    iobActivityNow     Double  Activité insulinique actuelle
8    iobActivityIn30    Double  Activité prédite +30min
9    peakMinutesAbs     Int     Temps absolu jusqu'au pic (min)
10   profileIsf         Double  ISF profil
11   tddIsf             Double  ISF basé TDD
12   fusedIsf           Double  ISF fusionné
13   predBg             Double  BG prédit principal
14   eventualBg         Double  BG eventual stabilisé
15   minPredBg          Double  BG prédit minimum
16   smbProposedU       Double  SMB proposé (U)
17   smbFinalU          Double  SMB final envoyé (U)
18   tbrUph             Double  TBR (U/h)
19   reason             String  Raison décision
```

### Validation Terrain (Patterns Attendus)

**Distribution Attendue:**
- `diaH`: 3-7 heures (mode ~4-5h pour Novorapid/Humalog)
- `peakMin`: 45-90 minutes (mode ~6075min)
- `tailFrac`: 0.2-0.5 (tail représente 20-50% de l'action)
- `iobActivityNow`: 0.0-0.8 (pic max ~0.6-0.8)

**Cas Suspects à Détecter:**
1. **Peak/DIA collés à min/max** → Estimateur saturé
2. **tailFrac > 1.0 ou < 0** → Bug calcul
3. **smbProposed >> smbFinal** → Over-capping systématique
4. **smbFinal > 0 quand iobActivityNow > 0.7** → Risque stacking near peak

---

## 🚀 PARTIE C — REAL-TIME INSULIN OBSERVER (Conception)

### Architecture Module

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * État de l'action insulinique en temps réel
 */
data class InsulinActionState(
    // Onset Detection
    val onsetConfirmed: Boolean,        // L'insuline a vraiment commencé à agir
    val onsetConfidenceScore: Double,   // 0.0-1.0
    val timeSinceOnsetMin: Double,      // Minutes depuis onset confirmé
    
    // Activity Metrics
    val activityNow: Double,             // 0.0-1.0 (normalized)
    val activityStage: ActivityStage,    // RISING / PEAK / FALLING / TAIL
    val timeToPeakMin: Int,              // Minutes jusqu'au pic
    val timeToEndMin: Int,               // Minutes jusqu'à fin d'action
    
    // Residual Effect
    val residualEffect: Double,          // 0.0-1.0 (aire restante / aire totale)
    val effectiveIob: Double,            // IOB pondéré par activité
    
    // Diagnostics
    val reason: String                   // Explication debug
)

enum class ActivityStage {
    RISING,    // Onset → Peak (avant pic)
    PEAK,      // Near peak (±15min du pic)
    FALLING,   // Post-peak, décroissance active
    TAIL       // Queue longue, activité résiduelle faible
}

/**
 * Observateur temps réel de l'action insulinique
 */
class RealTimeInsulinObserver {
    
    // État interne
    private var lastOnsetConfirmedAt: Long = 0L
    private var bgSlopeHistory: MutableList<Double> = mutableListOf()
    private var correlationHistory: MutableList<Double> = mutableListOf()
    
    /**
     * Met à jour l'observateur avec nouvelles données
     */
    fun update(
        currentBg: Double,
        bgDelta: Double,
        iobTotal: Double,
        iobActivityNow: Double,
        iobActivityIn30: Double,
        peakMinutesAbs: Int,
        diaHours: Double,
        carbsActiveG: Double,  // COB actif
        now: Long
    ): InsulinActionState {
        
        // 1. Calcul BG slope lissé
        val bgSlope = computeSmoothedSlope(bgDelta)
        
        // 2. Drive insulinique attendu (négatif si insuline active)
        val expectedDrive = -iobActivityNow * 50.0  // mg/dL/h estimé
        
        // 3. Corrélation slope vs drive (neutraliser effet glucides)
        val carbNeutralizer = if (carbsActiveG > 5.0) 0.5 else 1.0
        val correlation = computeCorrelation(bgSlope, expectedDrive) * carbNeutralizer
        
        // 4. Détection onset
        val onsetConfirmed = detectOnset(correlation, iobTotal, now)
        val onsetConfidence = correlation.coerceIn(0.0, 1.0)
        val timeSinceOnset = if (onsetConfirmed) (now - lastOnsetConfirmedAt) / 60000.0 else 0.0
        
        // 5. Stage détection
        val stage = detectActivityStage(iobActivityNow, peakMinutesAbs, timeSinceOnset)
        
        // 6. Time-to-peak/end
        val timeToPeak = if (stage == ActivityStage.RISING) peakMinutesAbs else 0
        val timeToEnd = estimateTimeToEnd(diaHours, timeSinceOnset)
        
        // 7. Residual effect (aire restante / aire totale)
        val residual = computeResidualEffect(timeSinceOnset, diaHours, stage)
        val effectiveIob = iobTotal * iobActivityNow
        
        // 8. Reason
        val reason = buildReason(onsetConfirmed, stage, correlation, residual)
        
        return InsulinActionState(
            onsetConfirmed = onsetConfirmed,
            onsetConfidenceScore = onsetConfidence,
            timeSinceOnsetMin = timeSinceOnset,
            activityNow = iobActivityNow,
            activityStage = stage,
            timeToPeakMin = timeToPeak,
            timeToEndMin = timeToEnd,
            residualEffect = residual,
            effectiveIob = effectiveIob,
            reason = reason
        )
    }
    
    private fun computeSmoothedSlope(bgDelta: Double): Double {
        // EMA du slope pour réduire bruit
        bgSlopeHistory.add(bgDelta)
        if (bgSlopeHistory.size > 4) bgSlopeHistory.removeAt(0)
        return bgSlopeHistory.average()
    }
    
    private fun computeCorrelation(bgSlope: Double, expectedDrive: Double): Double {
        // Corrélation simple : si slope et drive vont dans même sens
        // expectedDrive négatif (insuline baisse BG) et bgSlope négatif → corrélation positive
        val alignment = if (bgSlope * expectedDrive > 0) 1.0 else -1.0
        val magnitude = kotlin.math.abs(bgSlope / (kotlin.math.abs(expectedDrive) + 1.0))
        return (alignment * magnitude).coerceIn(-1.0, 1.0)
    }
    
    private fun detectOnset(correlation: Double, iobTotal: Double, now: Long): Boolean {
        correlationHistory.add(correlation)
        if (correlationHistory.size > 3) correlationHistory.removeAt(0)
        
        // Onset confirmé si:
        // 1. IOB > 0.5U (assez d'insuline pour effet mesurable)
        // 2. Corrélation stable > 0.5 pendant 3 ticks (15 min)
        val stableCorrelation = correlationHistory.all { it > 0.5 }
        val sufficientIob = iobTotal > 0.5
        
        if (stableCorrelation && sufficientIob && lastOnsetConfirmedAt == 0L) {
            lastOnsetConfirmedAt = now
            return true
        }
        
        return lastOnsetConfirmedAt > 0L
    }
    
    private fun detectActivityStage(activityNow: Double, timeToPeak: Int, timeSinceOnset: Double): ActivityStage {
        return when {
            timeToPeak in 1..15 -> ActivityStage.PEAK     // À 15 min du pic
            timeToPeak > 15 -> ActivityStage.RISING        // Avant pic
            activityNow > 0.3 -> ActivityStage.FALLING     // Post-pic, activité significative
            else -> ActivityStage.TAIL                      // Queue
        }
    }
    
    private fun estimateTimeToEnd(diaHours: Double, timeSinceOnset: Double): Int {
        val totalDurationMin = diaHours * 60.0
        val remaining = totalDurationMin - timeSinceOnset
        return remaining.coerceAtLeast(0.0).toInt()
    }
    
    private fun computeResidualEffect(timeSinceOnset: Double, diaHours: Double, stage: ActivityStage): Double {
        // Approximation aire restante
        // Weibull: pic à ~1/3, puis decay exponentiel
        val progress = timeSinceOnset / (diaHours * 60.0)
        
        return when (stage) {
            ActivityStage.RISING -> 0.9 - progress * 0.2  // Quasi tout reste avant pic
            ActivityStage.PEAK -> 0.7                      // 70% après pic
            ActivityStage.FALLING -> 0.5 - progress * 0.3  // Décroissance
            ActivityStage.TAIL -> 0.2 - progress * 0.2     // Queue résiduelle
        }.coerceIn(0.0, 1.0)
    }
    
    private fun buildReason(onset: Boolean, stage: ActivityStage, corr: Double, residual: Double): String {
        return "onset=${if (onset) "✓" else "✗"} stage=$stage corr=${"%.2f".format(corr)} resid=${"%.2f".format(residual)}"
    }
}
```

---

## 🎯 PARTIE D — INTÉGRATION SMB/TBR THROTTLE

### Principe de Décision

```kotlin
/**
 * Throttle intelligent SMB vs TBR basé sur InsulinActionState
 */
data class SmbTbrThrottle(
    val smbFactor: Double,      // 0.2-1.0 (multiplicateur SMB)
    val intervalAddMin: Int,    // 0-10 (ajout interval entre SMBs)
    val preferTbr: Boolean,     // true = privilégier TBR
    val reason: String
)

fun computeThrottle(
    actionState: InsulinActionState,
    bgDelta: Double,
    bgRising: Boolean
): SmbTbrThrottle {
    
    // Règle 1: Onset non confirmé + BG monte → TBR prioritaire, SMB réduit
    if (!actionState.onsetConfirmed && bgRising) {
        return SmbTbrThrottle(
            smbFactor = 0.6,
            intervalAddMin = 3,
            preferTbr = true,
            reason = "Onset unconfirmed, rising BG → TBR priority"
        )
    }
    
    // Règle 2: Near peak (activité élevée) → SMB très réduit
    if (actionState.activityStage == ActivityStage.PEAK || actionState.activityNow > 0.7) {
        return SmbTbrThrottle(
            smbFactor = 0.3,
            intervalAddMin = 5,
            preferTbr = true,
            reason = "Near peak / High activity → SMB throttled"
        )
    }
    
    // Règle 3: Tail (résiduel faible) + BG monte → SMB permissif
    if (actionState.activityStage == ActivityStage.TAIL && actionState.residualEffect < 0.3 && bgRising) {
        return SmbTbrThrottle(
            smbFactor = 1.0,
            intervalAddMin = 0,
            preferTbr = false,
            reason = "Tail stage, low residual → SMB permitted"
        )
    }
    
    // Règle 4: Falling (post-peak normal) → SMB modéré
    if (actionState.activityStage == ActivityStage.FALLING) {
        return SmbTbrThrottle(
            smbFactor = 0.7,
            intervalAddMin = 2,
            preferTbr = false,
            reason = "Falling stage → SMB moderate"
        )
    }
    
    // Défaut: Normal
    return SmbTbrThrottle(
        smbFactor = 1.0,
        intervalAddMin = 0,
        preferTbr = false,
        reason = "Normal operation"
    )
}
```

### Intégration dans DetermineBasalAIMI2

**Modification dans `finalizeAndCapSMB` (après ligne 1479):**

```kotlin
// 🚀 NOUVEAUTÉ: Real-Time Insulin Observer Throttle
if (insulinObserver != null && !isExplicitUserAction) {
    val actionState = insulinObserver.getState()
    val throttle = computeThrottle(actionState, delta, bg > targetBg)
    
    // Apply throttle
    gatedUnits = (gatedUnits * throttle.smbFactor).toFloat()
    
    // Log
    consoleLog.add("PKPD_THROTTLE smbFactor=${throttle.smbFactor} intervalAdd=${throttle.intervalAddMin} preferTbr=${throttle.preferTbr} reason=${throttle.reason}")
    consoleLog.add("PKPD_OBS ${actionState.reason}")
    
    // Si preferTbr, suggérer TBR modérée dans rT.reason (pas bloquer SMB)
    if (throttle.preferTbr && gatedUnits < proposedSmb * 0.5) {
        rT.reason.append(" | 💡 TBR recommended (${throttle.reason})")
    }
}
```

---

## 📊 LOGS ATTENDUS

### Logs PKPD Observer

```
PKPD_OBS onset=✓ stage=FALLING corr=0.78 resid=0.45
PKPD_THROTTLE smbFactor=0.7 intervalAdd=2 preferTbr=false reason=Falling stage → SMB moderate
```

### Logs Diagnostic Complets

```
PKPD_OBS onset=✗ stage=RISING corr=0.32 resid=0.85
PKPD_THROTTLE smbFactor=0.6 intervalAdd=3 preferTbr=true reason=Onset unconfirmed, rising BG → TBR priority
💡 TBR recommended (Onset unconfirmed, rising BG → TBR priority)
```

---

## ✅ VALIDATION & TESTS

### Test Scenarios

**Scenario 1: Onset Detection**
- **Input:** Bolus 5U envoyé, iobActivityNow monte de 0.1 → 0.4, BG slope passe de +2 à -3
- **Expected:** `onset=✓` après 3 ticks stables, `stage=RISING`

**Scenario 2: Near Peak Throttle**
- **Input:** `iobActivityNow=0.75`, `timeToPeak=10min`, BG monte +5
- **Expected:** `smbFactor=0.3`, `preferTbr=true`

**Scenario 3: Tail Permissive**
- **Input:** `activityStage=TAIL`, `residual=0.2`, BG monte +8
- **Expected:** `smbFactor=1.0`, `preferTbr=false`

---

## 🎯 PROCHAINES ÉTAPES IMPLÉMENTATION

### Phase 1: Core Observer (Priorité)
1. Créer `RealTimeInsulinObserver.kt`
2. Créer `InsulinActionState.kt` (data class)
3. Tests unitaires onset detection

### Phase 2: Throttle Logic
1. Créer `SmbTbrThrottle.kt`
2. Fonction `computeThrottle()`
3. Tests scénarios

### Phase 3: Intégration
1. Instancier observer dans `DetermineBasalAIMI2`
2. Appeler `update()` chaque tick
3. Appliquer throttle dans `finalizeAndCapSMB`
4. Logs PKPD_OBS / PKPD_THROTTLE

### Phase 4: Validation
1. Build success
2. Analyser logs terrain
3. Ajuster thresholds si nécessaire

---

## 📝 CONCLUSION

**État:** Spécification complète ✅  
**Prochaine action:** Implémenter RealTimeInsulinObserver.kt  
**Complexité estimée:** 3-4 heures (développement + tests + intégration)  
**Risque:** FAIBLE (soft throttle, pas de blocage)

**Garantie:** Le système **ne bloquera jamais** complètement les SMBs. Le throttle est un multiplicateur (0.2-1.0), jamais 0.0.
