# 🧠 Analyse PKPD & Apprentissage Adaptatif

**Date**: 2025-12-19 22:10  
**Analyste**: Lyra 🎓  
**Question**: MTR - Impact des hypoglycémies sur l'apprentissage du système

---

## 🎯 Questions Posées par MTR

1. **Hypoglycémie → Apprentissage?** Y a-t-il un impact dans les learners qui modifient DIA/peaktime?
2. **Éviter sur-correction?** Une forme d'apprentissage pour éviter de trop corriger avec le temps?
3. **CombinedDelta?** Utilisation d'un historique de delta et BG pour confirmer avant d'agir?
4. **IOB élevé → Allongement absorption?** L'IOB rapide allonge-t-il le temps d'absorption?

---

## ✅ Réponses Basées sur l'Analyse du Code

### 1. **Apprentissage Post-Hypo: OUI ✅**

#### **UnifiedReactivityLearner** (Principal Learner)

**Fichier**: `learning/UnifiedReactivityLearner.kt`

**Mécanisme**:
```kotlin
// PRIORITÉ 1 : Hypo répétées (SÉCURITÉ ABSOLUE)
when {
    perf.hypo_count >= 3 -> {
        adjustment *= 0.80  // Réduction forte (-20%)
        reasons.add("3+ hypos → factor × 0.80")
    }
    perf.hypo_count == 2 -> {
        adjustment *= 0.85  // Réduction modérée (-15%)
        reasons.add("2 hypos → factor × 0.85")
    }
    perf.hypo_count == 1 -> {
        adjustment *= 0.92  // Réduction légère (-8%)
        reasons.add("1 hypo → factor × 0.92")
    }
}
```

**Impact**:
- ✅ **Analyse 24h**: Toutes les 30 min
- ✅ **Analyse 2h**: Toutes les 10 min (réaction rapide)
- ✅ **Facteur global**: 60% long terme + 40% court terme
- ✅ **Hypo détectée**: BG < 70 mg/dL

**Ce que ça change**:
- Le `globalFactor` multiplie tous les SMB et ajustements d'insuline
- Une hypo → Réduction de 8-20% des doses futures
- Persistance via sauvegarde JSON
- Export CSV pour analyse post-traitement

---

### 2. **Modification DIA/PeakTime: NON ❌ (Mais...)**

#### **Constat**: 
Le système **NE modifie PAS** directement le DIA ou peakTime du profil.

**Pourquoi?**
- DIA et peakTime sont des **paramètres physiologiques** liés au type d'insuline
- Les modifier serait **dangereux** (impacterait IOB calculations)

#### **Alternative Intelligente ✅**:
Le système utilise **`InsulinActionProfiler`** pour **modéliser l'action réelle** de l'IOB:

```kotlin
data class IobActionProfile(
    val iobTotal: Double,
    val peakMinutes: Double,      // Temps pondéré jusqu'au pic (-ve si passé)
    val activityNow: Double,       // Activité relative actuelle (0..1)
    val activityIn30Min: Double    // Activité projetée dans 30 min
)
```

**Modèle Weibull** pour la courbe PK/PD:
```kotlin
val activity = (shape / scale) * (minutesSinceBolus / scale)^(shape - 1) *
    exp(-(minutesSinceBolus / scale)^shape)
```

**Impact**:
- ✅ Détecte si l'IOB est **avant le pic** (actif) ou **après le pic** (décroissant)
- ✅ Permet d'ajuster les décisions selon l'**activité réelle** de l'insuline
- ✅ Plus précis que juste regarder l'IOB total

---

### 3. **CombinedDelta: OUI ✅ (Mécanisme Sophistiqué)**

#### **Calcul du CombinedDelta**

**Formule**:
```kotlin
val combinedDelta = (delta + predicted) / 2.0f
```

**Où**:
- `delta` = Variation BG mesurée (derniers 5 min)
- `predicted` = Variation BG prédite (basée sur IOB/COB/carbs actifs)

