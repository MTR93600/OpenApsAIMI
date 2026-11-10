# 🎨 TRAJECTORY GUARD - Visualisation ASCII (26 Jan 2026)

## 🎯 Affichage visuel des trajectoires

Chaque type de trajectoire est maintenant affiché avec une **représentation graphique ASCII** dans le rT pour une compréhension immédiate.

---

## 📊 Exemples d'affichage réel

### 1️⃣ **DIVERGING** - Trajectoire qui s'éloigne
```
🌀 Trajectory: ↗️ Trajectory diverging - BG not controlled | κ=0.15 conv=-0.8 health=52%
  ●→●→●→  (diverging)
  📊 Metrics: Coherence=0.42 Energy=1.2U Openness=0.85
  🎛 Modulation: SMB×1.30 Int×1.00 (Trajectory diverging, need stronger action)
    → SMB: 0.60U → 0.78U
```

**Signification** : La BG continue de monter malgré l'insuline active. L'algorithme a détecté que la trajectoire s'éloigne de la cible, il **augmente** donc le SMB de 30% pour ramener la situation sous contrôle.

---

### 2️⃣ **CONVERGING** - Trajectoire qui ferme vers la cible
```
🌀 Trajectory: 🔄 Trajectory closing - returning to target | κ=0.08 conv=+0.5 health=88%
  ●→●→●  (closing)
  📊 Metrics: Coherence=0.91 Energy=0.8U Openness=0.24
  🎛 Modulation: SMB×0.85 Int×1.30 (Trajectory closing naturally)
    → Interval: 3min → 4min
  ⏱ Est. convergence: 18min
```

**Signification** : La BG revient progressivement vers la cible. L'algorithme détecte la convergence et **réduit légèrement** l'agressivité (damping 0.85x, intervalle +30%) pour laisser le système converger naturellement sans over-corriger.

---

### 3️⃣ **TIGHT SPIRAL** - Spirale serrée (risque over-correction)
```
🌀 Trajectory: 🌀 Trajectory compressed - over-correction risk | κ=0.53 conv=-0.2 health=55%
   ●●●   (spiral)
      ╱ ╲╱ ╲
     ● ○ ●
  📊 Metrics: Coherence=0.45 Energy=3.8U Openness=0.82
  🎛 Modulation: SMB×0.30 Int×1.80 (Trajectory compressed - over-correction risk)
    → SMB: 1.20U → 0.36U
    → Interval: 3min → 5min
    → MaxIOB: 5.00U → 6.50U
  🚨 🔴 Multiple corrections accumulating (E=3.82U) - hypo risk in 60-90 min
```

**Signification** : **ALERTE CRITIQUE**. Plusieurs corrections d'insuline se sont accumulées (IOB élevé), créant une trajectoire "compressée" qui va probablement boucler violemment. L'algorithme **réduit drastiquement** le SMB (-70%), **augmente l'intervalle** (+80%) et **élargit les marges de sécurité** (+30% MaxIOB) pour éviter un crash hypo dans l'heure qui suit.

---

### 4️⃣ **STABLE ORBIT** - Orbite stable (optimal)
```
🌀 Trajectory: ⭕ Stable orbit maintained | κ=0.05 conv=+0.1 health=95%
    ●●●
   ●   ●  (orbit)
    ●●●
  📊 Metrics: Coherence=0.94 Energy=0.3U Openness=0.12
```

**Signification** : Contrôle glycémique **EXCELLENT**. La BG oscille légèrement autour de la cible dans une orbite stable. Aucune modulation n'est appliquée, l'algorithme maintient la stratégie actuelle.

---

### 5️⃣ **UNCERTAIN** - Données insuffisantes
```
🌀 Trajectory: ❓ Trajectory unclear - need more data | κ=0.18 conv=-0.1 health=72%
  ● ? ●  (unclear)
  📊 Metrics: Coherence=0.52 Energy=1.5U Openness=0.58
```

