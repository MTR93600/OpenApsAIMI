# ANALYSE CRITIQUE: MaxSMB Zone 120-180 (Zone Repas)

**Date:** 2025-12-20 09:56  
**Question:** La variabilité maxSMB entre 120-180 a-t-elle du sens sachant que c'est la zone d'interception repas typique?

---

## 🎯 **LE PROBLÈME SOULEVÉ**

### **Zone 120-180 = Zone d'Interception Repas**

**Comportement actuel (code implémenté):**
```kotlin
// BG > 120 ET slope >= 1.0 → maxSMBHB
bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 -> maxSMBHB
```

**Scenarios typiques dans cette zone:**

#### **Scenario 1: Début de repas (BG 130 → 180)**
```
T+0:  BG 130, Δ +12, slope 1.8, COB 0g
      → slope >= 1.0 → maxSMBHB (1.2U) ✅
      → Interception précoce, BIEN

T+5:  BG 155, Δ +10, slope 1.5, COB 15g
      → maxSMBHB (1.2U) ✅
      → Limite montée, BIEN

T+10: BG 175, Δ +6, slope 1.2, COB 25g
      → maxSMBHB (1.2U) ✅
      → Continue correction, BIEN
```
**Résultat:** Pic à 180 au lieu de 220 → **EXCELLENT**

#### **Scenario 2: Fluctuation naturelle (BG 125 → 145 → 130)**
```
T+0:  BG 125, Δ +4, slope 1.1, COB 0g
      → slope >= 1.0 → maxSMBHB (1.2U) ⚠️
      → Peut-être trop agressif?

T+5:  BG 138, Δ +3, slope 0.9, IOB 1.0U
      → slope < 1.0 → maxSMB (0.6U)
      → Montée naturelle ralentit

T+10: BG 145, Δ +1, slope 0.4, IOB 1.4U
      → Pic atteint, IOB actif
      
T+20: BG 132, Δ -3, slope -0.2, IOB 1.0U
      → Redescend, correction peut-être excessive?
```
**Résultat:** Pic à 145 OK, mais IOB empilée pour fluctuation naturelle → **DISCUTABLE**

#### **Scenario 3: Résistance matinale (BG 120 stable → 160)**
```
T+0:  BG 122, Δ +2, slope 1.0, COB 0g (matin)
      → slope >= 1.0 → maxSMBHB (1.2U) ✅
      → Dawn phenomenon, résistance

T+10: BG 135, Δ +2, slope 1.0, IOB 1.0U
      → maxSMBHB (1.2U) ✅
      → Continue correction résistance

T+20: BG 148, Δ +2, slope 0.9, IOB 1.8U
      → slope < 1.0 → maxSMB (0.6U)
      → Résistance continue mais slope tombe
```
**Résultat:** Résistance partiellement contrôlée → **ACCEPTABLE**

---

## 📊 **ANALYSE: A-t-on VRAIMENT besoin de maxSMBHB dès 120?**

### **Arguments POUR (maxSMBHB dès 120):**

✅ **1. Interception précoce repas:**
```
Début repas BG 130:
- Avec maxSMBHB (1.2U): Pic 180
- Avec maxSMB (0.6U): Pic 220
→ Gain: -40 mg/dL sur le pic
```

✅ **2. Évite accumulation tardive:**
```
Si on attend BG 180 pour maxSMBHB:
- BG 130-180: correction lente (maxSMB 0.6U)
- BG atteint 180, IOB déjà 2.0U empilée
- Puis maxSMBHB s'active mais trop tard
→ Pics plus hauts ET IOB empilée
```

✅ **3. slope >= 1.0 filtre bien:**
```
Fluctuations naturelles:
- BG 120 → 135 lentement = slope < 1.0 → maxSMB
- BG 120 → 160 rapidement = slope >= 1.0 → maxSMBHB ✅
→ Le slope DISCRIMINE déjà
```

### **Arguments CONTRE (trop agressif dès 120):**

⚠️ **1. Proximité de la cible:**
```
BG 120-140 = Zone "acceptable"
- Cible: 100 mg/dL
- BG 120 = +20 mg/dL seulement
- Autoriser maxSMBHB peut over-corriger
```

⚠️ **2. Faux positifs slope:**
```
slope >= 1.0 peut être:
- Vraie montée repas (→ maxSMBHB justifié)
- Rebond post-hypo (→ maxSMBHB dangereux)
- Compression release (→ maxSMBHB excessif)
```

⚠️ **3. IOB empilée prématurément:**
```
BG 125, slope 1.2:
T+0: maxSMBHB 1.2U → IOB 1.2
T+5: slope encore 1.0 → maxSMBHB 1.0U → IOB 2.0
T+10: Montée s'arrête, mais IOB 2.0U active
→ Risque hypo 2h après
```

---

## 🔍 **VÉRIFICATION: Les Garde-Fous Protègent-ils?**

### **Garde-fous actifs entre BG 120-180:**

