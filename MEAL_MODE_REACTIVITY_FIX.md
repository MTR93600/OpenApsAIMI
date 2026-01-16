# 🔧 AIMI - Fix Meal Modes Reactivity & SMB Intervals

## Problèmes Identifiés

### 1️⃣ **Meal Modes Ignorent la Réactivité Apprise** 🚨 CRITIQUE
**Symptôme** : Utilisateur configure Réactivité à 7% mais obtient sur-corrections pendant les repas (BG 261, montée rapide)

**Root Cause** : 
```kotlin
// AVANT (SmbInstructionExecutor.kt lignes 181-187)
val lunchfactor = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0  // ❌ NO globalFactor!
val dinnerfactor = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0  // ❌ NO globalFactor!
...

val factors = when {
    lunchTime -> lunchfactor  // ❌ Ignorait globalFactor
    dinnerTime -> dinnerfactor  // ❌ Ignorait globalFactor
    else -> 1.0  // ❌ Ignorait globalFactor
}
```

Les meal factors (lunch, dinner, highcarb, snack, meal) étaient appliqués **SANS** multiplication par `UnifiedReactivityLearner.globalFactor`. Résultat : même si l'utilisateur avait appris une réactivité faible (0.07), les modes  meal délivraient 100% de l'insuline calculée.

**Solution Implémentée** :
```kotlin
// APRÈS (SmbInstructionExecutor.kt)
val lunchfactor = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0 * input.globalReactivityFactor  // ✅
val dinnerfactor = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0 * input.globalReactivityFactor  // ✅
...

val factors = when {
    lunchTime -> lunchfactor  // ✅ Maintenant inclut globalFactor
    dinnerTime -> dinnerfactor  // ✅ Maintenant inclut globalFactor
    else -> input.globalReactivityFactor  // ✅ Base réactive même hors meal mode
}
```

**Impact** : 
- Utilisateur avec Réactivité 7% : `lunchfactor = (100% / 100) * 0.07 = 0.07` au lieu de 1.0
- SMB lunch sera réduit de **93%** pour respecter la préférence utilisateur
- Plus de sur-corrections pendant les repas

---

### 2️⃣ **Intervalle SMB Non Respecté** ⚠️ IMPORTANT  
**Symptôme** : Utilisateur configure Intervalle SMB Snack = 12 min, mais reçoit des SMBs < 12 min d'intervalle

**Root Cause Possible** : Multiple points d'entrée pour `finalizeAndCapSMB()` qui peuvent bypasser le check d'intervalle :
1. **Advisor** (ligne 4658) - Mode automatique advisor
2. **Auto decisions** (ligne 4682) - Décisions auto
3. **Drift Terminator** (ligne 4735) - Correction de drift
4. **Normal SMB** (ligne 6069) - ✅ CELUI-CI respecte bien l'intervalle

**Investigation en cours** : Les mode

s meal peuvent déclencher des SMBs via Advisor/Auto qui ne checkent PAS `lastBolusAge > smbInterval`.

---

## 🔧 Modifications Apportées

### Fichier 1: `SmbInstructionExecutor.kt`

#### Ajout du paramètre `globalReactivityFactor`
```kotlin
data class Input(
    ...
    val cob: Float,
    val globalReactivityFactor: Double  // 🎯 NEW: From UnifiedReactivityLearner
)
```

#### Multiplication de TOUS les meal factors par globalReactivityFactor
```kotlin
val highcarbfactor = preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0 * input.globalReactivityFactor
val mealfactor = preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0 * input.globalReactivityFactor
val bfastfactor = preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0 * input.globalReactivityFactor
val lunchfactor = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0 * input.globalReactivityFactor
val dinnerfactor = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0 * input.globalReactivityFactor
val snackfactor = preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0 * input.globalReactivityFactor
val sleepfactor = preferences.get(DoubleKey.OApsAIMIsleepFactor) / 100.0 * input.globalReactivityFactor
```

#### Changement du fallback factor (when no meal mode)
```kotlin
val factors = when {
    input.highCarbTime -> highcarbfactor
    input.mealTime -> mealfactor
    input.bfastTime -> bfastfactor
    input.lunchTime -> lunchfactor
    input.dinnerTime -> dinnerfactor
    input.snackTime -> snackfactor
    input.sleepTime -> sleepfactor
    else -> input.globalReactivityFactor  // ✅ Was 1.0, now uses globalFactor
}
```

