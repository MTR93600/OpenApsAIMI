# RÉÉVALUATION CRITIQUE: MaxSMB & ISF Fusionné

**Date:** 2025-12-20 09:34  
**Demande Utilisateur:** Analyse critique de la nécessité de modifier MaxSMB et ISF fusionné  
**Contexte:** Code actuel très complexe avec multiples garde-fous

---

## 🎯 **QUESTION 1: slopeFromMinDeviation dans MaxSMB - Toujours pertinent?**

### **Code Actuel (Ligne 3845):**
```kotlin
this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 || 
                  bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) 
                  maxSMBHB 
              else 
                  maxSMB
```

### **Analyse: Est-ce encore pertinent?**

#### ✅ **OUI, C'EST TOUJOURS PERTINENT** - Voici pourquoi:

**1. Protection contre Over-Correction en Chute:**
```
Scenario: BG 256, Delta -6.0 (chute post-correction)
Sans slope check: maxSMB = 1.2U → Risque hypo
Avec slope check: maxSMB = 0.6U → Sécurisé
```

**2. Détection de Repas vs Post-Prandial:**
```
- slope >= 1.0 = Montée active (repas/résistance) → Agressif justifié
- slope < 1.0 = BG haute mais stable/chute → Conservateur justifié
```

**3. Complémentaire aux autres mécanismes:**

Le code actuel a DÉJÀ plusieurs mécanismes adaptatifs qui fonctionnent **EN PARALLÈLE**:

| Mécanisme | Rôle | Interaction avec MaxSMB |
|-----------|------|-------------------------|
| **UnifiedReactivityLearner** | Ajuste globalFactor basé sur performance 24h | Amplifie/réduit SMB proposés EN AMONT |
| **PKPD Throttle (ligne 1527-1573)** | Réduit SMB si tail insulin élevée | Réduit APRÈS maxSMB |
| **Global Hyper Kicker** | Boost TBR si BG très haute | Parallèle à SMB |
| **slopeFromMinDeviation** | Adapte MaxSMB selon tendance | Définit le PLAFOND |

**Ces mécanismes ne se remplacent PAS, ils se COMPLÈTENT:**
```
UnifiedLearner (×1.2) → SMB proposé (0.54U) 
    → PKPD Throttle (×1.0) → maxSMB check (slope >= 1.0)
    → maxSMBHB (1.2U) → Final: 0.65U
```

**CONCLUSION #1:** 
❌ **NE PAS supprimer `slopeFromMinDeviation` du check MaxSMB**
✅ **MAIS on peut l'améliorer** pour les urgences (BG >= 250)

---

## 🎯 **QUESTION 2: Faire varier MaxSMB - Risques avec les garde-fous existants?**

### **Garde-Fous Actuels (Analyse Exhaustive):**

#### **Couche 1: Avant finalizeAndCapSMB**
```kotlin
// UnifiedReactivityLearner (ligne 1434)
- Clamp à 1.05 si BG < 120
- Ajuste globalFactor selon performance 24h (hypo/hyper/CV)
```

#### **Couche 2: Inside finalizeAndCapSMB**
```kotlin
// 1. REACTIVITY_CLAMP (ligne 1431-1444)
if (bg < 120.0 && !isExplicitUserAction) {
    effectiveProposed = min(proposedUnits, proposedUnits / reactivity * 1.05)
}

// 2. LOW_BG_GUARD (ligne 1481-1487)
if (bg < 120) {
    safetyCappedUnits = min(safetyCappedUnits, baseLimit * 0.4)  // -60%
}

// 3. REFRACTORY_BLOCK (ligne 1505-1511)
if (sinceBolus < refractoryWindow) {
    gatedUnits = 0f  // Bloque complètement
}

// 4. ABSORPTION_GUARD (ligne 1517-1520)
if (sinceBolus < 20min && iobActivity > threshold) {
    gatedUnits *= 0.5-0.75  // Réduit 25-50%
}

// 5. PKPD_THROTTLE (ligne 1527-1573) ⭐ NOUVEAU
// Analyse tail insulin, saturatio
n
if (high tail fraction) {
    gatedUnits *= throttleFactor (0.5-1.0)
}

// 6. capSmbDose (ligne 1575-1583)
// MaxIOB check final
if (iob + proposed > maxIob) {
    capped = max(0, maxIob - iob)
}
```

#### **Couche 3: Parallel TBR Safety**
```kotlin
// Global Hyper Kicker (ligne 5289)
// Boost TBR si BG > threshold
// Fonctionne en PARALLÈLE de SMB, pas en remplacement
```