**1. MaxIOB:**
```kotlin
if (iob + proposed > maxIob) {
    capped = max(0, maxIob - iob)
}
```
✅ **Protège** contre IOB excessive totale  
⚠️ **MAIS** ne limite pas empilage si maxIOB = 8.0U (beaucoup de marge)

**2. PKPD Throttle:**
```kotlin
if (high tail fraction) {
    gatedUnits *= throttleFactor
}
```
✅ **Protège** si tail insulin élevée  
⚠️ **MAIS** pas si début repas (tail faible au début)

**3. Absorption Guard:**
```kotlin
if (sinceBolus < 20min && iobActivity > threshold) {
    gatedUnits *= 0.5-0.75
}
```
✅ **Protège** contre empilage rapide  
✅ **Efficace** pour limiter deuxième SMB si premier récent

**4. Refractory:**
```kotlin
if (sinceBolus < refractoryWindow) {
    gatedUnits = 0
}
```
✅ **Protège** totalement si SMB très récent (<3-5min)  
✅ **Efficace** pour espacer les SMB

**5. LOW_BG_GUARD:**
```kotlin
if (bg < 120) {
    safetyCappedUnits = min(safetyCappedUnits, baseLimit * 0.4)
}
```
❌ **N'ACTIVE PAS** dans zone 120-180  
⚠️ **Trou de protection** pour BG 120-140

---

## 💡 **MON AVIS: Zone 120-180 Nécessite Gradation**

### **Problème identifié:**

**BG 125 avec slope 1.0 ≠ BG 175 avec slope 1.0**

```
BG 125, slope 1.0:
- Risque: Faux positif, fluctuation naturelle
- Gravité si over-correction: Hypo possible
- maxSMBHB (1.2U) peut être excessif

BG 175, slope 1.0:
- Contexte: Probablement vrai repas
- Gravité: BG déjà haute, urgence modérée
- maxSMBHB (1.2U) approprié
```

### **Solution Proposée: Gradation Progressive**

#### **Option 1: Seuils slope variables**

```kotlin
this.maxSMB = when {
    // 🚨 PLATEAU CRITIQUE (inchangé)
    bg >= 250 && combinedDelta > -5.0 -> maxSMBHB
    
    // 🔴 MONTÉE ACTIVE avec seuils graduels slope
    bg >= 180 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 ||
    bg >= 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.3 ||
    bg >= 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.6 -> {
        consoleLog.add("MAXSMB_SLOPE BG=${bg} slope=${slope} → maxSMBHB")
        maxSMBHB
    }
    
    // ... reste inchangé
}
```

**Traduction:**
- **BG >= 180:** slope >= 1.0 suffit (montée modérée → maxSMBHB)
- **BG 140-180:** slope >= 1.3 requis (montée forte → maxSMBHB)
- **BG 120-140:** slope >= 1.6 requis (montée très forte → maxSMBHB)

**Rationnel:** Plus on est proche de la cible, plus on exige une montée confirmée

---

#### **Option 2: MaxSMB partiel zone 120-140**

```kotlin
this.maxSMB = when {
    // 🚨 PLATEAU CRITIQUE (inchangé)
    bg >= 250 && combinedDelta > -5.0 -> maxSMBHB
    
    // 🔴 MONTÉE ACTIVE haute (inchangé)
    bg >= 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 -> {
        consoleLog.add("MAXSMB_SLOPE BG=${bg} → maxSMBHB")
        maxSMBHB
    }
    
    // 🟡 NOUVEAU: MONTÉE zone sensible 120-140
    bg >= 120 && bg < 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 -> {
        val partial = max(maxSMB, maxSMBHB * 0.85)
        consoleLog.add("MAXSMB_SLOPE_SENSITIVE BG=${bg} → ${partial}U (85% maxSMBHB)")
        partial
    }
    
    // ... reste inchangé
}
```

**Traduction:**
- **BG >= 140:** maxSMBHB complet (100%)
- **BG 120-140:** maxSMBHB partiel (85%)
- **Rationnel:** Prudence supplémentaire proche cible

---

#### **Option 3: Exiger delta minimal BG 120-140**

```kotlin
this.maxSMB = when {
    // 🚨 PLATEAU CRITIQUE (inchangé)
    bg >= 250 && combinedDelta > -5.0 -> maxSMBHB
    
    // 🔴 MONTÉE ACTIVE avec delta minimum
    bg >= 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 ||
    bg >= 120 && bg < 140 && !honeymoon && 
        mealData.slopeFromMinDeviation >= 1.0 && combinedDelta >= 5.0 -> {
        // BG 120-140: exige AUSSI delta >= 5 mg/dL
        consoleLog.add("MAXSMB_SLOPE BG=${bg} Δ=${delta} → maxSMBHB")
        maxSMBHB
    }
    
    // ... reste inchangé
}
```

