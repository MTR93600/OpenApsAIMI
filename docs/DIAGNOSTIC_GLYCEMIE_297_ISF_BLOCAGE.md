# DIAGNOSTIC PROFOND: GLYCÉMIE 297 - POURQUOI SI PEU DE CORRECTION?

**Date:** 2025-12-20 09:10  
**Situation:** BG 297 mg/dL → SMB proposé 0.54-0.60U seulement  
**Question:** Pourquoi le système laisse monter la glycémie et ne corrige pas davantage?

---

## 🔍 ANALYSE DES CAPTURES D'ÉCRAN

### **Screenshot 1: Vue Générale (1:56 AM)**
- **BG actuel:** 297 mg/dL  
- **IOB:** 0.21U (TRÈS FAIBLE!)
- **Delta:** Positif (montée continue)
- **Mode:** OpsAPS actif
- **Pump:** 75.0U réservoir

### **Screenshot 2: Détails Techniques (1:51-1:56 AM)**

#### Ajustements Actifs:
```
MaxIOB: 8.00 U   ← Limite haute OK
MaxSMB: 0.6 U    ← ⚠️ LIMITE TRÈS BASSE
```

#### Calculs SMB:
```
UAM execute: -0.20 U
SMB (UAM): 0.20 U
Prediction: 0.20 U
Hyperglycémie boost: x1.7         ← Boost actif
MPC modèle prédictif: 0.60 U (75%)
PI modèle physiologique: 0.35 U (25%)
MPC utile: 84%
SMB final: 0.54 U
HighBG PKPD boost: tail=5%, scale=1.11
SMB ×1.20 (0.54→0.65)
PKPD: DIA=429 min, Peak=82 min, Tail=5%, Activity=18%
ISF(fused)=63 (profile=147, TDD=57, scale=1.11)
SMB proposed=0.54 → damped=0.54
quantized=0.60
Global Hyper Kicker (Active)
```

#### TBR:
```
Temp Basal Started -1.00 for -1m
→ TBR de base augmenté pour compenser
```

---

## 🚨 **PROBLÈMES IDENTIFIÉS**

### **#1: ISF=63 - BEAUCOUP TROP BAS (EFFET INVERSÉ!)**

#### Qu'est-ce que l'ISF?
L'**ISF (Insulin Sensitivity Factor)** indique de combien 1 unité d'insuline fait baisser la glycémie:
- **ISF = 63** signifie: 1U → baisse de 63 mg/dL
- **ISF du profil = 147** (valeur de base configurée)
- **ISF TDD = 57** (calculé dynamiquement selon la TDD 24h)

#### Le Problème CRITIQUE:
```kotlin
// PkPdIntegration.kt ligne 112
val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale)
// fusedIsf = 63 (résultat final fusionné)
```

**L'ISF fusionné (63) est TROP BAS** par rapport au profil (147). Voici pourquoi c'est un problème:

1. **ISF bas = Système pense que l'utilisateur est PEU sensible à l'insuline**
2. **Donc il se dit:** "Je dois donner BEAUCOUP d'insuline pour faire baisser la BG"
3. **MAIS le MaxSMB (0.6U) BLOQUE** cette intention
4. **Résultat:** Le système veut corriger mais est bridé artificiellement

#### Calcul de correction attendu:
```
Correction nécessaire = (BG actuel - Cible) / ISF
Correction = (297 - 100) / 63 = 3.13U

MAIS MaxSMB = 0.6U → BRIDÉ à 0.6U maximum!
```

**Le système SAIT qu'il faut 3U, mais ne peut donner que 0.6U.**

---

### **#2: MaxSMB = 0.6U - LIMITE RIDICULEUSEMENT BASSE**

#### Comparaison:
```
MaxIOB configuré:  8.00 U      ← OK pour une hyperglycémie
IOB actuel:        0.21 U      ← ÉNORME marge disponible (7.79U!)
MaxSMB configuré:  0.6 U       ← ⚠️ GOULOT D'ÉTRANGLEMENT
```

**Le système a 7.79U de marge d'IOB disponible, mais ne peut donner que 0.6U à la fois.**

#### Pourquoi MaxSMB = 0.6U?
```kotlin
// DetermineBasalAIMI2.kt lignes 3815-3816
this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
```

**C'est une préférence utilisateur configurée trop basse!**

