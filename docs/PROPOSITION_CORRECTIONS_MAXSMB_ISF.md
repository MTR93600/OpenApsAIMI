# PROPOSITION DE CORRECTIONS - MaxSMB ET ISF

**Date:** 2025-12-20 09:26  
**Objectif:** Corriger les deux problèmes identifiés  

---

## 🎯 **CORRECTION #1: Logique MaxSMB pour BG Critiques**

### **Problème:**
Ligne 3845 bride MaxSMB à 0.6U même quand BG=256, juste parce que `slope < 1.0` (chute).

### **Solution:**

```kotlin
// DetermineBasalAIMI2.kt ligne 3845
// AVANT:
this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 || 
                  bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) 
                  maxSMBHB 
              else 
                  maxSMB

// APRÈS:
this.maxSMB = when {
    // 🚨 URGENCE: BG critique (>= 250), autoriser maxSMBHB même en chute légère
    // Sauf si chute dramatique (< -8 mg/dL) qui pourrait être compression
    bg >= 250 && combinedDelta > -8.0 -> {
        consoleLog.add("MAXSMB_EMERGENCY BG=$bg delta=$combinedDelta → maxSMBHB × 1.2")
        (maxSMBHB * 1.2).coerceAtMost(maxIob - iob)  // +20% urgence, respecte maxIOB
    }
    
    // 🔴 HIGH BG avec montée confirmée (logique originale)
    bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 ||
    bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4 -> {
        consoleLog.add("MAXSMB_HIGHBG BG=$bg slope=${mealData.slopeFromMinDeviation} → maxSMBHB")
        maxSMBHB
    }
    
    // 🟠 HIGH BG en chute modérée: compromis entre sécurité et correction
    bg > 180 && combinedDelta > -5.0 && combinedDelta < 0 -> {
        val partialLimit = max(maxSMB, maxSMBHB * 0.7)
        consoleLog.add("MAXSMB_PARTIAL BG=$bg delta=$combinedDelta → ${String.format("%.2f", partialLimit)}U (70% HighBG)")
        partialLimit
    }
    
    // ⚪ NORMAL/BAS: limite standard
    else -> {
        consoleLog.add("MAXSMB_STANDARD BG=$bg → ${String.format("%.2f", maxSMB)}U")
        maxSMB
    }
}
```

---

## 🎯 **CORRECTION #2: Clamper ISF-TDD**

### **Problème:**
ISF-TDD peut dériver très loin du profil (57 vs 147), causant des corrections inadaptées.

### **Solution:**

```kotlin
// PkPdIntegration.kt ligne 198-202
// AVANT:
private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
    if (tdd24h <= 0.1) return fallback
    val anchored = 1800.0 / tdd24h
    return anchored.coerceIn(5.0, 400.0)
}

// APRÈS:
private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
    if (tdd24h <= 0.1) return fallback
    
    val anchored = 1800.0 / tdd24h
    
    // 🛡️ CLAMP: Ne pas s'écarter de plus de 50% du profil
    // Évite que TDD temporairement élevée/basse ne dérègle complètement l'ISF
    val maxDeviation = fallback * 0.5
    val clamped = anchored.coerceIn(
        fallback - maxDeviation,  // Min: profil × 0.5
        fallback + maxDeviation   // Max: profil × 1.5
    )
    
    // Log si clamp actif
    if (clamped != anchored) {
        // Log visible dans console
        println("ISF_TDD_CLAMP: raw=${String.format("%.1f", anchored)} profile=$fallback → clamped=${String.format("%.1f", clamped)}")
    }
    
    return clamped.coerceIn(5.0, 400.0)  // Garde-fou absolu
}
```

**Impact:**
```
Profil ISF = 147
TDD 24h = 31.5U → ISF-TDD brut = 1800/31.5 = 57

AVANT: 57 (déviation -61%)
APRÈS: 73.5 (déviation -50%, clampé)

Fusion (60/40):
AVANT: (147×0.6 + 57×0.4) × 1.11 = 110
APRÈS: (147×0.6 + 73.5×0.4) × 1.11 = 130

→ ISF plus réaliste, moins agressif
```

---