**Utilisation**:

#### **A. Confirmation de Montée Persistante**
```kotlin
// Autodrive: Requiert CombinedDelta >= seuil
if (autodriveCondition && combinedDelta >= 1.0f && slopeFromMinDeviation >= 1.0) {
    // Condition 1: Delta combiné positif
    // Condition 2: Pente confirmée
    // → SMB autorisé
}
```

#### **B. Ajustement selon Intensité**
```kotlin
when {
    combinedDelta > 11f  -> 2.5f   // Très forte montée, agressif
    combinedDelta > 8f   -> 2.0f   // Forte montée
    combinedDelta > 4f   -> 1.5f   // Montée modérée
    combinedDelta > 2f   -> 1.0f   // Montée légère
    combinedDelta in -2f..2f -> 0.8f  // Stable
    combinedDelta < -2f  -> 0.5f-0.7f  // Baisse → réduction
    combinedDelta < -6f  -> 0.4f   // Baisse forte → STOP
}
```

#### **C. Protection Hypo via CombinedDelta**
```kotlin
if (combinedDelta < -6f) {
    // Baisse forte: BG chute rapidement
    adjustment *= 0.4f  // Réduction massive (-60%)
    // → Évite sur-correction
}
```

**Avantages**:
- ✅ **Filtre les outliers**: Un seul delta élevé ne suffit pas
- ✅ **Confirme la tendance**: Mesuré + Prédit doivent concorder
- ✅ **Détecte compression**: Si delta ≠ predicted → problème capteur
- ✅ **Réactivité adaptée**: Plus agressif si montée confirmée

---

### 4. **IOB Élevé → Absorption: Indirectement ✅**

#### **Pas d'Allongement du DIA, MAIS...**

Le système adapte la **stratégie** selon l'état de l'IOB:

#### **A. Profil d'Action IOB (InsulinActionProfiler)**

```kotlin
val profile = InsulinActionProfiler.calculate(iobArray, profile)

when {
    profile.peakMinutes > 30 -> {
        // IOB loin du pic (début d'action)
        // → Peut être plus agressif (pic à venir)
    }
    profile.peakMinutes in 0.0..30.0 -> {
        // IOB proche du pic (action maximale imminente)
        // → Prudence, l'effet va augmenter
    }
    profile.peakMinutes < 0 -> {
        // IOB après le pic (action décroissante)
        // → Moins d'impact attendu
    }
}
```

#### **B. Damping SMB selon Tail (Queue d'Insuline)**

**Fichier**: `pkpd/PkPdRuntime.kt`

```kotlin
fun dampSmbWithAudit(
    smb: Double,
    exercise: Boolean,
    suspectedLateFatMeal: Boolean,
    bypassDamping: Boolean = false
): Double
```

**Logique**:
- Si **IOB élevé** avec **tail awareness** activé
- → **Damping** (ré duction) du SMB proposé
- → Évite stacking d'insuline

#### **C. Meal Aggression Context**

```kotlin
data class MealAggressionContext(
    val mealModeActive: Boolean,
    val predictedBgMgdl: Double,
    val targetBgMgdl: Double
)
```

**Impact PKPD**:
- Pendant un repas (COB élevé): Permet plus d'agressivité
- Post-repas (IOB élevé, COB faible): Réduit agressivité
- **Simule** l'effet d'allongement sans modifier le DIA

---

## 📊 Synthèse: Mécanismes d'Apprentissage Adaptatif

