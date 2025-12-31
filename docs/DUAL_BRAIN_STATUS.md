# 🎯 DUAL-BRAIN AUDITOR - ÉTAT D'AVANCEMENT

## Date: 2025-12-31 10:45

---

## ✅ IMPLÉMENTÉ

### 1. Local Sentinel (Core) ✅
**Fichier** : `LocalSentinel.kt` (335 lignes)

**Fonctionnalités** :
- ✅ Détection drift persistant (+30 pts)
- ✅ Détection plateau haut (+20 pts)
- ✅ Détection variabilité/oscillations (+25/+20 pts)
- ✅ Détection stacking risk (+35 pts IOB/PKPD)
- ✅ Détection SMB chain (+30 pts)
- ✅ Détection recent bolus stacking (+15 pts)
- ✅ Détection prediction missing (+40 pts)
- ✅ Détection contradiction PKPD/ML (+25 pts)
- ✅ Détection autodrive stuck (+20 pts)
- ✅ Détection high noise (+15 pts)
- ✅ Détection stale data (+25 pts)
- ✅ Détection pump unreachable (+30 pts)

**Tier System** :
- `score 0-19` → NONE
- `score 20-39` → LOW
- `score 40-69` → MEDIUM
- `score 70-100` → HIGH

**Recommendations** :
- CONFIRM (smb×1.0, +0min)
- REDUCE_SMB (smb×0.7-0.8, +3-4min)
- INCREASE_INTERVAL (smb×0.8-0.9, +3-4min)
- PREFER_BASAL (smb×0.8-0.9, +2min, preferBasal=true)
- HOLD_SOFT (smb×0.6, +6min)

### 2. Documentation Complète ✅
**Fichiers** :
- `DUAL_BRAIN_AUDITOR_DESIGN.md` (800+ lignes)
  - Architecture complète
  - Pipeline détaillé
  - Scoring logic
  - Tier system
  - External Auditor spec
  - Format prompt/réponse API
  - Logs RT premium
  - 6 scénarios de test
  - Métriques attendues

---

## ⏳ À IMPLÉMENTER (Prochaines Étapes)

### Étape 1: Intégrer Sentinel dans AuditorOrchestrator ⏳

**Fichier** : `Aud

itorOrchestrator.kt`

**Modifications nécessaires** :

```kotlin
// 1. Ajouter import
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.LocalSentinel

// 2. Dans auditDecision(), avant External Auditor call:

// === LOCAL SENTINEL (TOUJOURS ACTIF) ===
val sentinelAdvice = LocalSentinel.computeAdvice(
    bg = bg,
    target = profile.target,
    delta = delta,
    shortAvgDelta = shortAvgDelta,
    longAvgDelta = longAvgDelta,
    predictedBg = glucoseStatus?.predictedBg,
    eventualBg = glucoseStatus?.eventualBg,
    predBgsAvailable = predictionAvailable,
    iobTotal = iob.iob,
    iobActivity = iob.activity,
    pkpdStage = pkpdRuntime?.activity?.stage?.name,
    lastBolusAgeMin = ((systemTime - iob.lastBolusTime) / 60000.0).coerceAtLeast(0.0),
    smbCount30min = calculateSmbCount30min(), // À créer
    smbTotal60min = calculateSmbTotal60min(), // À créer
    smbProposed = smbProposed,
    noise = glucoseStatus?.noise ?: 0,
    isStale = glucoseStatus?.isStale ?: false,
    pumpUnreachable = false, // À récupérer du pump status
    autodriveActive = autodriveState.contains("ACTIVE"),
    modeActive = modeType != null,
    bgHistory = extractBgHistory30min() // À créer
)

// Log Sentinel
consoleLog.add("🔍 SENTINEL: score=${sentinelAdvice.score} tier=${sentinelAdvice.tier} reason=${sentinelAdvice.reason}")
sentinelAdvice.details.forEach { consoleLog.add("  └─ $it") }

// === EXTERNAL AUDITOR (CONDITIONNEL) ===
var externalVerdict: AuditorVerdict? = null

// Appeler External SEULEMENT si tier HIGH (ou MEDIUM en mode aggressive)
val shouldCallExternal = when {
    sentinelAdvice.tier == LocalSentinel.Tier.HIGH -> true
    sentinelAdvice.tier == LocalSentinel.Tier.MEDIUM && getModulationMode() == ModulationMode.AGGRESSIVE -> true
    else -> false
}

if (shouldCallExternal && isAuditorEnabled()) {
    // Check cooldown, budget, etc.
    if (checkRateLimit(now)) {
        // Build enhanced prompt avec Sentinel advice
        val enhancedInput = buildEnhancedInput(input, sentinelAdvice)
        
        // Call AI (existing code)
        externalVerdict = aiService.getVerdict(enhancedInput, provider, timeoutMs)
        
        if (externalVerdict != null) {
            consoleLog.add("🌐 AUDITOR: confidence=${String.format("%.2f", externalVerdict.confidence)} rec=${externalVerdict.verdict}")
        } else {
            consoleLog.add("🌐 AUDITOR: timeout or error, using Sentinel only")
        }
    } else {
        consoleLog.add("🌐 AUDITOR: rate limited, using Sentinel only")
    }
} else {
    consoleLog.add("🌐 AUDITOR: tier=${sentinelAdvice.tier} < threshold, Sentinel only")
}

// === COMBINE ADVICE ===
val combinedAdvice = combineAdvice(sentinelAdvice, externalVerdict)
consoleLog.add("✅ COMBINED: smb×${String.format("%.2f", combinedAdvice.smbFactor)} +${combinedAdvice.extraIntervalMin}m preferBasal=${combinedAdvice.preferBasal}")

// === APPLY (via callback) ===
callback?.invoke(externalVerdict, combinedAdvice.toModulatedDecision(...))
```

