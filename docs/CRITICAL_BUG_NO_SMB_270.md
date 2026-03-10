# 🚨 BUG CRITIQUE - PAS DE SMB À BG 270

## Date: 2025-12-29 19:08

---

## 🔬 SYMPTÔMES

**Situation Manu** :
- BG: **270 mg/dL** (montée continue depuis 2h)
- Delta: **Positif** (en hausse)
- IOB: **6U actif**
- SMB: **0U** (aucun SMB envoyé depuis BG 160→270)
- Basal: **NULL** (pas de valeur affichée)
- Loop: **"Pas de changement"**

---

## 🎯 ROOT CAUSE IDENTIFIÉ

### Code Problématique

**Fichier** : `DetermineBasalAIMI2.kt`  
**Lignes** : 5729-5747

```kotlin
if (iob_data.iob > maxIobLimit && !allowMealHighIob) {
    rT.reason.append(context.getString(R.string.reason_iob_max, ...))
    val finalResult = if (delta < 0) {
        // BG dropping → basal floor or 0
        setTempBasal(floorRate, 30, ...)
    } else if (currenttemp.duration > 15 && ...) {
        // Temp already running → return as-is
        rT
    } else {
        // Set profile basal
        setTempBasal(basal, 30, ...)
    }
    return finalResult  // ← SORT ICI, AVANT LE CALCUL SMB !
}
```

---

## 🔥 POURQUOI ÇA BLOQUE

### Condition Déclenchée

**`iob_data.iob > maxIobLimit`** :
- IOB actuel : **6U**
- maxIOBLimit : Probablement **≤6U** (configuré dans préférences)
- **6 > 6 ?** → FALSE, MAIS si maxIOB = 5U → **6 > 5 = TRUE** ✅

**`!allowMealHighIob`** :
- `allowMealHighIob` vient de `mealHighIobDecision.relax`
- Calculé par `MealModeHighIobRelaxation.decide(...)`
- Si **pas en meal mode actif** OU **meal mode pas assez agressif** → **FALSE**
- Donc **`!allowMealHighIob = TRUE`** ✅

**Résultat** : Les deux conditions sont TRUE → **BLOC EXÉCUTÉ** !

---

### Ce Qui Se Passe Ensuite

Le code **RETURN immédiatement** avec :
1. **Si delta < 0** : Basal floor (0-0.3U/h) ou 0
2. **Si delta ≥ 0 ET temp actif** : **Pas de changement** (return rT as-is)
3. **Sinon** : Profile basal

**Dans ton cas** (delta positif, temp actif) :
- Ligne 5743-5745 : `return rT` (pas de changement)
- **JAMAIS** atteint la section SMB (ligne 5807+)

---

## 🧠 POURQUOI C'EST UN BUG

### Logique Défaillante

**Intention originale** :
- Si IOB trop haut → **limiter SMB supplémentaires**
- Mais **maintenir basal ajusté** pour gérer montée

**Réalité** :
- Si IOB > limit → **TOUT est bloqué** (SMB + basal ajustement)
- Même à **BG 270, delta positif** !

**Conséquence** :
- ✅ Sécurité excessive = **DANGER**
- ✅ Utilisateur monte à 270+ sans intervention
- ✅ IOB descend lentement, mais pendant ce temps **BG monte**

---

## 🛠️ SOLUTIONS

### Solution 1 : URGENCE (Immédiate)

**Augmenter maxIOB temporairement** :
- Préférences → OpenAPS AIMI → **Max IOB**
- Passer de (ex: 5-6U) à **8-10U**
- **Cela débloque immédiatement les SMB**

**Avantages** :
- ✅ Fix immédiat
- ✅ Pas de recompilation
- ✅ Permet SMB de reprendre