**Traduction:**
- **BG >= 140:** slope >= 1.0 suffit
- **BG 120-140:** slope >= 1.0 **ET** delta >= 5 mg/dL
- **Rationnel:** Confirmation double (tendance ET vitesse)

---

## 📊 **COMPARAISON OPTIONS**

| Option | Zone 120-140 | Zone 140-180 | Zone >= 180 | Complexité | Sécurité |
|--------|--------------|--------------|-------------|------------|----------|
| **Actuel** | slope >= 1.0 → maxSMBHB | slope >= 1.0 → maxSMBHB | slope >= 1.0 → maxSMBHB | 🟢 Simple | 🟡 Moyenne |
| **Option 1** (slope graduel) | slope >= 1.6 → maxSMBHB | slope >= 1.3 → maxSMBHB | slope >= 1.0 → maxSMBHB | 🟡 Modérée | 🟢 Haute |
| **Option 2** (85%) | slope >= 1.0 → 85% maxSMBHB | slope >= 1.0 → maxSMBHB | slope >= 1.0 → maxSMBHB | 🟢 Simple | 🟢 Haute |
| **Option 3** (delta+slope) | slope >= 1.0 + Δ>=5 → maxSMBHB | slope >= 1.0 → maxSMBHB | slope >= 1.0 → maxSMBHB | 🟡 Modérée | 🟢 Haute |

---

## 🎯 **MA RECOMMANDATION**

### **OUI, il faut GRADUER pour zone 120-140**

**Pourquoi:**
1. ✅ BG 120-140 = proche cible, risque over-correction
2. ✅ Garde-fous actuels (Absorption, Refractory) aident MAIS pas dès le 1er SMB
3. ✅ slope 1.0 en zone 120-140 peut être faux positif

**Quelle option:**

**Je recommande Option 2 (85% partiel) car:**
- ✅ **Simple** à implémenter et comprendre
- ✅ **Prudent** sans être trop conservateur
- ✅ **Progressif** (120-140: 85%, 140+: 100%)
- ✅ **Garde interception précoce** repas (85% reste significatif)

### **Code Recommandé:**

```kotlin
this.maxSMB = when {
    // 🚨 PLATEAU CRITIQUE >= 250
    bg >= 250 && combinedDelta > -5.0 -> {
        consoleLog.add("MAXSMB_PLATEAU_CRITICAL → maxSMBHB")
        maxSMBHB
    }
    
    // 🔴 MONTÉE ACTIVE zone haute (>= 140)
    bg >= 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 ||
    bg >= 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4 -> {
        consoleLog.add("MAXSMB_SLOPE BG=${bg} → maxSMBHB")
        maxSMBHB
    }
    
    // 🟡 MONTÉE zone sensible (120-140): Prudence supplémentaire
    bg >= 120 && bg < 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 -> {
        val partial = max(maxSMB, maxSMBHB * 0.85)
        consoleLog.add("MAXSMB_SLOPE_SENSITIVE BG=${bg} slope=${slope} → ${partial}U (85%)")
        partial
    }
    
    // 🟠 PLATEAU MODÉRÉ (200-250)
    bg >= 200 && bg < 250 && combinedDelta > -3.0 && combinedDelta < 3.0 -> {
        val partial = max(maxSMB, maxSMBHB * 0.75)
        consoleLog.add("MAXSMB_PLATEAU_MODERATE → 75%")
        partial
    }
    
    // 🔵 FALLING PROTECTION
    bg > 180 && combinedDelta <= -3.0 && combinedDelta > -8.0 -> {
        val partial = max(maxSMB, maxSMBHB * 0.6)
        consoleLog.add("MAXSMB_FALLING → 60%")
        partial
    }
    
    // ⚪ STANDARD
    else -> {
        consoleLog.add("MAXSMB_STANDARD → ${maxSMB}U")
        maxSMB
    }
}
```

---

## 📈 **Impact Attendu avec 85% zone 120-140:**

### **Scenario: Repas BG 130**
```
AVANT (100%):
T+0: BG 130, slope 1.5 → maxSMBHB 1.2U
T+5: BG 145, slope 1.3 → maxSMBHB 1.2U
→ Pic 160

APRÈS (85%):
T+0: BG 130, slope 1.5 → maxSMBHB × 0.85 = 1.02U
T+5: BG 143, slope 1.2 → maxSMBHB 1.2U (BG >= 140)
→ Pic 165 (+5 mg/dL acceptable)
→ Moins de risque over-correction
```

### **Scenario: Fluctuation BG 125**
```
AVANT (100%):
T+0: BG 125, slope 1.1 → maxSMBHB 1.2U
→ Pic 138, puis descente, possible hypo

APRÈS (85%):
T+0: BG 125, slope 1.1 → maxSMBHB × 0.85 = 1.02U
→ Pic 135, descente douce, pas d'hypo
→ Plus sûr
```

---

**CONCLUSION:** OUI, graduer pour zone 120-140 a du sens. **Veux-tu que j'implémente l'option 2 (85%)?** 🔧