**Fonctions helper à créer** :
- `calculateSmbCount30min()` : Compter SMB 30 dernières min
- `calculateSmbTotal60min()` : Total U SMB 60 dernières min
- `extractBgHistory30min()` : Historique BG 30min
- `combineAdvice()` : Combiner Sentinel + External (most conservative)
- `buildEnhancedInput()` : Enrichir prompt avec Sentinel advice

### Étape 2: Modifier DetermineBasalAIMI2.kt ⏳

**Fichier** : `DetermineBasalAIMI2.kt`

**Intégration dans pipeline** (autour ligne 6200-6300) :

```kotlin
// Après calcul décision AIMI (modes, autodrive, ML, etc.)
// Avant finalizeAndCapSMB

// === DUAL-BRAIN AUDITOR ===
if (auditorEnabled) {
    // Appel AuditorOrchestrator.auditDecision()
    // qui fait Sentinel + optionnellement External
    auditorOrchestrator.auditDecision(
        bg = bg,
        delta = delta,
        // ... tous les params
        callback = { verdict, modulated ->
            // Apply modulation
            if (modulated.appliedModulation) {
                val beforeSmbGuard = smbToGive
                smbToGive = (smbToGive * modulated.smbFactor).coerceAtLeast(0f)
                
                intervalsmb = (intervalsmb + modulated.extraIntervalMin).coerceAtMost(20)
                
                consoleError.add("🛡️ DUAL-BRAIN: SMB ${String.format("%.2f", beforeSmbGuard)}U → ${String.format("%.2f", smbToGive)}U (×${String.format("%.2f", modulated.smbFactor)})")
                consoleLog.add("🛡️ DUAL-BRAIN: Interval +${modulated.extraIntervalMin}m → ${intervalsmb}m")
                
                if (modulated.preferTbr) {
                    consoleLog.add("🛡️ DUAL-BRAIN: Prefer basal (TBR) over SMB")
                    // Réduire encore SMB, augmenter TBR si applicable
                }
                
                rT.reason.append(" | GUARD×${String.format("%.2f", modulated.smbFactor)}")
            }
        }
    )
}

// Puis finalizeAndCapSMB (code existant)
```

### Étape 3: Modifier RtInstrumentationHelpers.kt ⏳

**Ajouter fonction** :

```kotlin
fun buildDualBrainLine(
    sentinelScore: Int,
    sentinelTier: String,
    sentinelReason: String,
    externalCalled: Boolean,
    externalConfidence: Double?,
    externalRec: String?,
    finalSmbFactor: Double,
    finalExtraInterval: Int
): String {
    val parts = mutableListOf<String>()
    
    // Sentinel
    parts.add("Sentinel: ${sentinelReason} tier=$sentinelTier score=$sentinelScore")
    
    // External (si appelé)
    if (externalCalled && externalConfidence != null) {
        parts.add("Ext: ${externalRec ?: "N/A"} conf=${String.format("%.2f", externalConfidence)}")
    }
    
    // Final
    parts.add("Guard: ×${String.format("%.2f", finalSmbFactor)} +${finalExtraInterval}m")
    
    val line = "DualBrain: " + parts.joinToString(" | ")
    return if (line.length > 100) line.substring(0, 97) + "..." else line
}
```

### Étape 4: Tests ⏳

**Scénarios à tester** :
1. ✅ Drift lent → tier MEDIUM, PREFER_BASAL
2. ✅ SMB chain + IOB high → tier HIGH, HOLD_SOFT, External appelé
3. ✅ Prediction missing → tier HIGH, degraded mode
4. ✅ BG <120 + delta+ → tier LOW, clamp variabilité
5. ✅ Autodrive stuck → tier MEDIUM, PREFER_BASAL
6. ✅ Normal stable → tier NONE, CONFIRM

---

## 📊 RÉSUMÉ

### ✅ Ce Qui Est Fait

| Item | Status | Fichier | Lignes |
|------|--------|---------|--------|
| Local Sentinel Core | ✅ | LocalSentinel.kt | 335 |
| Design Complet | ✅ | DUAL_BRAIN_AUDITOR_DESIGN.md | 800+ |
| Status Tracker (précédent) | ✅ | AuditorStatusTracker.kt | 112 |
| RT Helpers (précédent) | ✅ | RtInstrumentationHelpers.kt | 200 |

### ⏳ Ce Qui Reste

| Item | Status | Estimation | Priorité |
|------|--------|------------|----------|
| Intégration AuditorOrchestrator | ⏳ | 150 lignes | 🔴 CRITICAL |
| Helper functions (SMB count, etc.) | ⏳ | 80 lignes | 🔴 CRITICAL |
| Intégration DetermineBasalAIMI2 | ⏳ | 50 lignes | 🔴 CRITICAL |
| RT Logs premium | ⏳ | 30 lignes | 🟡 IMPORTANT |
| Tests scénarios | ⏳ | Manual | 🟡 IMPORTANT |
| Build validation | ⏳ | - | 🔴 CRITICAL |

---

## 🚀 PROCHAINE ACTION IMMÉDIATE

**Voulez-vous que je continue l'implémentation complète maintenant ?**

Si oui, je vais :
1. Modifier `AuditorOrchestrator.kt` pour intégrer Sentinel + 2-tier logic
2. Créer les helper functions nécessaires
3. Modifier `DetermineBasalAIMI2.kt` pour le pipeline unique
4. Ajouter logs RT premium
5. Build & validation

**Estimation** : ~45-60 min de travail pour compléter tout

**Alternative** : Je peux créer un fichier de "patch guide" détaillé que vous pouvez appliquer vous-même si vous préférez.

Que préférez-vous ? 🎯
