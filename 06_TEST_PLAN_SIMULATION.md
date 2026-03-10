# 🧪 Plan de Test & Simulation

Stratégie de validation pour le module AIMI (Focus Safety & Android 14).

## 1. Tests Unitaires (Logique Pure)

### A. DetermineBasalAIMI2 (Safety Gates)
Recréer les scénarios critiques via `DetermineBasalAdapterTest` (Mocks).

| Scénario | Input | Résultat Attendu |
|---|---|---|
| **Hypo Imminente** | BG=80, Delta=-5, IOB=2.0U | SMB = 0.0U, TBR = 0% (LGS) |
| **MaxSMB Cap** | BG=200, MealMode=On, Target=2.0U, MaxSmb=1.0U | SMB = 1.0U (Clamped) |
| **Reactivity High** | Factor=2.0, BG=150, Delta=+5 | SMB doublé vs standard, MAIS <= MaxSMB |

### B. SmbInstructionExecutor (Meal Modes)
Vérifier l'application correcte des facteurs ET du `globalReactivityFactor`.

*   **Test**: `Input(lunchTime=true, factor=100%, reactivity=0.07)`
*   **Assert**: `finalSMB` est ~7% de la dose théorique standard.

---

## 2. Tests d'Intégration (Android Component)

### A. Storage Persistence (Android 14)
*   **Objectif**: Vérifier que les JSON learners survivent au reboot.
*   **Procédure**:
    1.  Lancer App, forcer un apprentissage `UnifiedReactivity`.
    2.  Vérifier présence fichier (via `adb shell ls ...`).
    3.  Force Stop App.
    4.  Relancer App.
    5.  Vérifier logs (`Reloaded reactivity: X%`).

### B. Health Connect Bootstrap
*   **Objectif**: Valider le flux "Permissions -> Fetch -> Store".
*   **Procédure**:
    1.  Install Fresh APK.
    2.  Grant Permissions.
    3.  Attendre 4h (ou trigger manuel).
    4.  Vérifier log `PHYSIO: READY (Sleep=Xh)`.

---

## 3. Scénarios Cliniques (Simulation "In The Loop")

Ces tests nécessitent un simulateur (ex: NSClient Simulator ou Hardware Simulator).

### Scénario 1: Le "Faux Lunch" (Safety Override)
*   **Contexte**: BG 90 mg/dL descendant (-2 mg/dL/5min).
*   **Action**: Utilisateur active "Lunch Mode" (pensant manger, mais oublie Carbs).
*   **Attendu**: Le système NE DOIT PAS envoyer de SMB malgré le facteur Lunch 100%, car `predictedBG` passera sous le seuil de sécurité.
*   **Critère Succès**: SMB = 0.00U.

### Scénario 2: Le "Rebound" (After Hypo)
*   **Contexte**: Sortie d'hypo (65 → 85 mg/dL), Delta +10 mg/dL/5min (sucre rapide).
*   **Action**: Automatique.
*   **Attendu**: Le système observe la montée rapide. Risque de sur-correction (Insulin on Board 0U).
*   **Behavior**: `SmbDamping` doit modérer la réponse car `AvgDelta` est très volatile.

### Scénario 3: Dash Disconnect
*   **Contexte**: Boucle active.
*   **Action**: Éteindre le Pod (simulé) ou éloigner téléphone.
*   **Attendu**:
    1.  Warning "Pump Unreachable" dans logs.
    2.  `DetermineBasal` se met en pause ou "Open Loop" après X minutes d'échecs.
    3.  Pas de "Ghost Bolus" (SMB supposé livré mais inconnu).

## 4. Golden Logs (Signature de Succès)

```text
[SMB] Logic: Proposed=1.5U -> Safety=1.5U -> Damping=1.2U -> Cap(MaxSMB 1.0)=1.0U
[Physio] State: OPTIMAL (Conf 0.85)
[Storage] Write OK /data/user/0/.../physio_context.json
[Advisor] Verdict: CONFIRM (Confidence 0.9)
```
