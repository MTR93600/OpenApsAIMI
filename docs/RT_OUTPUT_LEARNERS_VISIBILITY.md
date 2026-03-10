# 🔍 RT OUTPUT VISIBILITY - LEARNERS & AUDITOR

## Date: 2025-12-28 22:06

---

## 🎯 PROBLÈME IDENTIFIÉ PAR MANU

### Observation

1. **AI Auditor** : Pas assez d'infos dans `finalResult.reason` (le champ `reason` visible dans RT)
2. **Learners** (Basal, PKPD, etc.) : Même problème, leurs décisions ne sont PAS dans `reason`

### Symptôme

Le RT affiche `finalResult.reason` qui contient des phrases comme :
- `"BG combinedDelta faible (0.5), réduction x0.6"`
- `"IOB élevé (2.5U), réduction x0.85"`

**MAIS** : Aucune mention de :
- ✅ Basal Learner : `shortTerm=1.05, mediumTerm=0.98, longTerm=1.12` → Applied: 1.05
- ✅ PKPD Learner : `ISF adjusted to 42 (from 50)`
- ✅ Diaby : `SMB reduced from 0.8U to 0.56U due to stacking risk`

---

## 🧩 ARCHITECTURE ACTUELLE

### Comment `finalResult.reason` est Construit

#### 1. Dans `determine_basal()`

```kotlin
// Ligne 706
val reasonBuilder = StringBuilder()

// Ajouts conditionnels basés sur BG, Delta, IOB, etc.
if (dropPerHour > 6) {
    reasonBuilder.append(context.getString(R.string.bg_drop_high, dropPerHour))
}
if (delta > 15) {
    reasonBuilder.append(context.getString(R.string.bg_rapid_rise, delta))
}
// ... etc
```

#### 2. Dans `setTempBasal()`

```kotlin
fun setTempBasal(...): RT {
    // rT.reason est un StringBuilder
    
    if (blockLgs) {
        rT.reason.append(context.getString(R.string.lgs_triggered, bg, hypoGuard))
    }
    
    if (forceExact) {
        rT.reason.append(context.getString(R.string.manual_basal_override, ...))
    }
    
    // WCycle
    if (wCycleInfo != null && wCycleInfo.applied) {
        appendWCycleReason(rT.reason, wCycleInfo)
    }
    
    // ... return rT
}
```

#### 3. `finalResult` est créé

```kotlin
// Ligne 5979
val finalResult = setTempBasal(
    _rate = basalDecision.rate,
    duration = basalDecision.duration,
    profile = profile,
    rT = rT,  // ← rT contient rT.reason (le StringBuilder)
    currenttemp = currenttemp,
    overrideSafetyLimits = basalDecision.overrideSafety
)
```

### Où les Learners Loggent Actuellement

#### Basal Learner

```kotlin
// Lignes 5948-5954
consoleLog.add("📊 BASAL_LEARNER:")
consoleLog.add("  │ shortTerm: ${\"%.3f\".format(Locale.US, basalLearner.shortTermMultiplier)}")
consoleLog.add("  │ mediumTerm: ${\"%.3f\".format(Locale.US, basalLearner.mediumTermMultiplier)}")
consoleLog.add("  │ longTerm: ${\"%.3f\".format(Locale.US, basalLearner.longTermMultiplier)}")
consoleLog.add("  │ Applied: ${\"%.3f\".format(Locale.US, basalLearner.getMultiplier())}")
consoleLog.add("  └─")
```

✅ **Présent dans** : `consoleLog` (array)  
❌ **Absent de** : `finalResult.reason` (string visible dans RT)

#### PKPD Learner

```kotlin
// Lignes 4677-4687
consoleLog.add("📊 PKPD_LEARNER:")
consoleLog.add("  │ ISF: ${pkpdRuntime.isf} (learning: ${\"%.1f\".format(pkpdRuntime.learningFactor)})")
consoleLog.add("  │ IC: ${pkpdRuntime.ic}")
consoleLog.add("  │ DIA peak: ${pkpdRuntime.peakMinute}min")
// ...
```

✅ **Présent dans** : `consoleLog` (array)  
❌ **Absent de** : `finalResult.reason` (string visible dans RT)

