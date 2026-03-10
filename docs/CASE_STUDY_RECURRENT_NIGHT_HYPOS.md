# 🩺 CAS CLINIQUE - PATIENTE HYPOS RÉCURRENTES

**Date:** 2025-12-21 09:44  
**Profil:** Femme sans règles (phase lutéale permanente), thyroïde ablatée (équilibrée)  
**Problème:** Tendance hypos, notamment nocturnes

---

## 📊 **ANALYSE DU GRAPHIQUE**

### **Observations:**
1. **BG actuelle:** 54 mg/dL (hypoglycémie) avec flèche montante (+8 mg/dL)
2. **IOB:** 0.93U (encore significatif malgré l'hypo)
3. **COB:** 0g, BWP: <0U, CAGE: 68h, SAGE: 6d11h
4. **Basal:** 0.600U/h
5. **Pattern nocturne:** Hypos

récurrentes (points rouges) entre 3-6 AM
6. **Profil insuline:** RETRO Pump, OpenAPS AIMI, unknown 9%

### **Hypos visibles:**
- Décembre 20 vers 4-5 AM : descente brutale
- Décembre 21 (maintenant) : 54 mg/dL à 5:49 AM
- Pattern répétitif : descentes nocturnes

---

## 🔬 **HYPOTHÈSES PHYSIOPATHOLOGIQUES**

### **1. Phase lutéale permanente:**
**Impact:** Sans cycle, pas de variation folliculaire → Pas de pics d'estrogènes
- Estrogènes **↓** sensibilité insuline (effet normal en phase folliculaire)
- En phase lutéale constante: **Sensibilité insuline stable mais possiblement élevée**

**Conséquence:** Besoin insuline constant, risque de sur-insulinisation si profil calibré pour cycles normaux

### **2. Thyroïde ablatée équilibrée:**
**Impact théorique minimal** SI substitution correcte
- Hypothyroïdie non substituée → **↑** sensibilité insuline
- Mais ici équilibrée → Normalement neutre

**À vérifier:** TSH récente? Dosage T4 libre?

### **3. Dawn phenomenon absent ou inversé:**
Le graphique montre descentes 3-6 AM au lieu de montées

**Causes possibles:**
- Basal nuit trop élevé
- Absence de cortisol matinal (rare, mais possible si surrénales fatiguées)
- Libération hormonale atypique (GH, cortisol)

---

## 🎯 **PISTES D'AMÉLIORATION AIMI**

### **A. AJUSTEMENTS PROFIL BASAL**

**1. Réduire basal nocturne (3-6 AM):**
```kotlin
// Actuellement: 0.600 U/h (apparemment trop)
// Proposer: -20 à -30% dans la fenêtre 3-6 AM
Basal 3-6 AM: 0.42-0.48 U/h (au lieu de 0.60)
```

**2. Autodrivebasalmaxratio:**
Si basal est géré par autodrive, vérifier le ratio max:
```kotlin
// Permettre réduction plus agressive la nuit
autodriveMaxBasal: Vérifier si bridé à 2-3×
```

### **B. ISF NOCTURNE**

**Sensibilité possiblement élevée la nuit:**
```kotlin
// ISF Profile: Potentiellement augmenter ISF nuit
// Exemple: ISF jour 147 → ISF nuit 170-180
// (Plus élevé = moins d'insuline pour correction)
```

### **C. LOW BG TARGET NUIT**

**Modifier la cible basse nocturne:**
```
preferences:
  Low Glucose Suspend Threshold: 70 → 80 mg/dL
  Target BG night (3-6 AM): 110-120 mg/dL (au lieu de 100)
```

### **D. PKPD - ISF FUSION**

**Si ISF-TDD est trop agressif:**
```kotlin
// PkPdIntegration.kt ligne 198-202
// ISF-TDD réduit l'ISF → Plus d'insuline
// Pour cette patiente: Favoriser Profile ISF

OApsAIMIIsfFusionMinFactor: 0.7 → 0.5 
// (Permet moins de poids à TDD-ISF si hypos)

OApsAIMIIsfFusionMaxFactor: 1.3 → 1.1
// (Réduit l'agressivité max)
```

---

## 🛠️ **MODIFICATIONS CODE POSSIBLES**

### **1. Détecteur d'hypos récurrentes**

Ajouter une logique pour détecter pattern hypo nocturne:

```kotlin
// Dans DetermineBasalAIMI2.kt
fun detectRecurrentNightHypos(): Boolean {
    // Analyser historique 3-7 jours
    // Si hypo <70 entre 3-6 AM répété ≥ 2 fois/semaine
    // → Flag "RECURRENT_NIGHT_HYPO"
    // → Réduire automatiquement basal nuit de 10-20%
}
```

### **2. Fork spécifique "Phase lutéale permanente"**

Dans `WCycleIntegration.kt`, ajouter mode:

```kotlin
enum class ContraceptiveType {
    NONE,
    PILL_COMBINED,
    PILL_PROGESTIN,
    IUD_HORMONAL,
    IUD_COPPER,
    IMPLANT,
    PERMANENT_LUTEAL  // NOUVEAU pour ablation/ménopause
}

// Logique:
if (contraceptive == PERMANENT_LUTEAL) {
    // Pas de variation cyclique
    // ISF constant (pas de modulation folliculaire/lutéale)
    // Potentiellement ↑ sensibilité globale
    return WCycleAdjustment(
        basalMultiplier = 0.95,  // -5% global
        isfMultiplier = 1.05,     // +5% (moins agressif)
        carbMultiplier = 1.0
    )
}
```

### **3. Low BG Guard amélioré**

Renforcer la protection hypo nocturne:

```kotlin
// DetermineBasalAIMI2.kt ligne ~1420
// Dans finalizeAndCapSMB()

val isNightTime = currentHour in 3..6
val recentHypo = bg < 80 || (prevBg != null && prevBg < 75)

if (isNightTime && recentHypo) {
    // Extra prudence
    val nightFactor = 0.5  // Réduit SMB de 50% supplémentaire
    smbCapped = (smbCapped * nightFactor).coerceAtMost(0.3)
    consoleLog.add("NIGHT_HYPO_GUARD SMB reduced ×0.5 -> ${smbCapped}U")
}
```

---

## 📋 **PLAN D'ACTION IMMÉDIAT**

### **Étape 1: Ajustements Profil (Urgent)**
```
1. Basal 3-6 AM: 0.60 → 0.45 U/h (-25%)
2. ISF nuit: Actuel → +20% (ex: 147 → 176)
3. Target BG nuit: 100 → 115 mg/dL
```

### **Étape 2: Préférences AIMI**
```
OApsAIMIIsfFusionMaxFactor: 1.3 → 1.1
OApsAIMIMaxSMB: Vérifier si > 1.5U → Réduire à 1.2U
Low Glucose Suspend: 70 → 75 mg/dL
```

### **Étape 3: Surveillance (3-5 jours)**
```
- Logger hypos < 70 mg/dL
- Vérifier si pattern 3-6 AM persiste
- Si amélioration: OK
- Si persiste: Réduire basal nuit encore (-10%)
```

### **Étape 4: Bilan hormonal**
```
- TSH, T4 libre (vérifier substitution thyroïde)
- Cortisol 8h (vérifier dawn phenomenon)
- HbA1c (contexte global)
```

---

## 🔍 **DIAGNOSTIC DIFFÉRENTIEL**

| Cause | Probabilité | Action |
|-------|-------------|--------|
| **Basal nuit trop élevé** | ⭐⭐⭐⭐⭐ | Réduire -25% |
| **ISF trop bas (trop agressif)** | ⭐⭐⭐⭐ | Augmenter ISF nuit |
| **Phase lutéale permanente** | ⭐⭐⭐ | Ajuster WCycle mode |
| **Thyroïde déséquilibrée** | ⭐⭐ | Vérifier TSH/T4 |
| **Insuline périmée/dégradée** | ⭐ | Vérifier CAGE/SAGE |

---

## 🎯 **MODIFICATIONS CODE RECOMMANDÉES**

### **1. PRIORITÉ HAUTE**
✅ Ajouter détecteur hypos récurrentes nocturnes
✅ Renforcer Low BG Guard 3-6 AM

### **2. PRIORITÉ MOYENNE**
- Ajouter mode "PERMANENT_LUTEAL" dans WCycle
- Logs diagnostics hypo pattern

### **3. PRIORITÉ BASSE**
- UI alerte "Recurrent night hypos detected"

---

## 📊 **MÉTRIQUES À MONITORER**

```kotlin
// Ajouter dans AimiAdvisorService.kt
data class HypoMetrics(
    val hyposCount: Int,              // Total hypos <70
    val severeHyposCount: Int,        // Hypos <54
    val nightHyposCount: Int,         // Hypos 3-6 AM
    val nightHyposPercent: Double,    // % hypos nocturnes
    val avgHypoRecoveryMin: Double    // Temps moyen remontée
)
```

---

## 🚨 **SÉCURITÉ**

**ATTENTION:** Patiente à **HAUT RISQUE HYPO**

**Recommandations:**
1. ⚠️ Glucagon emergency kit accessible
2. ⚠️ CGM alarmes: Low 75, Urgent Low 60
3. ⚠️ Réveil nocturne programmé si pattern persiste
4. ⚠️ Snack protéines avant coucher (optionnel)

---

## 💊 **HYPOTHÈSE THYROÏDE**

**Si TSH élevée (hypothyroïdie sous-substituée):**
```
Hypothyroïdie → ↑ Sensibilité insuline → Hypos
Solution: Augmenter L-thyroxine
```

**Si TSH normale:**
```
Thyroïde OK → Problème est ailleurs (basal/ISF)
```

---

## 🎓 **CONCLUSION**

**Cause la plus probable:** 
**Basal nocturne trop élevé + ISF trop agressif**

**Actions immédiates:**
1. ✅ Réduire basal 3-6 AM: -25%
2. ✅ Augmenter ISF nuit: +20%
3. ✅ Target BG nuit: 115 mg/dL

**Modifications code:**
1. ✅ sanitizeJson() pour fix Unicode (FAIT)
2. ⏳ Détecteur hypos récurrentes (À FAIRE)
3. ⏳ Mode PERMANENT_LUTEAL (À FAIRE)

**Suivi:** 3-5 jours, puis réévaluer

---

**CAS ANALYSÉ** ✅  
**FIX JSON IMPLÉMENTÉ** ✅  
**BUILD SUCCESS** ✅