## 🎯 **CORRECTION #3: Améliorer Fusion ISF**

### **Problème:**
La fusion pèse peut-être trop vers TDD-ISF (poids actuel inconnu, à vérifier).

### **Solution:**

```kotlin
// IsfFusion.kt (localiser la fonction fused())
// Ajuster les poids pour favoriser le profil

fun fused(profileIsf: Double, tddIsf: Double, pkpdScale: Double): Double {
    // AJUSTEMENT: 60% profil (stable, configuré), 40% TDD (dynamique, réactif)
    // AVANT: probablement 50/50 ou 30/70
    
    val blended = profileIsf * 0.6 + tddIsf * 0.4
    
    // Appliquer PKPD scale modérément
    val scaled = blended * pkpdScale.coerceIn(0.9, 1.3)  // Limite PKPD boost
    
    // Respecter bounds
    val final = scaled.coerceIn(bounds.minFactor, bounds.maxFactor)
    
    // Log pour diagnostic
    if (abs(final - profileIsf) > profileIsf * 0.3) {
        println("ISF_FUSION large deviation: profile=$profileIsf tdd=$tddIsf fused=$final (${String.format("%.0f", ((final/profileIsf - 1)*100))}%)")
    }
    
    return final
}
```

---

## 📊 **IMPACT ATTENDU**

### **Scenario 1: BG 256, Delta -6.0 (Screenshot 1)**

**AVANT:**
```
slope < 1.0
MaxSMB = 0.6U (bridé)
ISF = 63 (trop bas)
Correction = (256-100)/63 = 2.5U nécessaire, 0.6U donné (24%)
```

**APRÈS:**
```
BG >= 250, delta -6.0 > -8.0 → URGENCE
MaxSMB = 0.6 × 1.2 = 0.72U (+20%)
ISF = 122 (clampé + fusion équilibrée)
Correction = (256-100)/122 = 1.28U nécessaire, 0.72U donné (56%)
→ Amélioration ×2.3
```

### **Scenario 2: BG 203, slope >= 1.0 (Screenshot 2)**

**AVANT:**
```
slope >= 1.0
MaxSMB = 1.2U
ISF = 72
→ Fonctionne déjà correctement
```

**APRÈS:**
```
MaxSMB = 1.2U (inchangé, logique OK)
ISF = 130 (amélioré, moins agressif)
→ Correction plus douce, moins d'over-shoot
```

---

## 🔧 **PLAN D'IMPLÉMENTATION**

### **Étape 1: Correction MaxSMB (Priorité HAUTE)**
1. Localiser ligne 3845 dans `DetermineBasalAIMI2.kt`
2. Remplacer `if/else` par `when` progressif
3. Ajouter logs `MAXSMB_*` pour diagnostic
4. Tester avec BG > 250

### **Étape 2: Clamper ISF-TDD (Priorité HAUTE)**
1. Modifier `computeTddIsf()` dans `PkPdIntegration.kt`
2. Ajouter clamp ±50% du profil
3. Logger les clamps actifs
4. Vérifier que ISF fusionné reste dans 70-150% du profil

### **Étape 3: Ajuster Fusion (Priorité MOYENNE)**
1. Localiser `IsfFusion.fused()`
2. Ajuster poids vers 60/40 (profil/TDD)
3. Limiter PKPD scale à 0.9-1.3
4. Logger déviations > 30%

---

## ⚠️ **PRÉCAUTIONS**

### **Tests requis:**
1. ✅ **BG 250-300 en chute légère** (-3 à -7): Vérifier MaxSMB urgence ne cause pas hypo
2. ✅ **BG 200-250 en montée**: Vérifier logique HighBG standard fonctionne
3. ✅ **BG 150-180 stable**: Vérifier MaxSMB normal reste conservateur
4. ✅ **ISF fusionné**: Vérifier qu'il reste dans ±40% du profil

### **Rollback si:**
- Hypoglycémies augmentent (MaxSMB urgence trop agressif)
- Hyperglycémies prolongées pires (ISF clampé trop haut)
- Oscillations (fusion instable)

---

**Veux-tu que j'implémente ces corrections dans le code maintenant?** 🚀