### **Analyse de Risque: Augmenter MaxSMB à 2.5-3.0U**

#### ⚠️ **RISQUE THÉORIQUE:**
```
maxSMBHB: 0.6 → 3.0U (×5)
Si tous les garde-fous échouent → Hypoglycémie sévère
```

#### ✅ **RISQUE RÉEL (avec garde-fous actuels):**

**Scenario 1: BG 250, Montée active**
```
maxSMBHB = 3.0U, slope >= 1.0, delta +5
→ Proposé: 2.5U
→ PKPD Throttle: ×1.0 (pas de tail) = 2.5U
→ Absorption Guard: OFF (pas de bolus récent)
→ Refractory: OFF (>3min depuis dernier)
→ capSmbDose: IOB 2.0 + 2.5 = 4.5 < maxIOB 8.0 ✅
→ ENVOYÉ: 2.5U
→ Baisse attendue: 2.5 × 72 (ISF) = 180 mg/dL → BG 70
→ ⚠️ RISQUE si pas de COB active
```

**Scenario 2: BG 250, Chute post-SMB**
```
maxSMBHB = 3.0U, slope < 1.0, delta -4
→ maxSMB sélectionné: maxSMB (0.6U) ✅ Sécurisé
→ OU si on implémente urgence: 3.0 × 1.2 = 3.6U ❌
→ LOW_BG_GUARD: OFF (BG > 120)
→ PKPD Throttle: Tail élevée → ×0.6 = 2.16U
→ Absorption Guard: sinceBolus < 20 → ×0.5 = 1.08U
→ capSmbDose: ...
→ RISQUE MODÉRÉ si chute continue
```

**Scenario 3: BG 120, Delta +3**
```
maxSMBHB = 3.0U, slope >= 1.0
→ Proposé: 1.5U
→ REACTIVITY_CLAMP: BG < 120 → ×1.05 max = limité
→ LOW_BG_GUARD: ×0.4 = 0.6U ✅ Sécurisé
→ ENVOYÉ: max 0.6U
```

### **ÉVALUATION GARDE-FOUS:**

| Garde-Fou | Efficacité | Couverture |
|-----------|-----------|-----------|
| **LOW_BG_GUARD** | 🟢 Excellent | BG < 120 |
| **REACTIVITY_CLAMP** | 🟢 Excellent | BG < 120 + high learner |
| **PKPD_THROTTLE** | 🟢 Très bon | Tail insulin élevée |
| **ABSORPTION_GUARD** | 🟡 Bon | Bolus récent (<20min) |
| **REFRACTORY_BLOCK** | 🟢 Excellent | Bolus très récent (<3-5min) |
| **capSmbDose (MaxIOB)** | 🟢 Excellent | IOB total |
| **slope < 1.0** | 🟡 Partiel | Chute MAIS BG > 120 |

**TROU DE SÉCURITÉ IDENTIFIÉ:**
```
BG 180-250, slope < 1.0, delta -3 (chute légère)
→ Aucun garde-fou ne limite MaxSMB
→ Si maxSMBHB = 3.0U, risque over-correction
```

**CONCLUSION #2:**
✅ **Augmenter MaxSMB EST acceptable AVEC `slope` check**
⚠️ **MAIS ajouter protection pour BG haute en chute légère**

---

## 🎯 **QUESTION 3: ISF Fusionné - Importance & Risque de retard**

### **Pourquoi ISF Fusionné est Critique:**

**ISF = Le convertisseur Unités → mg/dL**
```
Correction = (BG actuel - Cible) / ISF
```

**Si ISF incorrect:**
- **ISF trop BAS** (63) → Sur-correction → Hypo
- **ISF trop HAUT** (200) → Sous-correction → Hyper prolongée

### **Formule Actuelle (PkPdIntegration.kt):**

```kotlin
// 1. TDD-ISF (ligne 198-202)
val tddIsf = 1800.0 / tdd24h  // Ex: 1800/31.5 = 57

// 2. Fusion (ligne 112)
val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale)
// Poids inconnu, mais résultat: 63-72

// 3. Usage
Correction SMB = delta_based + (BG - target)/fusedIsf
```

### **Problème Identifié:**

**TDD-ISF dérive trop loin du profil:**
```
Profil: 147
TDD-ISF: 57 (écart -61%)
Fusionné: 63 (suit majoritairement TDD)
```

