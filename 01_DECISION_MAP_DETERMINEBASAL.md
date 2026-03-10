# 🗺️ Decision Map: DetermineBasalAIMI2

Ce document cartographie le flux décisionnel complet du cerveau d'AIMI (`DetermineBasalAIMI2.kt` et `SmbInstructionExecutor.kt`).

---

## 🔄 Flux Principal (Main Loop)

### 1. 📥 Collecte des Entrées (`DetermineBasalAIMI2.kt`)
*   **Trigger**: Réception d'un nouveau statut Glucose (APS).
*   **Inputs**:
    *   `GlucoseStatus` (BG, Delta, AvgDelta)
    *   `Profile` (Basal, ISF, CR, Targets)
    *   `IOB/COB` (Calculés via plugin `iob`)
    *   `Physio` (Status Health Connect via adapter)
    *   `MealData` (Entrées carbs manuelles)

### 2. 🧠 Modulation Contextuelle (Pre-Learning)
Avant toute décision d'insuline, les paramètres du profil sont modulés :
*   **Autosens**: Ratio appliqué sur ISF/Basal.
*   **PhysioAdapter**: Modulation dynamique (`autosensRatio`) basée sur Sommeil/Stress/Activité.
    *   *Exemple*: Stress → Ratio 1.2 (plus agressif).
*   **UnifiedReactivityLearner**: Calcul du `globalReactivityFactor` (ex: 0.07 pour 7% de réactivité).
    *   *Impact*: Multiplie TOUS les facteurs SMB (Meal & Auto).

### 3. 🛡️ Safety Gates (Gardes-Fous Initiaux)
*   **Limits Check**: Vérification `maxBolus`, `maxBasal`.
*   **Target Check**: Si BG < Target, le système passe en mode "Low Glucose Suspend" (LGS) potentiel.

### 4. ⚙️ Moteur de Décision (`SmbDampingUsecase` / `SmbInstructionExecutor`)

#### A. Calcul de Base (MPC/PI)
Une boucle d'optimisation (Cost Function) détermine la dose idéale théorique (`optimalDose`) pour ramener BG à la cible.
*   *Mix*: Mélange pondéré entre MPC (Model Predictive Control) et PI (Proportional-Integral) selon le `deltaScore`.

#### B. Gestion des Modes Repas (Branching)
C'est ici que la logique diverge fortement pour les modes explicites (Lunch, Dinner, etc.).

| Condition | Logique Appliquée | Facteur de Modul. |
|-----------|-------------------|-------------------|
| **HighCarb** | `Base * HighCarbFactor` | × Reactivity |
| **Meal** | `Base * MealFactor` | × Reactivity |
| **Lunch** | `Base * LunchFactor` | × Reactivity |
| **Dinner** | `Base * DinnerFactor` | × Reactivity |
| **Snack** | `Base * SnackFactor` | × Reactivity |
| **Sleep** | `Base * SleepFactor` | × Reactivity |
| **Auto (Default)** | `Base * GlobalFactor` | × Reactivity |

> **Note Critique**: Depuis le fix "Reactivity", le `globalReactivityFactor` est appliqué PARTOUT. Avant, les modes repas l'ignoraient potentiellement.

#### C. Amortissement (Damping)
Le `smbDecision` brut passe dans `SmbDampingUsecase`:
1.  **Tail Damping**: Si on est dans la queue d'action de l'insuline (> 3h), on réduit le SMB.
2.  **Activity Damping**: Si activité insuline élevée, réduction pour éviter stacking.
3.  **Late Fat Rise**: Si détection de gras (via historique), boost autorisé.

### 5. 🛑 Validation Finale & Caps

#### A. MaxSMB Cap
`finalSmb = min(smbDecision, maxSmb)`
*   C'est le filet de sécurité ultime. Défini dans les préférences par l'utilisateur.

#### B. Hypo Protection
*   **PredBGs**: Vérification des courbes prédictives (Zero Temp, IOB, COB).
*   **Règle**: Si une courbe touche le seuil hypo (ex: 70mg/dL) dans l'horizon, le SMB est annulé ou drastiquement réduit.

#### C. HighBgOverride
*   Si BG très élevé (> seuil trigger) et montée franche, un override peut forcer un SMB plus agressif (bypass partiel du damping), mais toujours sous `maxSmb`.

### 6. 📤 Sortie (Action)
Le résultat (APSResult) contient:
*   `SMB`: Quantité à délivrer immédiatement (0.0 si unsafe).
*   `TBR`: Temporary Basal Rate (souvent 0 si SMB délivré, ou ajusté pour la sécurité).
*   `Reason`: Log textuel expliquant la décision (visible dans l'onglet "OpenAPS AIMI").

---

## ⚠️ Zones de Risque Identifiées

1.  **Conflit Modes vs Auto**: Si un utilisateur active "Lunch Mode" alors qu'il est déjà en hypo légère, le facteur multiplicateur s'applique quand même sur la base. C'est la *Cost Function* qui doit d'abord dire "Dose = 0" pour que le mode ne multiplie rien. Si la Cost Function est trompée (ex: erreur calibration capteur), le mode repas amplifie l'erreur.
2.  **Dépendance Reactivity**: Si le learner apprend une valeur folle (ex: 5.0 au lieu de 0.5) suite à une période de résistance temporaire, le système devient hyper-agressif partout. *Safety Gate*: Bornes hardcodées dans le learner (0.1 - 1.5 généralement).
3.  **Override Manuel**: Les boutons "Small/Medium/Large" dans l'UI injectent des Carbs + Mode temporaire. Ils dépendent entièrement de la justesse du profil (IC/ISF).

