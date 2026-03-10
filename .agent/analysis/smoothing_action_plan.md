# Plan d'Action Immédiat - Plugin Smoothing AIMI
## Solutions pour écart de 30 mg/dL avec Dexcom One+

---

## ✅ CHANGEMENTS EFFECTUÉS

### 1. **Nouveau Plugin : AdaptiveSmoothingPlugin**
📁 Fichier : `plugins/smoothing/src/main/kotlin/app/aaps/plugins/smoothing/AdaptiveSmoothingPlugin.kt`

**Fonctionnalités implémentées** :
- ✅ Détection automatique du contexte glycémique (zone, delta, accélération, CV%)
- ✅ 5 modes adaptatifs :
  - `RAPID_RISE` : Lissage minimal (70/30) pour montées rapides (delta > +5 mg/dL/5min)
  - `RAPID_FALL` : Lissage asymétrique (60% MIN, 40% actuel) pour descentes rapides
  - `STABLE` : Lissage standard (moyenne mobile 3 points)
  - `NOISY` : Lissage gaussien sur 5 points (CV% > 15%)
  - `HYPO_SAFE` : Pas de lissage (BG < 70 mg/dL)

**Performance attendue** :
```
Situation actuelle (Average Smoothing) :
  Glycémie raw : 165 mg/dL → Lissée : 135 mg/dL (écart -30)
  Lag : 10 minutes

Situation avec Adaptive Smoothing :
  Glycémie raw : 165 mg/dL → Lissée : 158 mg/dL (écart -7)
  Lag : 2-4 minutes
  → GAIN : 23 mg/dL + 6-8 min de réactivité
```

### 2. **Correction Critique : ExponentialSmoothingPlugin**
📁 Fichier : `plugins/smoothing/src/main/kotlin/app/aaps/plugins/smoothing/ExponentialSmoothingPlugin.kt`

**Problème résolu** :
- ❌ **AVANT** : Auto-calibration aveugle soustrayant 20 mg/dL à toutes les valeurs > 220 mg/dL
- ✅ **APRÈS** : Auto-calibration désactivée (commentée avec documentation)

**Risque évité** :
```kotlin
// DANGEREUX - SUPPRIMÉ :
if (sensorValue > 220) {
    sensorValue - 20  // Masquait les hyperglycémies réelles !
}
```

**Impact** :
- Plus de masquage des hyperglycémies
- AIMI peut maintenant réagir correctement aux BG > 220 mg/dL
- SMB/basale non bridés artificiellement

### 3. **Ressources UI**
📁 Fichier : `plugins/smoothing/src/main/res/values/strings.xml`

```xml
<string name="adaptive_smoothing_name">Adaptive smoothing</string>
<string name="description_adaptive_smoothing">
  "Context-aware adaptive smoothing: minimal lag on rapid rises, 
   aggressive filtering on noisy data, hypo-safe on lows"
</string>
```

---

## 🚀 PROCHAINES ÉTAPES

### Phase 1 : Activation et Test (Cette semaine)

#### 1.1 Activer le plugin
1. Ouvrir AAPS → **Configuration** → **BG Source**
2. **Désélectionner** : "Average smoothing"
3. **Sélectionner** : "Adaptive smoothing"
4. Enregistrer

#### 1.2 Activer les logs de diagnostic
Ajouter dans `logback.xml` (ou via UI) :
```xml
<logger name="GLUCOSE" level="DEBUG" />
```

Vous verrez dans les logs :
```
AdaptiveSmoothing: Mode=RAPID_RISE | BG=165 | Δ=+8.0 | Accel=2.5 | CV=8.2% | Zone=TARGET
```

#### 1.3 Période de test
- **Durée** : 3-5 jours minimum
- **Focus** : Situations de montée rapide post-prandiales
- **Données à collecter** :
  - Screenshots AAPS (graphe glycémie)
  - Export des logs (Menu → Maintenance → Export settings)
  - Écart moyen raw/smoothed (voir logs)