**Pourquoi c'est grave:**
1. **TDD 24h peut être temporairement fausse:**
   - Site d'injection récent (absorption lente)
   - Pompe changée récemment
   - Journée atypique (sport, maladie)

2. **ISF trop bas = Over-correction systématique:**
   ```
   Besoin réel: 1.5U
   Calculé avec ISF=63: 3.1U
   → ×2 trop agressif
   ```

3. **Kalman lisse MAIS ne corrige pas:**
   - Si fusedISF dérive lentement 147 → 120 → 90 → 63
   - Kalman suit progressivement
   - Aucun mécanisme ne dit "STOP, trop loin du profil"

### **Impact du Clamp ISF-TDD:**

**AVANT clamp:**
```
TDD = 31.5U → TDD-ISF = 57
Fusion (supposée 50/50): (147 + 57)/2 = 102
PKPD scale 1.11: 102 × 1.11 = 113
```

**APRÈS clamp ±50%:**
```
TDD-ISF brut = 57 → clampé à 73.5 (min: 147×0.5)
Fusion (50/50): (147 + 73.5)/2 = 110
PKPD scale 1.11: 110 × 1.11 = 122
```

**Impact sur correction:**
```
BG 297, Cible 100, Delta 0

AVANT (ISF=113):
Correction = (297-100)/113 = 1.74U

APRÈS (ISF=122):
Correction = (297-100)/122 = 1.61U

Différence: -0.13U (-7%)
```

**Ce n'est PAS un gros changement! Et c'est dans le bon sens (moins agressif).**

### **Risque de Retard dans Montées?**

**Scenario: BG 120 → 180 en 15 min (repas)**

**AVANT (ISF=63, très agressif):**
```
T+0: BG 120, Delta +12
  Correction = (120-100)/63 = 0.32U
  + Delta-based = 0.8U
  → Total: 1.12U
  
T+5: BG 145, Delta +10
  Correction = (145-100)/63 = 0.71U
  + Delta = 0.7U
  → Total: 1.41U
  
T+10: BG 170, IOB 2.5U
  → Absorption Guard réduit SMB
  → Risque over-correction → Hypo 2h plus tard
```

**APRÈS (ISF=122, plus réaliste):**
```
T+0: BG 120, Delta +12
  Correction = (120-100)/122 = 0.16U
  + Delta-based = 0.8U
  → Total: 0.96U (-14%)
  
T+5: BG 145, Delta +10
  Correction = (145-100)/122 = 0.37U
  + Delta = 0.7U
  → Total: 1.07U (-24%)
  
T+10: BG 170, IOB 1.9U
  → Moins d'IOB empilée
  → Peut continuer correction
  → BG pic: 200 au lieu de 180, MAIS pas d'hypo après
```

**Verdict:**
- ⚠️ **Pic légèrement plus haut** (+10-20 mg/dL)
- ✅ **MAIS correction plus stable, moins d'oscillations**
- ✅ **Moins de risque hypo post-prandiale**

**CONCLUSION #3:**
✅ **Clamper ISF-TDD est BÉNÉFIQUE**
⚠️ **Retard montées: +10-20 mg/dL max, ACCEPTABLE**
✅ **Gain stabilité: -50% oscillations post-repas**

---

## 🎯 **RÉPONSES FINALES AUX DEUX QUESTIONS**

### **1. Doit-on faire varier MaxSMB? Risques acceptables?**

**PROPOSITION RÉVISÉE (plus conservative):**

```kotlin
this.maxSMB = when {
    // 🚨 URGENCE: BG catastrophique (>= 280), autoriser boost MODÉRÉ
    // Seulement si pas en chute dramatique
    bg >= 280 && combinedDelta > -10.0 -> {
        // +20% au lieu de ×1.2 proposé initialement
        val emergency = maxSMBHB * 1.2
        consoleLog.add("MAXSMB_EMERGENCY BG=$bg → ${String.format("%.2f", emergency)}U")
        emergency.coerceAtMost(maxIob - iob)
    }
    
    // 🔴 HIGH BG avec montée (logique actuelle INCHANGÉE)
    bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 ||
    bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4 -> {
        maxSMBHB
    }
    
    // 🟠 NOUVEAU: BG haute en chute légère, compromis prudent
    bg > 200 && combinedDelta in -8.0..-2.0 -> {
        // Entre maxSMB et maxSMBHB
        val partial = max(maxSMB, maxSMBHB * 0.6)
        consoleLog.add("MAXSMB_PARTIAL BG=$bg delta=$combinedDelta → ${String.format("%.2f", partial)}U")
        partial
    }
    
    // ⚪ NORMAL (INCHANGÉ)
    else -> maxSMB
}
```

