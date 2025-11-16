# Manuel utilisateur – OpenAPS AIMI

Bienvenue dans AIMI (Adaptive Insulin Management Intelligence), le moteur prédictif d'AndroidAPS qui combine apprentissage automatique, surveillance physiologique et garde-fous avancés pour piloter basal et SMB (Super Micro-Bolus). AIMI observe votre historique glycémique, vos bolus, vos pas/rythme cardiaque et vos modes déclarés pour ajuster dynamiquement sensibilité, durée d'action de l'insuline et micro-bolus, tout en conservant les sécurités OpenAPS historiques.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L95-L175】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2480-L2648】

AIMI n'est pas une boîte noire : pensez-le comme un co-pilote. Plus vos données sont propres (profil à jour, enregistrement des repas, fermeture des boucles nocturnes), plus AIMI anticipe finement et stabilise vos glycémies.

---

## Sommaire
1. [Installation et activation](#installation-et-activation)
2. [Principes généraux et vérification du fonctionnement](#principes-généraux-et-vérification-du-fonctionnement)
3. [🔧 Réglages généraux](#-réglages-généraux)
4. [⚙️ Régulation basale & SMB](#️-régulation-basale--smb)
5. [🧠 Intelligence adaptative (ISF, PeakTime, PK/PD)](#-intelligence-adaptative-isf-peaktime-pkpd)
6. [💡 Modes & détection repas](#-modes--détection-repas)
7. [💪 Exercice & règles de sécurité](#-exercice--règles-de-sécurité)
8. [🌙 Mode nuit & croissance nocturne](#-mode-nuit--croissance-nocturne)
9. [❤️ Intégration fréquence cardiaque & pas (Wear OS)](#️-intégration-fréquence-cardiaque--pas-wear-os)
10. [♀️ WCycle – suivi du cycle menstruel](#️-wcycle--suivi-du-cycle-menstruel)
11. [Conseils d'ajustement rapide](#conseils-dajustement-rapide)
12. [Dépannage et interprétation des logs](#dépannage-et-interprétation-des-logs)
13. [Récapitulatif pédagogique](#récapitulatif-pédagogique)

---

## Installation et activation
1. **Activez le plugin** depuis *Configuration ▶️ Plugins ▶️ APS* et cochez **OpenAPS AIMI**. AIMI vérifie automatiquement que votre pompe accepte les basales temporaires.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L226-L238】
2. **Redémarrez la boucle** : au démarrage AIMI recharge vos sensibilités variables passées et installe son calculateur Kalman/PK-PD.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L140-L175】
3. **Autorisez les permissions** : si vous activez les pas/FC, assurez-vous que la montre Wear OS synchronise bien vers AAPS (voir section ❤️).
4. **Vérifiez l'état**
   - L'écran OpenAPS affiche *Algorithme AIMI* et la date du dernier calcul (`lastAPSRun`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L162-L165】
   - Les logs contiennent des raisons `AIMI+` lorsque l'adaptatif basal déclenche un kicker ou une micro-reprise.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
   - Les colonnes `SMB`/`Basal` du statut montrent les multiplicateurs WCycle ou NightGrowth lorsqu'ils sont actifs.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】

---

## Principes généraux et vérification du fonctionnement
- **Boucle complète** : AIMI récupère le `GlucoseStatusAIMI`, calcule un plan basale via `BasalPlanner`, applique `AIMIAdaptiveBasal` pour les plateaux et ajuste les SMB via PK/PD et ISF adaptatif.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L25-L113】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L109】
- **Apprentissage continu** : les paramètres PK/PD (DIA et temps de pic) sont mis à jour lorsqu'assez d'IOB est disponible, sauf si du sport ou des graisses retardées sont détectés.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L52】
- **Logs utiles** : `rT.reason` inclut les déclencheurs (plateau kicker, NGR, WCycle). Les CSV AIMI (`AAPS/oapsaimi*.csv`) enregistrent chaque décision pour analyse ultérieure.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】

---

## 🔧 Réglages généraux
Ces paramètres posent la base physiologique utilisée par toutes les briques AIMI.

### 🔹 `OApsAIMIMLtraining`
- **Valeur par défaut :** `false` (désactivé).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **But :** autoriser l'entraînement du modèle SMB local (fichier `oapsaimiML_records.csv`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L223】
- **Effet :** en mode entraînement, AIMI consigne vos boucles pour affiner le réseau `neuralnetwork5` après accumulation d'au moins 60 min de données.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L244】
- **Ajuster si :**
  - **Hypos fréquentes :** laissez désactivé le temps d'identifier la source avant de réentraîner.
  - **Hypers fréquentes :** activez pour apprendre vos patterns, mais surveillez la sécurité (SMB est toujours borné).
  - **Variabilité :** n'entraînez qu'après avoir stabilisé vos profils (au moins 3-4 jours de données homogènes).

### 🔹 `OApsAIMIweight`, `OApsAIMICHO`, `OApsAIMITDD7`
- **Valeurs par défaut :** 50 kg, 50 g, 40 U respectivement.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L67-L69】
- **But :** renseigner des bornes physiologiques utilisées pour initialiser le filtre de Kalman ISF et la PK/PD si votre historique est vide.
- **Effet :** un poids/TDD sous-estimé rendra l'ISF trop agressif; un CHO moyen trop faible détectera plus souvent des repas « gras ».
- **Ajuster :**
  - **Hypos :** augmentez légèrement `OApsAIMIweight` ou `OApsAIMITDD7` vers vos valeurs réelles → l'ISF se radoucit.
  - **Hypers :** ajustez `OApsAIMICHO` vers vos apports réels pour que les modèles repas restent réalistes.
  - **Variabilité :** harmonisez ces paramètres avec votre profil (mêmes unités que les rapports journaliers).

### 🔹 `AimiUamConfidence`
- **Valeur par défaut :** `0.5` (confiance moyenne).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L144-L146】
- **But :** pondérer l'apprentissage « UAM » quand la détection de repas non annoncés est fiable.
- **Effet :** plus la confiance est élevée, moins l'algorithme dynamique de sensibilité (IsfAdjustmentEngine) s'éloigne du profil.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L13-L36】
- **Ajuster :**
  - **Hypos post-UAM :** augmentez (0.6–0.8) pour limiter la baisse d'ISF.
  - **Hyper prolongées non annoncées :** réduisez (0.3–0.4) afin que l'ISF s'adapte plus vite.
  - **Variabilité :** laissez par défaut le temps que le moteur accumule assez de Kalman trust.

### 🔹 `OApsAIMIEnableBasal`
- **Valeur par défaut :** `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **But :** activer une basale prédictive spécifique (legacy). Actuellement non utilisée (commentée) : laissez désactivé sauf demande spécifique.

### 🔹 `OApsAIMIautoDrive`
- **Valeur par défaut :** `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **But :** activer l'autoDrive, c’est-à-dire l’utilisation automatique des facteurs modes (repas, auto-bolus) et du profil combiné (`combinedDelta`).
- **Effet :** applique les facteurs `autodrivePrebolus`, `autodrivesmallPrebolus`, limite le basal via `autodriveMaxBasal` et ajuste les déclencheurs `combinedDelta`/`AutodriveDeviation`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】
- **Ajuster :** commencez par OFF, puis activez lorsque vos modes repas sont bien renseignés.

### 🔹 Paramètres cibles AutoDrive (`OApsAIMIAutodriveBG`, `OApsAIMIAutodriveTarget`)
- **Valeurs par défaut :** 90 et 70 mg/dL.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L83-L86】
- **Effet :** servent de références pour la détection d'écarts minimes et le déclenchement des micro-prébolus autoDrive.
- **Conseil :** Gardez `AutodriveBG` au-dessus de votre cible réelle (≈ 90–100) pour laisser AIMI absorber les petites remontées sans sur-corriger.

---

## ⚙️ Régulation basale & SMB
AIMI contrôle simultanément la basale temporaire (kickers, anti-stall) et l'intensité des SMB via ses paramètres.

### Paramètres SMB globaux
| Paramètre | Valeur par défaut | Rôle | Ajustement hypos | Ajustement hypers | Variabilité |
|-----------|------------------|------|------------------|-------------------|-------------|
| `OApsAIMIMaxSMB` | 1.0 U | plafond SMB standard | ↓ à 0.7–0.8 si hypos après SMB | ↑ jusqu'à 1.2 si post-prandiales hautes | combinez avec facteurs repas |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `OApsAIMIHighBGMaxSMB` | 1.0 U | plafond SMB lorsque AIMI détecte un haut plateau | idem | ↑ (1.5) pour corriger plus vite un plateau >180 mg/dL | Surveillez NGR |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `autodriveMaxBasal` | 1.0 U/h | plafond basale autoDrive | ↓ si hypos nocturnes | ↑ (×1.2) si plateau hyper en autoDrive | Couplé à anti-stall |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】|
| `meal_modes_MaxBasal` | 1.0 U/h | plafond basale durant modes repas | idem | ↑ (×1.3) si vous tolérez plus en repas longs | Laisser > basale profil |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L113-L115】|

**Astuce :** Les plafonds SMB/basal sont appliqués après toutes les sécurités (`applyMaxLimits`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L308】

### Intervalles SMB / modes
Les préférences `OApsAIMIHighBGinterval`, `OApsAIMImealinterval`, etc., définissent la fréquence minimale (par 5 min) à laquelle AIMI peut reproposer un SMB en mode correspondant (par défaut 3 × 5 min = 15 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- **Hypos :** augmentez l’intervalle (4–5) pour espacer les SMB.
- **Hypers prolongées :** réduisez à 2 (10 min) pour HighBG seulement.

### AIMIAdaptiveBasal (plateaux, micro-reprises)
- **Seuil haut** `OApsAIMIHighBg` = 180 mg/dL : déclenche les kicks lorsqu’un plateau haut est identifié.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L62-L112】
- **Bande plateau** `OApsAIMIPlateauBandAbs` = ±2.5 mg/dL/5 min : plus la bande est large, plus AIMI tolère des variations avant de kick-er.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Multiplicateur max** `OApsAIMIMaxMultiplier` = ×1.6 : limite la basale temporaire en plateau.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Kicker step/min** (`OApsAIMIKickerStep`, `OApsAIMIKickerMinUph`, `OApsAIMIKickerStartMin`, `OApsAIMIKickerMaxMin`) contrôlent l’intensité et la durée du kicker.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L138-L140】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L93-L98】
- **Micro-reprise** (`OApsAIMIZeroResumeMin`, `OApsAIMIZeroResumeFrac`, `OApsAIMIZeroResumeMax`) : relance une basale faible après un arrêt ≥10 min pour éviter les remontées post-hypo.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L141-L142】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L96-L97】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
- **Anti-stall** `OApsAIMIAntiStallBias` (10 %) et `OApsAIMIDeltaPosRelease` (Δ+1 mg/dL) définissent l’overdrive minimal en plateau collant.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L142-L143】

**Décision tree pratique :**
```
Si plateau >180 mg/dL et Δ≈0 → augmenter `OApsAIMIKickerStep` (+0,05) pour corriger plus vite.
Si hypos après reprise basale → réduire `OApsAIMIZeroResumeFrac` (0,2) ou augmenter `ZeroResumeMin` (15 min).
Si montée lente malgré kicks → augmenter `OApsAIMIMaxMultiplier` (1,8 max) et vérifier `KickerMinUph`.
```

### Sécurité hypoglycémie
AIMI applique un garde-fou qui bloque SMB si la glycémie se rapproche du seuil hypo avec pente négative, en tenant compte d'une marge supplémentaire selon la vitesse de chute.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L400-L413】

---

## 🧠 Intelligence adaptative (ISF, PeakTime, PK/PD)

### PK/PD dynamique
- **Activation** : `OApsAIMIPkpdEnabled` (OFF par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **Paramètres initiaux** (`OApsAIMIPkpdInitialDiaH`, `OApsAIMIPkpdInitialPeakMin`) définissent le DIA (20 h) et pic (40 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L80】
- **Bornes & vitesse** (`OApsAIMIPkpdBoundsDia*`, `OApsAIMIPkpdBoundsPeak*`, `OApsAIMIPkpdMax*`) limitent l’apprentissage quotidien.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L78】
- **État persistant** (`OApsAIMIPkpdStateDiaH`, `OApsAIMIPkpdStatePeakMin`) mémorise le dernier DIA/pic appris.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L79-L80】
- **Effet :** lorsque activé, AIMI fusionne l’ISF profil/TDD avec l’estimation PK/PD et applique un *pkpdScale* lié à la fraction de queue d’IOB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L82】
- **Priorité repas :** lorsque les modes repas/COB actifs annoncent une montée, le *pkpdScale* est relevé (planche 0.9 → plafond 1.5) et les gardes SMB sont assouplies pour conserver de petits bolus rapprochés tant que la prédiction reste au-dessus de la cible.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L1-L86】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L1180-L1360】
- **Ajustements :**
  - **Hypos tardives** : réduisez `OApsAIMIPkpdMaxDiaChangePerDayH` pour freiner l’allongement de DIA.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L77-L78】
  - **Hypers post-repas** : baissez `OApsAIMIPkpdBoundsPeakMinMax` (ex. 180) pour favoriser des pics plus courts.
  - **Données instables** : désactivez temporairement `PkpdEnabled` et revenez aux valeurs initiales (reset via préférences).

### Fusion ISF & blending rapide
- **`OApsAIMIIsfFusionMinFactor` / `MaxFactor`** : facteurs min/max appliqués à l’ISF de profil (0.75–2.0 par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **`OApsAIMIIsfFusionMaxChangePerTick`** : variation max ±40 % par tick de 5 min.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **Effet :** la fusion mélange l’ISF TDD/PkPd et le Kalman rapide via `IsfBlender`, respectant un lissage ±5 % par boucle.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfBlender.kt†L5-L45】

### Ajustement adaptatif ISF
`IsfAdjustmentEngine` utilise la glycémie Kalman et une EMA du TDD pour recalculer l’ISF cible (loi logarithmique) tout en limitant le changement à ±5 % par boucle et ±20 % par heure.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L6-L49】
- **Hypos** : réduisez `AimiUamConfidence` ou désactivez PK/PD si l’ISF chutait trop vite.
- **Hypers** : vérifiez que `OApsAIMIIsfFusionMaxFactor` reste ≥1.6.

### SMB damping intelligent
Les paramètres `OApsAIMISmbTailThreshold`, `OApsAIMISmbTailDamping`, `OApsAIMISmbExerciseDamping`, `OApsAIMISmbLateFatDamping` contrôlent la réduction des SMB en fin d’action, après exercice ou repas gras.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L84-L87】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/SmbDamping.kt†L4-L77】
- **Conseil :**
  - Si vous restez haut en fin d’action → augmentez `SmbTailThreshold` (0.35) ou relevez `SmbTailDamping` (0.6).
  - Si hypos après sport → réduisez `SmbExerciseDamping` (0.4) pour couper plus fort.

### PeakTime dynamique
Le calcul `calculateDynamicPeakTime` combine IOB, activité future, pas, FC, et capteur pour ajuster le temps de pic entre 35 et 120 min.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2533-L2645】
- **Hypos nocturnes** : si le pic est trop court, augmentez `OApsAIMIcombinedDelta` (1.5) pour rendre AIMI plus prudent dans l’autoDrive.
- **Hypers post-prandiales** : assurez-vous que les pas/FC sont bien synchronisés pour autoriser un pic raccourci lorsque vous êtes actif.

---

## 💡 Modes & détection repas
AIMI module ses SMB selon vos modes temporels et vos facteurs dédiés.

### Facteurs journaliers
`OApsAIMIMorningFactor`, `OApsAIMIAfternoonFactor`, `OApsAIMIEveningFactor` (défaut 50 %) pondèrent le SMB prédit selon la tranche horaire.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L88-L101】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L245】
- **Hypos matinales** : réduisez le MorningFactor (40 %).
- **Hypers soirée** : augmentez EveningFactor (60–70 %).

### Modes repas spécifiques
Chaque mode dispose d’un trio *(prébolus1, prébolus2, facteur %)* et d’un intervalle :
- **Petit déjeuner** : `OApsAIMIBFPrebolus` (2.5 U), `OApsAIMIBFPrebolus2` (2.0 U), `OApsAIMIBFFactor` (50 %), intervalle 15 min.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L95-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】
- **Déjeuner / Dîner** : paramètres analogues (`Lunch*`, `Dinner*`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L98-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L76-L79】
- **Snack / HighCarb / Meal génériques** : `OApsAIMISnackPrebolus`, `OApsAIMIHighCarbPrebolus`, etc.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L120-L123】
- **Hyper mode** : `OApsAIMIHyperFactor` (60 %) renforce les SMB si BG>180.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L100-L103】

**Astuces :**
- Utilisez `OApsAIMImealinterval` (15 min par défaut) pour éviter les SMB trop rapprochés pendant un repas prolongé.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- `OApsAIMIMealFactor` pèse le SMB même sans mode explicite (utile pour repas surprises).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L91-L101】

### AutoDrive prébolus
`OApsAIMIautodrivePrebolus` (1 U) et `OApsAIMIautodrivesmallPrebolus` (0.1 U) servent de limites pour des micro-prébolus automatiques lorsque `autoDrive` est actif.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L107】

### Gestion notes & détection repas
AIMI scanne vos notes (sleep, sport, meal…) pour activer les modes si vous oubliez de cliquer sur le bouton, et les enregistre dans les logs SMB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2656-L2678】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L311-L360】

---

## 💪 Exercice & règles de sécurité

### Toggles physiologiques
- **`OApsAIMIpregnancy`**, **`OApsAIMIhoneymoon`** : activent des ajustements spécifiques dans `BasalDecisionEngine` (par ex. augmenter la basale si delta>0 pendant la grossesse).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L53-L63】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L461-L463】
- **`OApsAIMIforcelimits`** : forcer les plafonds basale/SMB (utilisé par certains profils). Laissez OFF sauf recommandation clinique.

### Détection sport & sécurité SMB
- Les règles `isSportSafetyCondition` coupent les SMB lorsque pas/FC indiquent une activité intense, ou lorsque la cible est élevée (>140).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
- `applySpecificAdjustments` réduit de moitié les SMB si vous êtes en sommeil/snack/basse activité prolongée.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L353-L360】

### Décision tree sécurité
```
Si hypos après sport → activer `OApsAIMIEnableStepsFromWatch` + réduire `SmbExerciseDamping`.
Si hypos grossesse → réduire `OApsAIMIMaxMultiplier` et vérifier `pregnancy` activé.
Si hypers en lune de miel → activer `OApsAIMIhoneymoon` pour autoriser plus d'agressivité.
```

---

## 🌙 Mode nuit & croissance nocturne

### Mode nuit classique
- **Toggle** `OApsAIMInight` (OFF par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L127-L129】
- **Facteur sommeil** `OApsAIMIsleepFactor` (60 %) et intervalle `OApsAIMISleepinterval` (15 min) modèrent les SMB durant la nuit.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L102-L103】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】

### Night Growth Resistance (NGR)
Ce module gère les pics d'hormone de croissance chez l'enfant/adolescent.
- **Activation** : auto pour <18 ans ou via `OApsAIMINightGrowthEnabled` (ON par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L133-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】
- **Paramètres clés** :
  - `OApsAIMINightGrowthAgeYears` (14 ans), fenêtres `OApsAIMINightGrowthStart`/`End` (22:00–06:00).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L87-L90】【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L61】
  - `OApsAIMINightGrowthMaxIobExtra` = marge d'IOB autorisée par tranche de 30 min lorsque l'épisode est actif.【F:plugins/aps/src/main/res/values/strings.xml†L543-L544】
- **Fonctionnement** : les seuils de pente/durée, les multiplicateurs SMB/basal et la phase de décroissance sont désormais apprises automatiquement à partir de l'autosens, de la DIA, de la stabilité CGM et du profil basale.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L420-L471】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/NightGrowthResistanceLearner.kt†L1-L59】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/NightGrowthResistanceMonitor.kt†L13-L215】

**Conseils :**
- Ajustez uniquement la fenêtre horaire et l'IOB supplémentaire si la croissance déborde encore les plafonds.
- Pour les plus jeunes, réduisez la tranche horaire si l'épisode commence plus tôt/laissez le learner décider des intensités.

---

## ❤️ Intégration fréquence cardiaque & pas (Wear OS)
- **Activation** : `OApsAIMIEnableStepsFromWatch` (OFF par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L129】
- **Effets** :
  - Les pas sur 5–180 min (`recentSteps*`) et la FC moyenne 5/60/180 min sont utilisés pour ajuster le temps de pic, moduler SMB (sport) et décider des reprises basales.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L848-L911】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2539-L2645】
  - En cas d'activité intense (>1000 pas et FC>110), AIMI allonge le pic (×1.2) et limite SMB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2616-L2626】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
  - Au repos (pas<200, FC<50), le pic est raccourci (×0.75) pour éviter les retards d'action.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2618-L2626】

**Astuces :**
- Vérifiez que la montre transmet bien toutes les 5 min (sinon les valeurs resteront nulles et AIMI n'ajustera pas).
- En cas d'hypos à l'effort, réduisez `SmbExerciseDamping` ou désactivez temporairement l'option.

---

## ♀️ WCycle – suivi du cycle menstruel
AIMI peut adapter basales et SMB selon votre phase menstruelle.

### Activation & mode
- **`OApsAIMIwcycle`** : active le module (OFF par défaut).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L134】
- **Modes de suivi** : `OApsAIMIWCycleTrackingMode` (`FIXED_28`, `CALENDAR_VARIABLE`, etc.).【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】
- **Paramètres physiologiques** : contraceptif, statut thyroïde, Verneuil influencent l'amplitude des multiplicateurs.【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L1-L39】
- **Clamp min/max** (`OApsAIMIWCycleClampMin` 0.8, `ClampMax` 1.25) bornent l'échelle appliquée.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- **Options shadow/confirm** :
  - `OApsAIMIWCycleShadow` garde les calculs sans les appliquer (mode observation).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】
  - `OApsAIMIWCycleRequireConfirm` demande une confirmation avant d'appliquer un changement.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】

### Fonctionnement
- `ensureWCycleInfo()` interroge `WCycleFacade` avec vos préférences et renvoie la phase, les multiplicateurs et un texte `reason` injecté dans les logs.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2517】
- `updateWCycleLearner` ajuste les multipliers appris tout en respectant `ClampMin/Max`.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2521-L2531】
- Les valeurs de base suivent `WCycleDefaults` (ex. +12 % basal en phase lutéale).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L18-L38】

**Conseils :**
- Définissez la durée moyenne (`OApsAIMIWCycleAvgLength`, 28 j) et le jour de début (`OApsAIMIwcycledateday`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L86-L87】【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- En cas de contraception hormonale, l’amplitude est automatiquement réduite (×0.4–0.5).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L23-L30】

---

## Conseils d'ajustement rapide
| Situation | Ajustement suggéré | Préférence liée |
|-----------|-------------------|-----------------|
| Hypos post-SMB | ↓ `OApsAIMIMaxSMB`, ↑ `OApsAIMISmbTailDamping` | SMB & PK/PD |
| Hypos nocturnes | ↑ `OApsAIMIZeroResumeMin`, ↓ `NightGrowthBasalMultiplier` | Basal & Night |
| Hypers post-repas | ↑ facteurs repas (60–70 %), ↓ `OApsAIMIPkpdBoundsPeakMinMax` | Modes & PK/PD |
| Hyper plateau plat | ↑ `OApsAIMIKickerStep`, vérifier `HighBGMaxSMB` | Adaptive Basal |
| Variabilité forte | Stabiliser poids/TDD, désactiver `PkpdEnabled`, activer `Shadow` WCycle | Général & WCycle |

### Mini decision tree quotidien
```
Si vous restez >180 mg/dL malgré SMB → vérifier HighBG mode : augmenter `HighBGMaxSMB` et `HyperFactor`.
Si descente trop rapide après autoDrive → diminuer `autodrivePrebolus` et augmenter `AutodriveDeviation` (1.5).
Si tendance haute pendant activité → activer suivi pas/FC et réduire `SmbExerciseDamping` pour conserver un peu de SMB.
```

---

## Dépannage et interprétation des logs
1. **Lire `rT.reason`** : chaque boucle concatène les motifs (`plateau kicker`, `WCycle`, `NGR`). Cherchez les phrases `AIMI+` pour voir les actions adaptatives.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】
2. **CSV AIMI** : `_records.csv` contient toutes les variables (pas, TDD, ISF). Utile pour vérifier si vos modes ou pas sont bien pris en compte.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】
3. **PK/PD ne s'actualise plus** : vérifiez que `PkpdEnabled` est ON et que vous n'êtes pas en exercice (flag coupe l’apprentissage).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L38】
4. **Retour aux défauts** : chaque clé peut être réinitialisée depuis le menu (valeurs par défaut listées plus haut). Si vous voulez un reset complet, désactivez `PkpdEnabled`, supprimez les fichiers `oapsaimi*_records.csv`, puis réactivez.
5. **Aucun SMB** : vérifiez les sécurités `isCriticalSafetyCondition` (BG<target, delta négatif, etc.) et les plafonds `maxIob`/`maxSMB`.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L339】

---

## Récapitulatif pédagogique
AIMI est un co-pilote adaptatif :
- Il observe vos glycémies, vos efforts et vos modes pour ajuster l’ISF, le temps de pic et les SMB.
- Ses garde-fous (plateau kicker, NGR, damping SMB, sécurité sport) évitent les extrêmes tout en laissant l’apprentissage évoluer.
- Laisser AIMI accumuler des données cohérentes (profil à jour, annonces repas, étapes/pulsations fiables) maximise ses performances. Chaque paramètre est ajustable pour refléter votre réalité, mais changez un seul réglage à la fois pour en lire l’impact dans les logs.

Continuez à collaborer avec AIMI : plus vous fournissez des données stables, plus il affine ses prédictions et maintient votre glycémie dans la cible.