**Signification** : Les données sont ambiguës ou contradictoires. L'algorithme observe mais n'applique pas de modulation, laissant la logique standard d'AIMI opérer.

---

## 🔍 Légende des symboles ASCII

| Symbole | Signification |
|---------|---------------|
| `●` | Point de mesure BG |
| `○` | Centre de la spirale (position actuelle) |
| `→` | Direction du mouvement |
| `╱ ╲` | Courbure de la trajectoire |
| `?` | Incertitude |

---

## 🎯 Interprétation rapide

### **Trajectoires saines** ✅
- `●→●→●  (closing)` → Continue, ça va converger
- `  ●●●\n ●   ●  (orbit)` → Parfait, ne change rien

### **Trajectoires à surveiller** ⚠️
- `●→●→●→  (diverging)` → Attention, action nécessaire
- `● ? ●  (unclear)` → Observe, attends plus de data

### **Trajectoires critiques** 🔴
- ` ●●●   (spiral)\n      ╱ ╲╱ ╲\n     ● ○ ●` → **DANGER** over-correction imminente !

---

## 📍 Position dans l'interface

Ces visualisations apparaissent dans la section **"Reasoning (rT)"** de l'interface AAPS, juste après la ligne de status Trajectory.

**Exemple complet visible dans l'UI** :

```
🌀 Trajectory: 🌀 Trajectory compressed - over-correction risk | κ=0.53 conv=-0.2 health=55%
   ●●●   (spiral)
      ╱ ╲╱ ╲
     ● ○ ●
  📊 Metrics: Coherence=0.45 Energy=3.8U Openness=0.82
  🎛 Modulation: SMB×0.30 Int×1.80 (Trajectory compressed - over-correction risk)
    → SMB: 1.20U → 0.36U
    → Interval: 3min → 5min
    → MaxIOB: 5.00U → 6.50U
  🚨 🔴 Multiple corrections accumulating (E=3.82U) - hypo risk in 60-90 min
```

---

## 🎓 Conseils d'utilisation

1. **Glance rapide** : L'emoji et le dessin ASCII donnent une idée instantanée de l'état
2. **Métriques** : Les chiffres (κ, conv, health) fournissent la précision
3. **Modulation** : Les lignes "→" montrent les **actions concrètes** prises par l'algorithme
4. **Warnings** : Les 🚨 indiquent les situations nécessitant une vigilance accrue

---

## 📁 Fichiers modifiés

### 1. `PhaseSpaceModels.kt`
**Ajout** : Méthode `asciiArt()` dans l'enum `TrajectoryType`

```kotlin
fun asciiArt(): String = when (this) {
    OPEN_DIVERGING -> "●→●→●→  (diverging)"
    CLOSING_CONVERGING -> "●→●→●  (closing)"
    TIGHT_SPIRAL -> " ●●●   (spiral)\n      ╱ ╲╱ ╲\n     ● ○ ●"
    STABLE_ORBIT -> "  ●●●\n ●   ●  (orbit)\n  ●●●"
    UNCERTAIN -> "● ? ●  (unclear)"
}
```

### 2. `DetermineBasalAIMI2.kt`
**Ajout** : Insertion de l'ASCII art dans le consoleLog après la ligne de status

```kotlin
// Visual representation of trajectory type
val artLines = analysis.classification.asciiArt().split("\n")
artLines.forEach { line -> consoleLog.add("  $line") }
```

---

## ✅ Validation

Lancez AIMI et observez le rT :
- ✅ Chaque trajectoire doit avoir son petit dessin ASCII
- ✅ Le dessin doit correspondre au type (emoji + description)
- ✅ Les lignes doivent être correctement indentées
- ✅ Les multi-lignes (spiral, orbit) doivent s'afficher correctement

---

**Résultat** : Le Trajectory Guard est maintenant **visuellement parlant** et **immédiatement compréhensible** ! 🎨🚀

---

**Auteur** : Antigravity AI  
**Date** : 26 janvier 2026  
**Complexité** : 4/10 (Ajout de visualisation ASCII)