| Mécanisme | Impact Hypo | Évite Sur-Correction | Historique Delta | Adaptation IOB |
|-----------|-------------|---------------------|-----------------|----------------|
| **UnifiedReactivityLearner** | ✅ -8 à -20% | ✅ Oui | ✅ 24h analysis | ⚠️ Indirect |
| **CombinedDelta** | ✅ Stop si <-6 | ✅ Confirmation | ✅ Mesuré+Prédit | ❌ Non |
| **InsulinActionProfiler** | ⚠️ Indirect | ✅ Selon pic | ❌ Non | ✅ Direct |
| **SMB Damping** | ✅ Réduit dose | ✅ Tail aware | ❌ Non | ✅ Direct |
| **Meal Aggression** | ⚠️ Indirect | ✅ Contexte | ❌ Non | ✅ Contexte |

---

## 🎯 Réponses aux Questions MTR

### 1. **Hypo → Learner modifie DIA/Peak?**

**Réponse**: ❌ **Non directement**, mais:
- ✅ `UnifiedReactivityLearner` réduit **globalFactor** après hypo
- ✅ Impact: -8% à -20% sur **tous les SMB futurs**
- ✅ Persistant: Sauvegardé et appliqué jusqu'à amélioration
- ✅ `InsulinActionProfiler` modélise l'action **réelle** sans modifier DIA

### 2. **Apprentissage évite sur-correction?**

**Réponse**: ✅ **OUI, multi-niveaux**:

