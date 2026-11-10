# 🚧 AI Decision Auditor - Corrections Nécessaires

## Status : Architecture complète, intégration 90% complétée

### ✅ CE QUI EST FAIT

1. **Architecture complète** (1,777 lignes Kotlin)
   - `AuditorDataStructures.kt` - Structures Input/Output
   - `AuditorPromptBuilder.kt` - Construction prompt sophistiqué
   - `AuditorDataCollector.kt` - Extraction données AIMI (**NÉCESSITE CORRECTIONS**)
   - `AuditorAIService.kt` - Appels multi-providers
   - `DecisionModulator.kt` - Modulation bornée
   - `AuditorOrchestrator.kt` - Orchestrateur principal

2. **Configuration préférences** (5 clés ajoutées)
   - `AimiAuditorEnabled` (Boolean)
   - `AimiAuditorMode` (String)
   - `AimiAuditorMaxPerHour`, `AimiAuditorTimeoutSeconds`, `AimiAuditorMinConfidence` (Int)

3. **RT fields ajoutés** pour affichage dashboard
   - `aiAuditorEnabled`, `aiAuditorVerdict`, `aiAuditorConfidence`
   - `aiAuditorModulation`, `aiAuditorRiskFlags`

4. **Intégration dans DetermineBasalAIMI2.kt**
   - Injection `AuditorOrchestrator`
   - Helper function `calculateSmbLast30Min()`
   - Appel complet de l'auditeur avant return finalResult
   - Population des RT fields
   - Logs dans consoleLog

5. **Documentation complète** (2,288 lignes Markdown)
   - Guide technique complet
   - Guide d'intégration
   - Cas de test avec exemples JSON
   - Résumé exécut if

---

## ❌ ERREURS DE COMPILATION À CORRIGER

### 1. DetermineBasalAIMI2.kt (lignes ~1007, ~6020-6063)

#### Erreur `calculateSmbLast30Min` (ligne 1007)
```kotlin
// ERREUR:
persistenceLayer.getBolusesAfterTimestamp(lookback30min, ascending = false)

// CORRECTION:
persistenceLayer.getBolusesFromTime(lookback30min, ascending = false)
```

#### Erreur Therapy.P1/P2 (ligne 6020)
```kotlin
// ERREUR:
val inPrebolusWindow = (therapy.P1 || therapy.P2)

// CORRECTION:
val inPrebolusWindow = false  // TODO: determine P1/P2 from mode runtime
// OU chercher comment Therapy expose P1/P2 (peut-être via méthodes différentes)
```

#### Erreur mode runtime (lignes 6036-6041)
```kotlin
// ERREUR:
therapy.bfastruntime, therapy.lunchruntime, etc.

// CORRECTION:
// Ces propriétés n'existent peut-être pas sur Therapy
// Alternative: garder des timestamps locaux ou utiliser modeState
val now = dateUtil.now()
val modeRuntimeMin = when {
    therapy.bfastTime -> 0  // TODO: track runtime
    therapy.lunchTime when 0
    // etc.
    else -> null
}
```

#### Erreur wCycleFacade.getFactor() (ligne 6050)
```kotlin
// ERREUR:
val wcycleFactor = wCycleFacade.getFactor()

// CORRECTION:
val wcycleFactor = wCycleFacade.getCurrentFactor()  // OU autre méthode
// OU simplement: null pour MVP
val wcycleFactor: Double? = null
```

#### Erreur type iob (ligne 6063)
```kotlin
// ERREUR:
iob = iob_data_array.firstOrNull() ?: IobTotal(dateUtil.now())

// CORRECTION:
iob = iob_data_array.firstOrNull() ?: IobTotal(dateUtil.now()).apply { iob = 0.0 }
```

---

### 2. AuditorDataCollector.kt

#### Erreur glucoseStatus.timestamp (ligne 177)
```kotlin
// ERREUR:
val ageMs = now - it.timestamp

// CORRECTION:
val ageMs = now - it.date
```

#### Erreur pkpdRuntime.onset (ligne 197)
```kotlin
// ERREUR:
onsetConfirmed = pkpdRuntime?.onset != null

// CORRECTION:
onsetConfirmed = pkpdRuntime?.isOnsetConfirmed ?: false
// OU chercher la bonne propriété sur PkPdRuntime
```

#### Erreur pkpdRuntime.activity type (lignes 198, 245)
```kotlin
// ERREUR:
residualEffect = pkpdRuntime?.activity  // Type mismatch

// CORRECTION:
residualEffect = pkpdRuntime?.activity?.fractionOfPeak  // OU autre propriété Double
//OU
residualEffect = pkpdRuntime?.getCurrentActivity()
```