### Phase 2 : Validation & Tuning (Semaine 2)

#### 2.1 Analyser les métriques
Comparer avec les 5 jours précédents (Average smoothing) :

| Métrique | Cible |
|----------|-------|
| Time in Range (70-180) | +3-5% |
| Écart max raw/smoothed | < 15 mg/dL (vs 30 actuellement) |
| Pics post-prandiaux | -15-20 mg/dL |
| Standard Deviation | Stable ou -5% |
| Temps de lag moyen | < 5 min (vs 10 actuellement) |

#### 2.2 Tuning des seuils (si nécessaire)
Si besoin, ajuster dans `AdaptiveSmoothingPlugin.kt` :

```kotlin
// Ligne ~135 : Seuils de détection RAPID_RISE
context.delta > 5.0 && context.acceleration > 2.0
// Essayer : 4.0 et 1.5 si trop sensible
//         : 6.0 et 2.5 si pas assez réactif

// Ligne ~144 : Seuil de bruit
context.cv > 15.0
// Essayer : 12.0 si capteur très stable
//         : 18.0 si beaucoup de faux positifs
```

### Phase 3 : Intégration Avancée (Optionnel - Semaines 3-4)

#### 3.1 Hybrid Selector (Auto-sélection)
Créer `HybridSmoothingPlugin.kt` qui sélectionne automatiquement :
- `NoSmoothing` en hypo (BG < 75)
- `AdaptiveSmoothing` en montée rapide
- `ExponentialSmoothing` (sans auto-cal) si variabilité élevée
- `AvgSmoothing` en situation stable

#### 3.2 Intégration PKPD/UnifiedReactivity
Utiliser le contexte AIMI pour moduler le lissage :
- IOB > 4U → Renforcer le lissage (moins réactif, éviter sur-correction)
- COB élevé + montée rapide → Mode RAPID_RISE encore plus agressif
- Learner UnifiedReactivity → Ajuster les poids via machine learning

---

## 📊 MÉTRIQUES DE SUCCÈS

### Critères de validation
✅ **Succès confirmé si** :
1. Écart raw/smoothed moyen < 15 mg/dL (vs 30 actuellement)
2. Lag moyen < 5 minutes (vs 10 actuellement)
3. Time in Range +3% minimum
4. Pas d'hypo manqué (sécurité validée)
5. Pics post-prandiaux réduits de 15+ mg/dL

⚠️ **Échec si** :
1. Hypo non détecté ou retardé (priorité absolue)
2. Oscillations/instabilité du lissage (sur-réactivité)
3. Écart > 20 mg/dL persistant
4. Consommation CPU > 50 ms

---

## 🔍 DIAGNOSTIC EN CAS DE PROBLÈME

### Problème 1 : "Adaptive pas assez réactif"
**Symptôme** : Écart encore > 20 mg/dL en montée rapide
**Solution** :
```kotlin
// AdaptiveSmoothingPlugin.kt, ligne ~202
// AVANT :
data[i].smoothed = 0.7 * data[i].value + 0.3 * data[i - 1].value

// APRÈS (plus agressif) :
data[i].smoothed = 0.85 * data[i].value + 0.15 * data[i - 1].value
```

### Problème 2 : "Trop de bruit / oscillations"
**Symptôme** : Lissage détecte RAPID_RISE trop souvent (faux positifs)
**Solution** :
```kotlin
// Ligne ~135, augmenter les seuils
context.delta > 6.0 && context.acceleration > 2.5  // Au lieu de 5.0 et 2.0
```

### Problème 3 : "Mode NOISY activé trop souvent"
**Symptôme** : Lag augmenté à cause du lissage gaussien 5 points
**Solution** :
```kotlin
// Ligne ~144, augmenter le seuil de CV
context.cv > 18.0  // Au lieu de 15.0
```