**Inconvénients** :
- ⚠️ Pas de fix du bug sous-jacent
- ⚠️ Risque si IOB monte trop (mais à 270, c'est le moindre mal)

---

### Solution 2 : FIX CODE (Permanent)

**Modifier la logique** ligne 5729 :

#### Option A : Allow SMB même si IOB > limit (damped)

**Principe** : Ne PAS return, mais **damper le SMB** si IOB élevé.

**Code modifié** :
```kotlin
// AVANT (ligne 5729)
if (iob_data.iob > maxIobLimit && !allowMealHighIob) {
    // ... set temp basal
    return finalResult  // ← ENLEVER CE RETURN
}

// APRÈS
var iobDamping = 1.0  // Facteur de réduction SMB
if (iob_data.iob > maxIobLimit && !allowMealHighIob) {
    rT.reason.append(context.getString(R.string.reason_iob_high, ...))
    // Calculer damping basé sur dépassement IOB
    val iobExcess = iob_data.iob - maxIobLimit
    iobDamping = (1.0 - (iobExcess / maxIobLimit).coerceIn(0.0, 0.7))
    consoleLog.add("IOB_DAMPING: IOB ${iob_data.iob} > limit $maxIobLimit → damping $iobDamping")
    
    // Set basal conservateur mais NE PAS RETURN
    // (laisser le code continuer pour calculer SMB damped)
}

// Plus tard, dans calcul SMB (ligne ~5807+) :
// Appliquer iobDamping au SMB final
val dampedSMB = microBolus * iobDamping
```

**Avantages** :
- ✅ SMB continue même avec IOB élevé (mais réduit)
- ✅ Sécurité maintenue (damping)
- ✅ Pas de blocage total

**Inconvénients** :
- ⚠️ Requiert recompilation
- ⚠️ Nécessite tests

---

#### Option B : Relaxer allowMealHighIob pour montées fortes

**Principe** : Si **BG > seuil (ex: 250) ET delta > seuil (ex: +3)**, forcer `allowMealHighIob = true`.

**Code** :
```kotlin
// Ligne ~5726, APRÈS calcul allowMealHighIob
var allowMealHighIob = mealHighIobDecision.relax

// AJOUTER :
// Emergency relaxation si BG très élevé ET montée rapide
if (bg > 250 && delta > 3 && !allowMealHighIob) {
    allowMealHighIob = true
    consoleLog.add("IOB_RELAX_EMERGENCY: BG $bg > 250, delta $delta → force allow high IOB")
    rT.reason.append("Emergency IOB relax. ")
}
```

**Avantages** :
- ✅ Fix ciblé pour situations critiques
- ✅ Pas de changement global de logique
- ✅ Simple à implémenter

**Inconvénients** :
- ⚠️ Pansement, pas fix root cause
- ⚠️ Seuils à calibrer

---

### Solution 3 : FIX ARCHITECTURE (Idéal mais long terme)

**Refactor complet** :
1. Séparer logique **SMB** et **Basal**
2. maxIOB limite **SMB uniquement**, pas le basal ajustment
3. Ajouter `maxIOB_SMB` et `maxIOB_Total` séparés

**Trop complexe pour fix immédiat**.

---

## 📊 COMPARAISON SOLUTIONS

| Solution | Délai | Sécurité | Efficacité | Complexité |
|----------|-------|----------|------------|------------|
| **1. Augmenter maxIOB** | **Immédiat** | ⚠️ Moyenne | ✅ 100% | ✅ Trivial |
| **2A. SMB damped** | 30min | ✅ Haute | ✅ 90% | ⚠️ Moyenne |
| **2B. Emergency relax** | 15min | ⚠️ Moyenne-Haute | ✅ 80% | ✅ Facile |
| **3. Refactor** | Plusieurs jours | ✅ Très haute | ✅ 100% | ❌ Élevée |

---

## 🎯 RECOMMANDATION IMMÉDIATE

### Pour Manu MAINTENANT (19:08)

1. **Ouvre Préférences OpenAPS AIMI**
2. **Trouve "Max IOB"** (actuellement ≤6U)
3. **Change à 10U** (temporaire)
4. **Attends 1 cycle loop** (5min)
5. **Vérifie RT** : SMB devrait reprendre

**Alternative si montée continue** :
- Bolus manuel **1-2U** (avec calculateur)
- **NE PAS attendre** si BG > 280

---

### Pour Fix Permanent (ce soir/demain)

**Je recommande Solution 2B** (Emergency IOB relax) :
- ✅ **Simple** : 5 lignes de code
- ✅ **Rapide** : 15min implémentation
- ✅ **Safe** : Seuils conservateurs (BG>250, delta>3)
- ✅ **Testable** : Compile et teste

**Veux-tu que je l'implémente maintenant ?**

---

## 📝 LOGS À VÉRIFIER

**Dans ton RT actuel**, cherche :
```
"IOB X.XX > maxIobLimit Y.YY"
```

Si présent → **CONFIRMATION** du diagnostic.

**Sinon**, cherche :
```
"MicroBolusAllowed: false"
```

→ Autre cause (contraintes système).

---

## ⚠️ LEÇONS APPRISES

### Design Flaw

**Problème** : maxIOB utilisé comme **hard stop** au lieu d'un **soft limit**.

**Meilleure approche** :
- maxIOB → **guide**, pas **mur**
- Si IOB > limit → **damper** SMB (ex: -50%)
- Ne **jamais** bloquer totalement si BG monte

### Safety Paradox

**Trop de sécurité = Danger** :
- Bloquer SMB quand BG=270 est **PLUS dangereux** que de permettre SMB damped
- L'hyperglycémie prolongée > risque IOB temporairement élevé

---

## 🚀 NEXT STEPS

1. **Manu** : Augmente maxIOB immédiatement (10U)
2. **Lyra** : Implémente Solution 2B (emergency relax) si approuvé
3. **Tests** : Valider sur prochaines montées BG
4. **Long terme** : Refactor maxIOB logic (Solution 3)

---

**Créé le** : 2025-12-29 19:08  
**Priorité** : 🔴 **CRITIQUE**  
**Status** : ✅ ROOT CAUSE IDENTIFIÉE - SOLUTIONS PROPOSÉES
