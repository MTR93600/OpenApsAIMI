# 🐛 Meal Advisor Calcul Problème - netNeeded = 0

**Date**: 2025-12-19 20:17  
**Screenshot**: uploaded_image_1766171831896.png  
**Status**: 🔍 INVESTIGATION

---

## 📸 Observation

### Capture d'écran
- **BG**: 105 mg/dL (+6 mg/dl)
- **IOB**: 2.75U
- **COB**: 0g
- **Mode**: Meal Advisor actif
- **TBR envoyé**: ✅ 7.00 U/h pour 30 min
- **SMB envoyé**: ❌ AUCUN

### Notifications visibles
```
@ 7:42 PM, Ajustements: Maxlob 20,00 UPose temp à 7,00 U/h pour 30 minutes.
⬆️ Autodrive: ✔ | Mode collation / pré-bolus: Meal Advisor |

@7:32 PM, Temp Basal Started 14.00 for 30m...
Mode collation / pré-bolus: Meal Advisor |
```

**Constat**: Seul le TBR est envoyé, **pas de SMB**.

---

## 🔍 Analyse Racine

### Formule Actuelle (ligne 6032-6035)

```kotlin
val insulinForCarbs = estimatedCarbs / profile.carb_ratio
val coveredByBasal = safeMax * 0.5  // 30min coverage
val netNeededRaw = insulinForCarbs - iobData.iob - coveredByBasal
val netNeeded = netNeededRaw.coerceAtLeast(0.0)
```

### Calcul avec valeurs estimées

Supposons:
- `estimatedCarbs` = 50g
- `IC ratio` = 10
- `IOB` = 2.75U
- `TBR` = 7.0 U/h

**Étapes**:
1. `insulinForCarbs` = 50 / 10 = **5.0U**
2. `coveredByBasal` = 7.0 × 0.5 = **3.5U**
3. `netNeededRaw` = 5.0 - 2.75 - 3.5 = **-1.25U**
4. `netNeeded` = **0.0U** (après coerceAtLeast)

**Résultat**: bolusU = 0.0 → **Aucun SMB envoyé** ❌

---

## ❌ Problème Identifié

### 1. **Double Comptage de l'Insuline**

La formule actuelle **soustrait** à la fois:
- ✅ **IOB existant** (correct - évite le stacking)
- ❌ **TBR coverage** (problématique - le TBR va être envoyé!)

**Cercle vicieux**:
- Le TBR de 7.0 U/h va délivrer 3.5U sur 30 min
- Mais on soustrait ces 3.5U du bolus **avant même que le TBR soit actif**
- Résultat: Le bolus est réduit à 0, et seul le TBR est envoyé

### 2. **Incohérence Conceptuelle**

Si le TBR **remplace** le bolus:
- ❌ Pourquoi envoyer un TBR si on veut un **prebolus** (action rapide)?
- ❌ Le TBR prend du temps à agir (30 min), le prebolus est immédiat

Si le TBR **complète** le bolus:
- ✅ Le bolus donne l'insuline immédiate
- ✅ Le TBR fournit un soutien continu
- ❌ **Mais alors il ne faut PAS soustraire la coverage du bolus!**

---

## 🎯 Solutions Proposées

### **Option A: TBR comme Complément (Recommandée)**

**Logique**: Le SMB est le prebolus principal, le TBR est un support supplémentaire.

```kotlin
val insulinForCarbs = estimatedCarbs / profile.carb_ratio
val netNeeded = (insulinForCarbs - iobData.iob).coerceAtLeast(0.0)

// TBR séparé (complément)
val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal

return DecisionResult.Applied(
    source = "MealAdvisor",
    bolusU = netNeeded,        // SMB basé sur carbs - IOB seulement
    tbrUph = safeMax,          // TBR agressif en parallèle
    tbrMin = 30,
    reason = "📸 Meal Advisor: ${estimatedCarbs.toInt()}g -> ${"%.2f".format(netNeeded)}U + TBR"
)
```

**Avantages**:
- ✅ Le SMB est envoyé même avec IOB modéré
- ✅ Le TBR fournit une couverture continue
- ✅ Pas de "annulation" entre les deux

**Exemple avec vos valeurs**:
- insulinForCarbs = 5.0U
- IOB = 2.75U
- **netNeeded** = 5.0 - 2.75 = **2.25U** → ✅ SMB ENVOYÉ!
- TBR = 7.0 U/h × 30 min = 3.5U → ✅ TBR ENVOYÉ!

---

### **Option B: Répartition Intelligente**

