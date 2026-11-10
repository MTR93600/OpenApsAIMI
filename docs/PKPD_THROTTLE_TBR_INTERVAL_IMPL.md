# PKPD THROTTLE — IMPLÉMENTATION TBR & INTERVAL SMB

**Date:** 2025-12-18 17:00  
**Objectif:** Implémenter la partie manquante (TBR dynamique + Interval SMB ajusté)

---

## 🎯 CE QUI MANQUE ACTUELLEMENT

### ✅ Déjà Implémenté
1. **SMB Throttle** (réduction via `smbFactor`) → ✅ FONCTIONNE
2. **Logs PKPD_OBS / PKPD_THROTTLE** → ✅ FONCTIONNE
3. **Calcul throttle** (`SmbTbrThrottleLogic`) → ✅ FONCTIONNE

### ❌ Pas Implémenté
1. **Interval SMB** (`intervalAddMin`) → Calculé mais pas utilisé
2. **TBR Action** (`preferTbr`) → Juste suggéré en logs, pas appliqué

---

## 📋 STRATÉGIE D'IMPLÉMENTATION RECOMMANDÉE

### **Approche Progressive (Safe & Robuste)**

#### **OPTION A: Simple & Conservative (RECOMMANDÉ)**
1. **Interval SMB**: Ajouter `intervalAddMin` au résultat de `calculateSMBInterval()`
2. **TBR**: Garder comme suggestion uniquement (logs)
3. **Validation**: Observer pendant 3-5 jours
4. **Phase 2**: Si efficace → ajouter TBR dynamique

**Avantages:**
- ✅ Faible risque
- ✅ Observable (logs)
- ✅ Réversible
- ✅ Pas de conflit avec logique basal existante

**Inconvénients:**
- ⚠️ TBR reste manuelle (suggestion uniquement)

---

#### **OPTION B: Complet (Plus Audacieux)**
1. **Interval SMB**: Ajouter `intervalAddMin`
2. **TBR Boost**: Appliquer un boost TBR quand `preferTbr=true`
3. **Coordination**: Assurer que TBR boost ne casse pas la logique existante

**Avantages:**
- ✅ Système complet onset→peak→tail
- ✅ Vrai pilotage SMB vs TBR

**Inconvénients:**
- ⚠️ Risque de conflit avec basal logic existante
- ⚠️ Nécessite validation extensive
- ⚠️ Peut créer des TBR "fantômes" si mal implémenté

---

## 🚀 IMPLÉMENTATION RECOMMANDÉE (OPTION A)

### 1️⃣ **Interval SMB (Priorité 1)**

**Principe:** Stocker `throttleIntervalAdd` comme variable de classe et l'ajouter dans `calculateSMBInterval()`

#### **Patch 1: Ajouter membre de classe**
```kotlin
// Ligne 337 (après insulinObserver)
private var pkpdThrottleIntervalAdd: Int = 0  // PKPD throttle interval boost
```

#### **Patch 2: Stocker intervalAdd lors du throttle**
```kotlin
// Ligne 1519 (dans finalizeAndCapSMB, après calcul throttle)
// Stocker interval add pour calculateSMBInterval
pkpdThrottleIntervalAdd = throttle.intervalAddMin
```

#### **Patch 3: Utiliser dans calculateSMBInterval**
```kotlin
// Ligne 2539 (juste avant return finalInterval)
// 🚀 PKPD Throttle: Add interval boost if near peak/onset unconfirmed
val pkpdBoost = pkpdThrottleIntervalAdd
if (pkpdBoost > 0) {
    finalInterval = (finalInterval + pkpdBoost).coerceAtMost(10)
    consoleLog.add("PKPD_INTERVAL_BOOST base=${finalInterval - pkpdBoost}m +${pkpdBoost}m → ${finalInterval}m")
}

return finalInterval
```

**Résultat:**
- Near peak (intervalAdd=5) → SMB espacés de +5 min
- Onset non confirmé (intervalAdd=3) → SMB espacés de +3 min
- Normal (intervalAdd=0) → Pas de changement

---

### 2️⃣ **TBR Suggestion (Logs Uniquement - Déjà Implémenté)**