---

### Fichier 2: `DetermineBasalAIMI2.kt`

#### Passage du globalReactivityFactor au SmbInstructionExecutor
```kotlin
val smbExecution = SmbInstructionExecutor.execute(
    SmbInstructionExecutor.Input(
        ...
        cob = cob,
        globalReactivityFactor = if (preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)) {
            unifiedReactivityLearner.globalFactor
        } else 1.0  // Backwards compatible default
    ),
    ...
)
```

**Logique** :
- Si UnifiedReactivityLearner activé → utilise `globalFactor` appris (ex: 0.07 pour 7% reactivity)
- Si désactivé → fallback à 1.0 (comportement neutre, backward compatible)

---

## 📊 Exemple Concret

**Configuration Utilisateur** :
- Réactivité générale : **7%** (apprise par UnifiedReactivityLearner → `globalFactor = 0.07`)
- Mode Lunch Factor : **100%** (préférence)
- DynISF : 100%

**AVANT le fix** :
```kotlin
lunchfactor = 100 / 100 = 1.0  // ❌ Ignorait les 7%
SMB calculé = 2.0 U
SMB final = 2.0 U * 1.0 = 2.0 U  // ❌ SUR-CORRECTION!
```

**APRÈS le fix** :
```kotlin
lunchfactor = (100 / 100) * 0.07 = 0.07  // ✅ Respecte les 7%
SMB calculé = 2.0 U
SMB final = 2.0 U * 0.07 = 0.14 U  // ✅ Correctement réduit!
```

**Résultat** : **93% de réduction** de l'insuline meal mode pour respecter la réactivité apprise.

---

## ✅ Validation À Faire

### Test 1: Réactivité Meal Mode
1. Configurer UnifiedReactivityLearner à 10% (bas)
2. Activer mode Lunch (Factor 100%)
3. Déclencher repas (COB > 0, montée BG)
4. **Vérifier** : SMB lunch doit être ~10% de la valeur normale

**Log Attendu** :
```
Reactivity (> 6AM): enabled=true, factor=0.100
SMB: proposed=1.50 → damped=0.15 → quantized=0.15
```

### Test 2: Réactivité Élevée
1. Configurer UnifiedReactivityLearner à 150% (agressif)
2. Même scénario
3. **Vérifier** : SMB lunch doit être ~150% de la valeur normale

**Log Attendu** :
```
Reactivity (> 6AM): enabled=true, factor=1.500
SMB: proposed=1.00 → damped=1.50 → quantized=1.50
```

### Test 3: Intervalle SMB Snack
1. Configurer Intervalle SMB Snack = 12 min
2. Activer mode Snack
3. **Vérifier** : Aucun SMB avant 12 min depuis dernier bolus

**Log Attendu** :
```
[SMB interval=12.0 min, lastBolusAge=5.2 min, Δ=+3.5, BG=142]
Waiting 6.8m:48s for next SMB
```

---

## 🐛 Bug Visuel Overview (Non Traité)

**Symptôme** : Carte "unicor" apparaît dans l'interface normale Overview

**TODO** : Investigation requise dans :
- `app/src/main/res/layout/overview_fragment.xml`
- `ui/src/main/kotlin/app/aaps/ui/activities/fragments/OverviewFragment.kt`
- `component_status_card.xml`

Probablement un problème de condition de visibilité (`View.VISIBLE` vs `View.GONE`).

---

## 📝 Fichiers Modifiés

| Fichier | Lignes Modifiées | Type |
|---------|-----------------|------|
| `SmbInstructionExecutor.kt` | 84-86, 179-245 | ✅ Fix Critique |
| `DetermineBasalAIMI2.kt` | 5455-5460 | ✅ Fix Critique |

**Total** : 2 fichiers, ~70 lignes modifiées

---

## 🚀 Prochaines Étapes

1. ✅ **Compilation** : En cours...
2. ⏳ **Test Runtime** : Valider avec scénarios réels
3. ⏳ **Bug Overview** : Investigation + fix
4. ⏳ **Intervalle SMB** : Vérifier pourquoi bypass possible

**ETA** : Fix reactivity READY, autres bugs en investigation