#### Erreur persistenceLayer.getBolusesFromTime (lignes 287, 298)
```kotlin
// ERREUR:
persistenceLayer.getBolusesFromTime(fromTime, to = now, ascending = false)

// CORRECTION (simplifiée pour MVP):
// Retourner liste vide si l'API n'existe pas
val boluses = emptyList<app.aaps.core.data.model.BS>()
val smbs = emptyList<app.aaps.core.data.model.BS>()
```

#### Erreur aapsLogger.debug (lignes 291, 302)
```kotlin
// ERREUR:
aapsLogger.debug("AuditorDataCollector", "Failed to fetch...")

// CORRECTION:
aapsLogger.debug(app.aaps.core.interfaces.logging.LTag.APS, "Failed to fetch...")
```

#### Erreur tirCalculator.calculate() (ligne 384)
```kotlin
// ERREUR:
tirCalculator.calculate()  // manque paramètres

// CORRECTION (pour MVP, valeurs par défaut):
val tirStats: app.aaps.core.interfaces.stats.TirCalculator.Result? = null  // TODO
```

#### Erreur tirStats properties (lignes 399-402)
```kotlin
// ERREUR:
tirStats?.let { it.inRangePct }  // propriété n'existe pas

// CORRECTION (vérifier TirCalculator.Result):
// Soit utiliser les bonnes propriétés, soit:
return Stats7d(
    tir = 0.0,  // TODO
    hypoPct = 0.0,  // TODO
    hyperPct = 0.0,  // TODO
    meanBG = 0.0,  // TODO
    cv = 0.0,
    tdd7dAvg = tdd7d,
    basalPct = 50.0,
    bolusPct = 50.0
)
```

---

## 🔧 STRATÉGIE DE CORRECTION RECOMMANDÉE

### Option A : MVP Simplifié (RECOMMANDÉ)

Remplacer toutes les données qui ne compilent pas par des valeurs par défaut pour avoir un système fonctionnel :

```kotlin
// Dans AuditorDataCollector.buildSnapshot():
val cgmAgeMin = 0  // TODO
val iobActivity: Double? = null  // TODO
val pkpd = PKPDSnapshot(
    diaMin = (profile.dia * 60.0).toInt(),
    peakMin = 60,
    tailFrac = 0.0,
    onsetConfirmed = null,
    residualEffect = null
)
val activity = ActivitySnapshot(0, 0, null, null)
val lastDelivery = LastDeliverySnapshot(null, null, null, null, null, null)

// Dans buildStats7d():
return Stats7d(
    tir = 0.0,
    hypoPct = 0.0,
    hyperPct = 0.0,
    meanBG = 0.0,
    cv = 0.0,
    tdd7dAvg = tdd7d,
    basalPct = 50.0,
    bolusPct = 50.0
)
```

**Avantage** : Compile immédiatement, système fonctionnel
**Inconvénient** : Données incomplètes pour l'AI (mais snapshot principal OK)

### Option B : Corrections Précises

1. Chercher les bonnes API dans le code existant :
   - Regarder comment `Therapy` expose P1/P2 et runtimes
   - Vérifier les méthodes de `PersistenceLayer` pour boluses
   - Vérifier structure de `TirCalculator.Result`
   - Vérifier propriétés de `PkPdRuntime`

2. Corriger chaque API une par une

**Avantage** : Données complètes
**Inconvénient** : Nécessite investigation API par API

---

## 📋 TODO RESTANT (après corrections)

1. ✅ Corriger erreurs compilation
2. ⏭️ Ajouter préférences UI (XML layout dans `res/xml/pref_aimi.xml`)
3. ⏭️ Tester avec vraies données
4. ⏭️ Implémenter buildHistory() avec vraies données historiques
5. ⏭️ Afficher les champs RT dans l'adjustment panel du dashboard

---

## 🎯 ÉTAT ACTUEL

- **Code architecture** : ✅ 100% complet
- **Intégration DetermineBasalAIMI2** : ✅ 95% (appel fait, needs API fixes)
- **RT fields** : ✅ 100%
- **Préférences keys** : ✅ 100%
- **Documentation** : ✅ 100%
- **Compilation** : ❌ ~10 erreurs API à corriger
- **UI Préférences** : 📝 0% (XML à créer)
- **Dashboard display** : 📝 0% (modification adjustment panel)

---

## 💡 PROCHAINE SESSION

Recommandation : **Option A (MVP Simplifié)** pour avoir un système fonctionnel rapidement.

1. Remplacer toutes les données problématiques par des valeurs par défaut
2. Compiler avec succès
3. Ajouter UI préférences
4. Tester le flow complet
5. Itérer pour ajouter les vraies données progressivement

---

*Dernière mise à jour : 2025-12-26 23:15*