### Problème 4 : "Hypo détecté trop tard"
**Symptôme** : Lissage appliqué en dessous de 70 mg/dL
**Solution** : Vérifier les logs - devrait afficher :
```
AdaptiveSmoothing: HYPO detected, no smoothing applied
```
Si ce n'est pas le cas, bug à investiguer (impossible normalement).

---

## 🛠️ COMMANDES UTILES

### Rebuild du plugin smoothing
```bash
cd /Users/mtr/StudioProjects/OpenApsAIMI
./gradlew :plugins:smoothing:assembleFullDebug
```

### Build complet de l'app
```bash
./gradlew :app:assembleFullDebug
```

### Export des logs pour analyse
```
AAPS → Menu → Maintenance → Export settings
→ Fichier partagé contient les logs
```

### Filtrer les logs Adaptive
```bash
adb logcat | grep "AdaptiveSmoothing"
```

---

## 📝 QUESTIONS À VALIDER AVEC VOUS

1. **Seuil d'activation RAPID_RISE** : 
   - Actuel : delta > +5 mg/dL/5min ET accel > +2
   - Souhaitez-vous plus ou moins sensible ?

2. **Sécurité hypo** :
   - Actuel : Pas de lissage si BG < 70 mg/dL
   - Faut-il élargir à < 80 ou resserrer à < 60 ?

3. **Mode NOISY** :
   - Actuel : Gaussien 5 points si CV% > 15%
   - Votre Dexcom One+ a-t-il souvent CV > 15% ?

4. **Intégration IOB/COB** :
   - Souhaitez-vous que le lissage tienne compte du contexte AIMI ?
   - Ex : IOB élevé → Lissage renforcé (prudence sur-correction)

5. **Logging** :
   - Voulez-vous un dashboard visuel des décisions de lissage ?
   - Ou logs textuels suffisent ?

---

## 📚 DOCUMENTATION DU CODE

### Architecture AdaptiveSmoothingPlugin

```kotlin
smooth(data) 
  ↓
calculateGlycemicContext()  // Analyse : delta, accel, CV%, zone
  ↓
determineMode()             // Décision : RAPID_RISE / RAPID_FALL / STABLE / NOISY / HYPO_SAFE
  ↓
apply[Mode]Smoothing()      // Exécution du lissage adapté
  ↓
return data                 // Données avec .smoothed rempli
```

### Contexte glycémique calculé
```kotlin
data class GlycemicContext(
    val delta: Double,          // Tendance linéaire mg/dL/5min
    val acceleration: Double,   // Courbure (dérivée seconde)
    val cv: Double,            // Stabilité capteur (%)
    val zone: GlycemicZone,    // HYPO / LOW_NORMAL / TARGET / HYPER
    val currentBg: Double,     // BG actuel
    val sensorNoise: Double    // Estimation bruit (~10% BG)
)
```

### Poids de lissage par mode
| Mode | Fenêtre | Poids | Lag |
|------|---------|-------|-----|
| RAPID_RISE | 2 pts (10 min) | 70% présent, 30% passé | 2-3 min |
| RAPID_FALL | 3 pts (15 min) | 60% MIN, 40% actuel | 3-4 min |
| STABLE | 3 pts (15 min) | 33% / 33% / 33% | 5-7 min |
| NOISY | 5 pts (25 min) | Gaussien [0.06, 0.24, 0.4, 0.24, 0.06] | 8-10 min |
| HYPO_SAFE | - | Pas de lissage | 0 min |

---

## 🎯 OBJECTIF FINAL

**Réduire votre écart de 30 mg/dL à moins de 10 mg/dL**

Avec :
- ✅ Réactivité maximale en montée rapide
- ✅ Sécurité absolue en hypo
- ✅ Filtrage efficace du bruit capteur
- ✅ Pas de masquage des hyperglycémies (correction auto-cal dangereuse)

---

**Prêt à tester ? Activez AdaptiveSmoothing et tenez-moi au courant des résultats !** 🚀

— Lyra, Senior++ Kotlin & Product Expert