#### AI Auditor

```kotlin
// Lignes 6117-6130 (ancien code)
if (modulated.appliedModulation) {
    consoleLog.add(sanitizeForJson("🧠 AI Auditor: ${modulated.modulationReason}"))
    // ...
}
```

✅ **Présent dans** : `consoleLog` (array)  
❌ **Absent de** : `finalResult.reason` (string visible dans RT)  
⚠️ **Partiellement dans** : `finalResult.aiAuditorModulation` (champ dédié, mais PAS dans reason)

---

## ✅ SOLUTION PROPOSÉE

### Stratégie : Ajouter à `finalResult.reason`

On doit **enrichir** `finalResult.reason` en y ajoutant les contributions des learners et de l'auditor.

### Où Modifier ?

**Option A** : Modifier `rT.reason` AVANT de retourner `finalResult`

```kotlin
// Dans determine_basal(), APRÈS création de finalResult (ligne ~6000)

// 1. Ajouter Basal Learner
if (basalLearner.getMultiplier() != 1.0) {
    finalResult.reason.append("; BasalLearner: ${\"%.2f\".format(basalLearner.getMultiplier())}")
}

// 2. Ajouter PKPD
if (pkpdRuntime.learningFactor != 1.0) {
    finalResult.reason.append("; PKPD ISF: ${pkpdRuntime.isf} (x${\"%.2f\".format(pkpdRuntime.learningFactor)})")
}

// 3. Ajouter Unified Reactivity
if (reactivityRuntime.reactiveDamp != 0.0) {
    finalResult.reason.append("; Reactivity Damp: ${\"%.2f\".format(reactivityRuntime.reactiveDamp)}")
}

// 4. Ajouter WCycle (déjà fait dans setTempBasal, mais on peut enrichir)
```

**Option B** : Créer une fonction `enrichReasonWithLearners()`

```kotlin
private fun enrichReasonWithLearners(
    result: RT,
    basalLearner: BasalLearner,
    pkpd: PKPDRuntime,
    reactivity: ReactivityRuntime
) {
    val additions = mutableListOf<String>()
    
    if (basalLearner.getMultiplier() != 1.0) {
        additions.add("BasalL: ${\"%.2f\".format(basalLearner.getMultiplier())}")
    }
    
    if (pkpd.learningFactor != 1.0) {
        additions.add("PKPD: ISF ${pkpd.isf}")
    }
    
    if (reactivity.reactiveDamp != 0.0) {
        additions.add("React: ${\"%.2f\".format(reactivity.reactiveDamp)}x")
    }
    
    if (additions.isNotEmpty()) {
        result.reason.append("; [").append(additions.joinToString(", ")).append("]")
    }
}
```

### Pour l'AI Auditor

L'auditor est **asynchrone**, donc on ne peut pas modifier `finalResult.reason` dans le callback directement.

**Solution** :

1. **Dans le callback** : Modifier `finalResult.reason` si possible (mais attention au threading)
2. **Alternative** : Utiliser `finalResult.aiAuditorModulation` (déjà fait) + documenter que c'est un champ séparé

**Option recommandée** : Créer un champ dédié `finalResult.learnersInfo` contenant un résumé :

```kotlin
// Nouvelle propriété dans DetermineBasalResultSMB
var learnersInfo: String? = null

// Dans determine_basal(), après learners process
val learnersSummary = buildString {
    append("BasalL:${\"%.2f\".format(basalLearner.getMultiplier())}")
    append(", PKPD:${pkpdRuntime.isf}")
    append(", React:${\"%.2f\".format(reactivityRuntime.reactiveDamp)}")
}
finalResult.learnersInfo = learnersSummary
```

---

## 📋 IMPLÉMENTATION CONCRÈTE

### Étape 1 : Ajouter Champ `learnersInfo` dans `DetermineBasalResultSMB`

**Fichier** : `DetermineBasalResultSMB.kt` (ou `.java`)

```kotlin
var learnersInfo: String? = null
```

### Étape 2 : Peupler `learnersInfo` dans `determine_basal()`

**Fichier** : `DetermineBasalAIMI2.kt`  
**Localisation** : Après ligne 5955 (après process des learners)