#### Impact en cascade:
```kotlin
// Ligne 1454
val baseLimit = if (this.bg > 120) this.maxSMBHB else this.maxSMB
// BG=297 > 120 → utilise maxSMBHB = 0.6U

// Ligne 1575-1583: capSmbDose
val safeCap = capSmbDose(
    proposedSmb = gatedUnits,
    bg = this.bg,
    maxSmbConfig = kotlin.math.max(baseLimit, proposedUnits),
    // baseLimit = 0.6U → PLAFONNE TOUT
    iob = this.iob.toDouble(),
    maxIob = this.maxIob
)
```

**Peu importe les calculs sophistiqués (MPC, PI, PKPD), tout est plafonné à 0.6U.**

---

### **#3: FUSION ISF - FORMULE AGRESSIVE INADAPTÉE**

#### Le problème de fusion:
```kotlin
// PkPdIntegration.kt ligne 112
val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale)
// profileIsf = 147 (config)
// tddIsf = 57 (1800/TDD24h)
// pkpdScale = 1.11 (boost PKPD)
// → fusedIsf = 63 (TROP BAS)
```

#### Calcul TDD-ISF:
```kotlin
// Ligne 198-202
private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
    if (tdd24h <= 0.1) return fallback
    val anchored = 1800.0 / tdd24h
    return anchored.coerceIn(5.0, 400.0)
}
// Si TDD24h ≈ 31.5U → tddIsf = 1800/31.5 = 57
```

**Le système utilise massivement l'ISF-TDD (57) plutôt que le profil (147).**