**État actuel:**
```kotlin
if (throttle.preferTbr && gatedUnits < proposedFloat * 0.5) {
    rT.reason.append(" | 💡 TBR recommended (${throttle.reason})")
}
```

**Recommandation:** **Garder tel quel pour l'instant**

**Pourquoi?**
- La TBR est déjà calculée par `basalDecisionEngine` ou autre logique
- Forcer une TBR ici risque de créer des conflits
- La suggestion dans les logs permet à l'utilisateur de **manuellement** ajuster la TBR s'il le souhaite

---

### 3️⃣ **TBR Dynamique (Phase 2 - Optionnel)**

**Si on veut implémenter TBR automatique (après validation interval SMB):**

#### **Approche Safe:**
1. Créer un **signal TBR boost** dans un membre de classe
2. Le transmettre à la logique basal via un paramètre
3. La basal logic **suggère** une TBR plus élevée (pas force)

```kotlin
// Membre classe (ligne 338)
private var pkpdPreferTbrBoost: Double = 1.0  // 1.0 = normal, 1.2 = +20%, etc.

// Dans finalizeAndCapSMB (ligne 1520)
if (throttle.preferTbr) {
    pkpdPreferTbrBoost = 1.15  // +15% TBR suggestion
    consoleLog.add("PKPD_TBR_BOOST factor=1.15 reason=${throttle.reason}")
} else {
    pkpdPreferTbrBoost = 1.0
}

// Puis dans la logique basal (où TBR est calculée)
// Identifier où la TBR finale est décidée et multiplier par pkpdPreferTbrBoost
```

**Recherche nécessaire:**
- Trouver où la TBR finale est calculée
- S'assurer qu'on ne crée pas de conflit avec modes repas / LGS / etc.

---

## 📊 LOGS ATTENDUS (Après Implémentation)

### Scenario 1: Near Peak + High Activity
```
PKPD_OBS onset=✓ stage=PEAK corr=0.92 resid=0.70
PKPD_THROTTLE smbFactor=0.30 intervalAdd=5 preferTbr=true reason=Near peak
  ⚠️ SMB reduced 2.50 → 0.75U (PKPD throttle)
PKPD_INTERVAL_BOOST base=3m +5m → 8m
💡 TBR recommended (Near peak / High activity → SMB throttled)
```

### Scenario 2: Onset Non Confirmé
```
PKPD_OBS onset=✗ stage=RISING corr=0.32 resid=0.85
PKPD_THROTTLE smbFactor=0.60 intervalAdd=3 preferTbr=true reason=Onset unconfirmed
PKPD_INTERVAL_BOOST base=3m +3m → 6m
💡 TBR recommended (Onset unconfirmed, rising BG → TBR priority)
```

### Scenario 3: Tail (Normal)
```
PKPD_OBS onset=✓ stage=TAIL corr=0.88 resid=0.25
PKPD_THROTTLE smbFactor=1.00 intervalAdd=0 preferTbr=false reason=Tail stage
(pas de PKPD_INTERVAL_BOOST car intervalAdd=0)
```

---

## 🎯 RECOMMANDATION FINALE

### **Phase 1 (Immédiat): OPTION A - Interval SMB Uniquement**

**Patches à appliquer:**
1. ✅ Ajouter `pkpdThrottleIntervalAdd` comme membre
2. ✅ Stocker `throttle.intervalAddMin` lors du calcul
3. ✅ Ajouter boost dans `calculateSMBInterval()`

**Validation:**
- Tester pendant 3-5 jours
- Observer logs `PKPD_INTERVAL_BOOST`
- Vérifier que l'interval SMB monte bien quand near peak/onset non confirmé

---

### **Phase 2 (Après Validation): TBR Dynamique**

**Pré-requis:**
- Phase 1 validée et stable
- Analyse des patterns TBR existants
- Identification de la zone de calcul TBR finale

**Implémentation:**
- Ajouter `pkpdPreferTbrBoost` comme signal
- Intégrer dans la logique basal existante
- Validation extensive (1-2 semaines)

---

## 🚀 PRÊT À IMPLÉMENTER?

**Option A (Safe):** Je peux implémenter les 3 patches interval SMB maintenant (5 min)
**Option B (Complet):** Je peux ajouter aussi TBR boost (15 min + recherche logique basal)

**Quelle approche préférez-vous?** 🤔