```kotlin
// 📊 Build learners summary for RT visibility
val learnersSummary = buildString {
    // Basal Learner
    val basalMult = basalLearner.getMultiplier()
    if (kotlin.math.abs(basalMult - 1.0) > 0.01) {
        append("Basal×${\"%.2f\".format(basalMult)}")
    }
    
    // PKPD Learner
    if (pkpdRuntime.learningFactor != 1.0) {
        if (isNotEmpty()) append(", ")
        append("ISF:${pkpdRuntime.isf}")
    }
    
    // Reactivity
    if (kotlin.math.abs(reactivityRuntime.reactiveDamp) > 0.01) {
        if (isNotEmpty()) append(", ")
        append("React:${\"%.2f\".format(reactivityRuntime.reactiveDamp)}x")
    }
}

finalResult.learnersInfo = if (learnersSummary.isNotEmpty()) learnersSummary else null
```

### Étape 3 : Modifier `finalResult.reason` pour Inclure Learners

**Option Simple** : Append à `reason`

```kotlin
// Juste après création de finalResult (ligne ~5986)
if (!learnersSummary.isNullOrEmpty()) {
    finalResult.reason.append("; [").append(learnersSummary).append("]")
}
```

### Étape 4 : Pour l'Auditor (Async Problem)

**Dans le callback** (lignes 6114-6175) :

```kotlin
if (modulated.appliedModulation) {
    // Ajouter à reason (thread-safe ?)
    finalResult.reason.append("; Diaby: ${modulated.modulationReason}")
    
    // Aussi dans aiAuditorModulation (déjà fait)
    finalResult.aiAuditorModulation = modulated.modulationReason
}
```

⚠️ **Attention** : `finalResult.reason` est un `StringBuilder`, pas thread-safe ! Si le callback est async, il faut synchroniser.

**Solution sécurisée** : Ne PAS modifier `reason` dans callback async, utiliser uniquement `aiAuditorModulation`.

---

## 📊 RÉSULTAT ATTENDU

### Avant

```json
{
  "reason": "BG combinedDelta faible (0.5), réduction x0.6; IOB élevé (2.5U), réduction x0.85",
  "rate": 1.2,
  "duration": 30
}
```

### Après

```json
{
  "reason": "BG combinedDelta faible (0.5), réduction x0.6; IOB élevé (2.5U), réduction x0.85; [Basal×1.05, ISF:42, React:0.95x]",
  "learnersInfo": "Basal×1.05, ISF:42, React:0.95x",
  "aiAuditorModulation": "SMB reduced by 30% due to stacking risk",
  "rate": 1.2,
  "duration": 30
}
```

---

## ⚠️ POINTS D'ATTENTION

### 1. Thread Safety

`finalResult.reason` est modifié dans plusieurs endroits :
- `setTempBasal()` (synchrone)
- Callback auditor (async)

**Risque** : Race condition si callback modifie `reason` après retour de `determine_basal()`

**Solution** : Ne modifier `reason` que de manière synchrone, utiliser champs dédiés pour info async.

### 2. Longueur de `reason`

Si on ajoute trop d'infos, `reason` devient illisible.

**Solution** : Format court avec abbréviations :
- ✅ `Basal×1.05`
- ❌ `Basal Learner multiplier: 1.05 (short-term learning)`

### 3. Retrocompatibilité

Si des parsers externes lisent `reason`, ajouter du contenu peut les casser.

**Solution** : Utiliser un séparateur clair (ex: `; [learners]`) et documenter.

---

## ✅ RECOMMANDATION FINALE

**Approche Hybride** :

1. ✅ **Ajouter `finalResult.learnersInfo`** : Champ dédié, propre
2. ✅ **Enrichir `finalResult.reason`** : Avec résumé court des learners
3. ✅ **Garder `ai AuditorModulation`** : Ne PAS toucher à `reason` depuis callback async
4. ✅ **Améliorer `consoleLog`** : Garder les logs détaillés pour debugging

**Prochaine étape** : Implémenter Étapes 1-3 ! 🚀

---

**Créé le** : 2025-12-28 22:06  
**Status** : ✅ ANALYSE COMPLÈTE - PRÊT POUR IMPLÉMENTATION