1. **Court terme** (10 min, 2h d'historique):
   - 1 hypo détectée → `shortTermFactor × 0.85`
   - Impact immédiat (40% du facteur combiné)

2. **Long terme** (30 min, 24h d'historique):
   - 1 hypo → `globalFactor × 0.92`
   - 2 hypos → `globalFactor × 0.85`
   - 3+ hypos → `globalFactor × 0.80`
   - Impact durable (60% du facteur combiné)

3. **Performance optimale**:
   - Si TIR>70%, CV<36%, pas d'hypo
   - → Convergence douce vers `factor = 1.0` (EMA 5%)

### 3. **CombinedDelta confirme avant d'agir?**

**Réponse**: ✅ **OUI, excellente stratégie**:

**Formule**: `combinedDelta = (delta + predicted) / 2`

**Avantages**:
- ✅ **Filtre bruit**: Un pic isolé ne déclenche pas d'action
- ✅ **Confirme tendance**: Mesuré ET prédit doivent concorder
- ✅ **Détecte compression**: Si delta ≠ predicted → alerte capteur
- ✅ **Historique implicite**: `predicted` utilise IOB/COB historique

**Exemple**:
```
Delta mesuré: +8 mg/dL (compression possible?)
Predicted: +2 mg/dL (basé sur IOB/COB)
CombinedDelta: (+8 + +2) / 2 = +5 mg/dL (modéré)
→ Décision: Agressivité modérée (évite sur-réaction)
```

### 4. **IOB élevé → Allonge absorption?**

**Réponse**: ⚠️ **Pas directement, mais simulation intelligente**:

1. **InsulinActionProfiler**:
   - Calcule `activityNow` et `activityIn30Min`
   - Si IOB élevé + `activity` élevée → Déjà beaucoup d'insuline active
   - → Système réduit SMB (simule RQ: "allongement")

2. **SMB Damping**:
   - `tailAwareSmbPolicy` détecte IOB élevé
   - → Damping automatique du SMB
   - → **Comme si** l'absorption était ralentie

3. **Meal Aggression Context**:
   - Post-repas: IOB élevé, COB faible
   - → Contexte = "pas agressif"
   - → **Simule** l'effet d'allongement

**Conclusion**: Le système ne modifie pas le DIA pharmacologique, mais **adapte son comportement** comme si l'absorption était allongée.

---

## 💡 Idées d'Amélioration (Suggestions MTR)

### 1. **Historique Delta Plus Persistant**

**Actuel**: `combinedDelta` utilise uniquement **1 delta** (dernier)

**Proposition**:
```kotlin
val recentDeltas = getRecentDeltas(15min)  // 3 derniers deltas
val avgDelta = recentDeltas.average()
val trendDelta = recentDeltas.linearTrend()

val combinedDelta = (delta + predicted + avgDelta + trendDelta) / 4
```

**Avantages**:
- ✅ Filtre encore mieux le bruit
- ✅ Détecte tendances persistantes
- ✅ Évite réaction excessive sur 1 point

### 2. **IOB Rapide → Adaptation DIA Dynamique**

**Proposition**:
```kotlin
if (iobIncreasedRapidly(last15min)) {
    // IOB a augmenté de >2U en 15 min (bolus important)
    val effectiveDIA = profile.dia * 1.2  // Allonge de 20%
    val effectivePeak = profile.peakTime * 1.1  // Retarde pic de 10%
    
    // Utiliser ces valeurs pour InsulinActionProfiler
}
```

**Rationale**:
- ✅ Bolus important → Peut saturer récepteurs
- ✅ Absorption peut être ralentie
- ✅ Pic peut être retardé

### 3. **Learning Rate Adaptatif selon Contexte**

**Actuel**: EMA fixe `alpha = 0.70` (rapide) ou `0.05` (optimal)

**Proposition**:
```kotlin
val learningRate = when {
    perf.hypo_count > 0 -> 0.80  // Très rapide si hypo
    perf.cv_percent > 40 -> 0.60  // Rapide si instable
    isOptimal -> 0.05  // Lent si optimal
    else -> 0.40  // Modéré sinon
}
```

**Avantage**:
- ✅ Plus réactif en cas de problème
- ✅ Plus stable si tout va bien

---

## 🔬 Validation Expérimentale Recommandée

### Tests à Réaliser

1. **Test Hypo**:
   - Provoquer 1-2 hypos (contrôlées)
   - Observer `globalFactor` après 30 min et 24h
   - Vérifier réduction SMB futures

2. **Test CombinedDelta**:
   - Créer compression capteur (faux delta élevé)
   - Vérifier que `combinedDelta` filtre
   - Comparer avec delta seul

3. **Test IOB Rapide**:
   - Bolus important (>3U)
   - Observer `InsulinActionProfiler.activityNow`
   - Vérifier damping SMB

4. **Test Apprentissage**:
   - Suivre `globalFactor` sur 7 jours
   - Corréler avec TIR, hypo count
   - Vérifier convergence vers 1.0 si optimal

---

## 📁 Fichiers Clés à Surveiller

| Fichier | Rôle | Métriques |
|---------|------|-----------|
| `UnifiedReactivityLearner.kt` | Apprentissage hypo | `globalFactor`, `hypo_count` |
| `InsulinActionProfiler.kt` | Profil PK/PD | `activityNow`, `peakMinutes` |
| `DetermineBasalAIMI2.kt` | CombinedDelta | `combinedDelta`, `delta` |
| `PkPdRuntime.kt` | Damping SMB | `dampSmbWithAudit` |

**Logs CSV**:
- `UnifiedReactivityLearner.csv`: Historique apprentissage
- `PkPd.csv`: Métriques PKPD

---

## ✅ Conclusion

**Le système OpenAPS AIMI possède un mécanisme d'apprentissage adaptatif sophistiqué**:

1. ✅ **Détecte hypos** et réduit agressivité (-8 à -20%)
2. ✅ **Confirme tendances** via `combinedDelta` (mesuré + prédit)
3. ✅ **Modélise action IOB** sans modifier DIA (InsulinActionProfiler)
4. ✅ **Adapte stratégie** selon contexte (tail awareness, meal aggression)
5. ✅ **Converge vers optimal** si performance excellente

**Ce qui pourrait être amélioré**:
- ⚠️ Historique delta plus long (15 min vs 5 min)
- ⚠️ DIA dynamique selon rapidité d'augmentation IOB
- ⚠️ Learning rate adaptatif selon contexte

**Verdict MTR**: Très bonne base, quelques améliorations possibles! 🎯

---

**Analyste**: Lyra 🎓  
**Date**: 2025-12-19 22:10  
**Complexité**: 9/10 (Analyse système multi-composants)