**Logique**: Répartir l'insuline totale entre SMB (immédiat) et TBR (continu).

```kotlin
val insulinForCarbs = estimatedCarbs / profile.carb_ratio
val totalNeeded = (insulinForCarbs - iobData.iob).coerceAtLeast(0.0)

// Répartition: 60% SMB (immédiat), 40% TBR (30 min)
val smbPortion = totalNeeded * 0.6
val tbrPortion = totalNeeded * 0.4
val tbrRate = (tbrPortion / 0.5).coerceAtMost(profile.max_basal) // 0.5h = 30min

return DecisionResult.Applied(
    source = "MealAdvisor",
    bolusU = smbPortion,
    tbrUph = max(tbrRate, profile.current_basal), // Au moins basal actuel
    tbrMin = 30,
    reason = "📸 Meal Advisor: ${estimatedCarbs.toInt()}g -> SMB ${"%.2f".format(smbPortion)}U + TBR ${"%.1f".format(tbrRate)}U/h"
)
```

**Avantages**:
- ✅ Garantit qu'un SMB est toujours envoyé (si totalNeeded > 0)
- ✅ Répartition équilibrée entre immédiat et continu
- ✅ TBR adapté au besoin réel

**Exemple avec vos valeurs**:
- totalNeeded = 2.25U
- SMB = 2.25 × 0.6 = **1.35U** → ✅ ENVOYÉ!
- TBR = (2.25 × 0.4) / 0.5 = **1.8 U/h** → ✅ ENVOYÉ!

---

### **Option C: Minimum Garanti + TBR Coverage (Conservatrice)**

**Logique**: Garantir un SMB minimum même si le calcul donne 0.

```kotlin
val insulinForCarbs = estimatedCarbs / profile.carb_ratio
val coveredByBasal = safeMax * 0.5
val netNeededRaw = insulinForCarbs - iobData.iob - coveredByBasal
val netNeeded = max(netNeededRaw, 0.5)  // Minimum 0.5U si Advisor actif

return DecisionResult.Applied(
    source = "MealAdvisor",
    bolusU = netNeeded,
    tbrUph = safeMax,
    tbrMin = 30,
    reason = "📸 Meal Advisor: ${estimatedCarbs.toInt()}g -> ${"%.2f".format(netNeeded)}U"
)
```

**Avantages**:
- ✅ Simple (modification minimale)
- ✅ Garantit un prebolus minimum
- ✅ Conserve la logique actuelle de coverage

**Inconvénients**:
- ⚠️ Peut donner trop d'insuline si IOB déjà élevé
- ⚠️ Le "minimum forcé" peut créer des hypos

---

## 📊 Comparaison des Options

| Critère | Option A | Option B | Option C |
|---------|----------|----------|----------|
| **SMB toujours envoyé** | ✅ (si IOB<carbs/IC) | ✅ (si netNeeded>0) | ✅ (forcé 0.5U) |
| **Sécurité** | ✅ Basé sur IOB | ✅ Basé sur IOB | ⚠️ Forcé minimum |
| **Simplicité** | ✅ Simple | ⚠️ Complexe | ✅ Très simple |
| **Logique** | ✅ Cohérente | ✅ Cohérente | ⚠️ Arbitraire |
| **Risque Hypo** | ✅ Faible | ✅ Faible | ⚠️ Moyen |

---

## 🎯 Recommandation

**Je recommande l'Option A** car:
1. ✅ **Logique claire**: Le TBR est un complément, pas un substitut
2. ✅ **Sécurité maintenue**: IOB est toujours vérifié
3. ✅ **Efficacité**: SMB donne l'action immédiate (prebolus), TBR soutient
4. ✅ **Simplicité**: Modification minimale, facile à tester

---

## 🧪 Validation Nécessaire

Avec les logs ajoutés, vous devriez voir:
```
ADVISOR_CALC carbs=50g IC=10.0 → 5.00U
ADVISOR_CALC IOB=2.75U TBR_coverage=3.50U (7.0U/h × 0.5)
ADVISOR_CALC netRaw=-1.25U → net=0.00U delta=+6.0 modesOK=true
```

**Confirme le diagnostic**: `netRaw < 0` → `net = 0` → **Aucun SMB**.

---

## 🔄 Action Requise

1. **Confirmer les logs** sur la prochaine exécution
2. **Choisir une option** (A, B, ou C)
3. **Implémenter le fix**
4. **Tester** avec scénario réel

---

**Analyst**: Lyra 🎓  
**Priority**: 🔴 HIGH (Feature ne fonctionne pas selon spec)