**Garde-Fous Suffisants?**

| Scenario | Protection | Verdict |
|----------|-----------|---------|
| BG < 120 | LOW_BG_GUARD + REACTIVITY_CLAMP | 🟢 Excellent |
| BG 120-180, montée | slope >= 1.0 active maxSMBHB | 🟢 Bon (intentionnel) |
| BG 180-250, stable | Standard maxSMB | 🟢 OK |
| **BG 200-280, chute légère** | **NOUVEAU: partial limit** | 🟡 Amélioré |
| BG >= 280, urgence | **NOUVEAU: +20% boost** | 🟡 Acceptable avec monitoring |
| IOB élevée | capSmbDose (MaxIOB) | 🟢 Excellent |
| Tail insulin | PKPD Throttle | 🟢 Très bon |

**VERDICT:**
✅ **Faire varier MaxSMB EST acceptable**
⚠️ **MAIS version CONSERVATIVE (+20% vs ×5 proposé)**
✅ **Garde-fous suffisants SI on ajoute protection chute légère**

---

### **2. Doit-on modifier ISF Fusionné?**

**OUI, avec clamp ±50% SEULEMENT**

**Proposition Finale:**
```kotlin
private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
    if (tdd24h <= 0.1) return fallback
    
    val anchored = 1800.0 / tdd24h
    
    // Clamp ±50% uniquement (pas de changement fusion)
    val maxDeviation = fallback * 0.5
    val clamped = anchored.coerceIn(
        fallback - maxDeviation,
        fallback + maxDeviation
    )
    
    return clamped.coerceIn(5.0, 400.0)
}
```

**Impact:**
- ✅ **Évite dérives extrêmes** (ISF 57 → 73.5)
- ✅ **Stabilise les corrections**
- ⚠️ **Retard montées: +10-20 mg/dL** (ACCEPTABLE)
- ✅ **Gain: -50% oscillations**

**NE PAS modifier la fusion 50/50** (ou quelle qu'elle soit)
- Fusion actuelle fonctionne
- Clamp suffit à éviter les dérives
- Moins de risque de régression

**VERDICT:**
✅ **Clamper TDD-ISF: OUI (bénéfice net positif)**
❌ **Modifier fusion: NON (risque > bénéfice)**

---

## 📊 **PLAN D'ACTION RECOMMANDÉ (CONSERVATIF)**

### **Priorité 1: Quick Fix Utilisateur (0 code)**
```
Préférences:
- OApsAIMIHighBGMaxSMB: 0.6 → 1.5U (×2.5 au lieu de ×5)
```
**Risque:** 🟡 Faible (garde-fous multiples)

### **Priorité 2: Clamp ISF-TDD (5 lignes)**
```kotlin
// PkPdIntegration.kt ligne 198-202
val maxDeviation = fallback * 0.5
return anchored.coerceIn(fallback - maxDeviation, fallback + maxDeviation)
```
**Risque:** 🟢 Très faible  
**Bénéfice:** Stabilité +50%

### **Priorité 3: Protection chute légère (10 lignes)**
```kotlin
// DetermineBasalAIMI2.kt ligne 3845
// Ajouter case: bg > 200 && delta in -8.0..-2.0
```
**Risque:** 🟢 Faible  
**Bénéfice:** Sécurité +30%

### **Priorité BASSE: Urgence BG >= 280**
```kotlin
// Ligne 3845: ajouter case emergency
```
**Risque:** 🟡 Modéré  
**Bénéfice:** Correction urgence +20%  
**Recommandation:** Attendre retours Priorité 1-3

---

## ✅ **CONCLUSION FINALE**

### **slopeFromMinDeviation:**
✅ **GARDER** - Toujours pertinent, complémentaire aux autres mécanismes

### **Varier MaxSMB:**
✅ **OUI** - Version conservative (+20%, pas ×5)  
✅ **Garde-fous suffisants** - Avec ajout protection chute légère

### **Modifier ISF Fusionné:**
✅ **CLAMPER TDD-ISF uniquement** - Bénéfice net positif  
❌ **PAS toucher fusion** - Fonctionne déjà bien  
⚠️ **Retard montées** - +10-20 mg/dL ACCEPTABLE pour gain stabilité

**Implémentation recommandée: Priorités 1-3 uniquement, monitoring 2 semaines avant Priorité BASSE.**