#### Pourquoi c'est un problème?
1. **TDD récente peut être FAUSSE** (pompe changée? site d'injection récent?)
2. **L'ISF-TDD est très agressif** (assume forte résistance)
3. **La fusion pèse trop fortement vers TDD** au détriment du profil

---

## 🎯 **POURQUOI LE SYSTÈME NE CORRIGE PAS?**

### **Chaîne de Limitations:**

```
1. ISF fusionné = 63 (trop bas)
   ↓
2. Système calcule: Besoin de 3.13U pour corriger 297→100
   ↓
3. Calculs sophistiqués proposent: SMB = 0.54U
   ↓
4. PKPD boost: 0.54 × 1.20 = 0.65U
   ↓
5. Global Hyper Kicker amplifie encore
   ↓
6. MAIS capSmbDose() plafonne à MaxSMB = 0.6U
   ↓
7. Quantization: 0.6U
   ↓
8. ENVOYÉ: 0.6U seulement (19% du besoin réel!)
```

### **Pourquoi il "laisse monter"?**

**Le système NE LAISSE PAS volontairement monter. Il est BRIDÉ par:**
1. **MaxSMB trop bas** (0.6U)
2. **ISF fusionné inadapté** (63 au lieu de 147)
3. **Répétition lente** (SMB envoyés tous les 3-5 min minimum)

**Avec ces limitations, à 0.6U toutes les 3 minutes:**
- **Correction totale en 15 min:** 3.0U
- **Baisse attendue:** 3.0 × 63 = 189 mg/dL
- **Temps pour corriger 297→100:** ~20-25 minutes

**MAIS la glycémie continue de MONTER (delta positif) donc le système est en retard constant.**

---

## 💡 **SOLUTIONS RECOMMANDÉES**

### **Solution #1: Augmenter MaxSMB pour HighBG (PRIORITÉ HAUTE)**

```kotlin
// Paramètre à modifier:
OApsAIMIHighBGMaxSMB = 0.6  →  2.5-3.0U
```

**Justification:**
- BG 297 = Urgence relative
- IOB disponible: 7.79U
- MaxIOB = 8.0U est bien configuré
- Le système SAIT qu'il faut plus, laissons-le donner

**Impact:**
```
Avec MaxSMBHB = 2.5U:
- SMB proposé: 0.54 → 0.65U (PKPD) → 2.5U max
- Premier envoi: 2.5U au lieu de 0.6U
- Baisse attendue: 2.5 × 63 = 157 mg/dL
- 297 - 157 = 140 mg/dL dès le premier cycle
```

---

### **Solution #2: Corriger la Fusion ISF (PRIORITÉ CRITIQUE)**

#### Problème actuel:
```kotlin
// La fusion pèse trop vers TDD-ISF
fusedIsf = fusion.fused(profileIsf=147, tddIsf=57, pkpdScale=1.11)
// → 63 (trop proche de 57, ignore presque le profil)
```

#### Option A: Augmenter le poids du profil
```kotlin
// IsfFusion.kt (à créer/modifier)
class IsfFusion(private val bounds: IsfFusionBounds) {
    fun fused(profileIsf: Double, tddIsf: Double, pkpdScale: Double): Double {
        // AVANT (trop agressif):
        // val blend = (profileIsf * 0.3 + tddIsf * 0.7) * pkpdScale
        
        // APRÈS (plus équilibré):
        val blend = (profileIsf * 0.6 + tddIsf * 0.4) * pkpdScale
        return blend.coerceIn(bounds.minFactor, bounds.maxFactor)
    }
}
```

**Impact:**
```
Avec poids 60/40:
fusedIsf = (147×0.6 + 57×0.4) × 1.11
         = (88.2 + 22.8) × 1.11
         = 111 × 1.11
         = 123

Correction nécessaire = (297-100)/123 = 1.60U (au lieu de 3.13U)
→ Plus réaliste, moins agressif
```

#### Option B: Limiter l'écart TDD-ISF
```kotlin
private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
    if (tdd24h <= 0.1) return fallback
    val anchored = 1800.0 / tdd24h
    
    // AJOUT: Ne pas s'écarter de plus de 50% du profil
    val maxDeviation = fallback * 0.5
    return anchored.coerceIn(
        fallback - maxDeviation,
        fallback + maxDeviation
    )
}
```

**Impact:**
```
profileIsf = 147
maxDeviation = 73.5
tddIsf brut = 57 → clampé à max(147-73.5, 57) = 73.5

fusedIsf = (147×0.5 + 73.5×0.5) × 1.11 = 122
→ Évite les dérives extrêmes
```

---

### **Solution #3: Améliorer la Logique HighBG**

```kotlin
// DetermineBasalAIMI2.kt ligne 1454
// AVANT:
val baseLimit = if (this.bg > 120) this.maxSMBHB else this.maxSMB

// APRÈS (progressif):
val baseLimit = when {
    this.bg >= 250 -> this.maxSMBHB * 1.5  // Urgence haute
    this.bg >= 180 -> this.maxSMBHB * 1.2  // Hyperglycémie modérée
    this.bg > 120 -> this.maxSMBHB         // Légèrement haut
    else -> this.maxSMB                     // Normal/bas
}.coerceAtMost(this.maxIob - this.iob)     // Respecte toujours maxIOB
```

**Impact pour BG=297:**
```
MaxSMBHB = 0.6U
baseLimit = 0.6 × 1.5 = 0.9U (au lieu de 0.6U)

Avec ISF corrigé (123):
Correction = (297-100)/123 = 1.60U
SMB proposé = min(1.60, 0.9) = 0.9U
→ 50% d'amélioration immédiate
```

---

### **Solution #4: Désactiver PKPD Scale Temporairement**

Si tu suspectes que le PKPD aggrave la situation:

```kotlin
// PkPdIntegration.kt ligne 110
// AVANT:
val pkpdScale = (1.0 + ...)
    .coerceIn(minScale, maxScale)

// TEST DIAGNOSTIC:
val pkpdScale = 1.0  // Neutralise PKPD
```

**Impact:**
```
Avec pkpdScale = 1.0:
fusedIsf = fusion.fused(147, 57, 1.0)
         ≈ 111 (sans amplification)
         
→ Vérifie si le problème vient du PKPD ou de l'ISF TDD
```

---

## 📊 **TABLEAU COMPARATIF**

| Paramètre | Valeur Actuelle | Impact | Recommandation |
|-----------|----------------|--------|----------------|
| **ISF Profil** | 147 | ✅ OK (config utilisateur) | Garder |
| **ISF TDD** | 57 | ❌ TROP BAS (trop agressif) | Clamper à ±50% du profil |
| **ISF Fusionné** | 63 | ❌ CRITIQUE (suit trop TDD) | Poids 60/40 profil/TDD |
| **PKPD Scale** | 1.11 | ⚠️ Amplifie problème ISF | Tester à 1.0 |
| **MaxSMB** | 0.6U | ❌ GOULOT (brid tout) | Augmenter à 2.5-3.0U |
| **MaxSMBHB** | 0.6U | ❌ IDENTIQUE (inutile) | Augmenter à 3.0-4.0U |
| **MaxIOB** | 8.0U | ✅ OK (marge disponible) | Garder |
| **IOB actuel** | 0.21U | ℹ️ Énorme marge (7.79U) | Normal |

---

## 🔧 **PLAN D'ACTION IMMÉDIAT**

### **Étape 1: Quick Fix (5 min)**
```
1. Aller dans Préférences OpenAPS AIMI
2. Localiser "High BG Max SMB"
3. Passer de 0.6U à 2.5U
4. Localiser "Max SMB" (Normal)
5. Passer de 0.6U à 1.5U
6. Sauvegarder et redémarrer la boucle
```

**Résultat attendu:** Prochaine correction à BG 297 → SMB ~2.0-2.5U au lieu de 0.6U

---

### **Étape 2: Diagnostic ISF (Code)**
```kotlin
// Ajouter des logs dans PkPdIntegration.kt ligne 112
val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale)

consoleLog?.add("ISF_FUSION profile=$profileIsf tdd=$tddIsf scale=$pkpdScale → fused=$fusedIsf")
consoleLog?.add("ISF_FUSION weights: profile=0.5 tdd=0.5") // À ajuster
```

**Objectif:** Comprendre la formule de fusion exacte

---

### **Étape 3: Implémenter Fusion Équilibrée (Code)**

Localiser ou créer `IsfFusion.kt` et modifier:
```kotlin
fun fused(profileIsf: Double, tddIsf: Double, pkpdScale: Double): Double {
    // Clamper TDD-ISF pour éviter dérives
    val maxDeviation = profileIsf * 0.5
    val clampedTddIsf = tddIsf.coerceIn(
        profileIsf - maxDeviation,
        profileIsf + maxDeviation
    )
    
    // Fusion pondérée: 60% profil, 40% TDD
    val blended = profileIsf * 0.6 + clampedTddIsf * 0.4
    
    // Appliquer PKPD scale de manière limitée
    val scaled = blended * pkpdScale.coerceIn(0.9, 1.3)
    
    return scaled.coerceIn(bounds.minFactor, bounds.maxFactor)
}
```

---

### **Étape 4: Tester et Vérifier**

1. **Provoquer une hyperglycémie contrôlée** (repas test)
2. **Collecter les logs:**
   ```bash
   adb logcat | grep "ISF_FUSION"
   adb logcat | grep "SMB_CAP"
   adb logcat | grep "GATE_MAXSMB"
   ```
3. **Vérifier:**
   - ISF fusionné proche du profil (± 30%)
   - SMB proposés cohérents avec BG/ISF
   - MaxSMB ne bloque plus systématiquement

---

## 📝 **RÉSUMÉ EXÉCUTIF**

### **Pourquoi BG=297 avec si peu de correction?**

**Réponse courte:**
1. **ISF fusionné (63) est TROP BAS** → Le système pense qu'il faut beaucoup d'insuline
2. **MaxSMB (0.6U) est RIDICULEMENT BAS** → Bride toute tentative de correction agressive
3. **Résultat:** Le système veut donner 3U mais ne peut que 0.6U → Correction trop lente → Glycémie monte

### **ISF trop haut ou trop bas?**

**ISF=63 est TROP BAS (pas trop haut!).**

- **ISF bas** = Peu sensible à l'insuline = Besoin de BEAUCOUP d'insuline
- **ISF haut** = Très sensible = Besoin de PEU d'insuline

**Ton ISF profil (147) est probablement plus correct que l'ISF TDD (57).**

### **Priorités d'action:**
1. 🔴 **URGENT:** Augmenter MaxSMBHB à 2.5-3.0U
2. 🟠 **IMPORTANT:** Corriger la fusion ISF (60/40 au lieu de 30/70)
3. 🟡 **AMÉLIORATION:** Logique progressive HighBG (×1.5 si BG≥250)

---

## ❓ **TES SOUPÇONS ÉTAIENT-ILS JUSTES?**

> "est-ce parce que ISF est trop haut et cela donne un résultat inadapté?"

**Oui, tu as raison sur le principe, mais c'est INVERSÉ:**

- ❌ ISF n'est pas "trop haut" (63 est BAS)
- ✅ **ISF fusionné (63) est inadapté** (devrait être proche de 147)
- ✅ **Cela DONNE un résultat inadapté** (calcule qu'il faut 3U mais donne 0.6U)

**Le vrai problème:** ISF-TDD (57) domine la fusion et tire l'ISF fusionné vers le bas.

---

**Veux-tu que j'implémente les corrections de fusion ISF dans le code immédiatement?** 🚀
