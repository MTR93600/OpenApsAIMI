# 📋 Backlog Corrections & Améliorations

Liste priorisée des tâches techniques issues de l'audit complet.

## 🔴 Priorité Haute (Safety & Crash Risk)

1.  **[Android 14] Storage Migration**
    *   *Symptôme*: Logs invisibles pour user, risque denial access.
    *   *Action*: Migrer `AimiStorageHelper` pour utiliser `MediaStore` (si écriture publique requise) ou implémenter un "File Export" explicite depuis le dossier privé de l'app.
    *   *Package*: `utils`

2.  **[Safety] Final SMB Safety Gate**
    *   *Symptôme*: Risque de bypass safety par des modes complexes (Meal/Learner).
    *   *Action*: Ajouter une classe `FinalSafetyCheck` juste avant l'envoi à la pompe.
    *   *Règle*: `if (IOB > 2 * Basal * DIA) SMB = min(SMB, 0.5)`.
    *   *Package*: `smb`

3.  **[Drivers] Bluetooth Dash Sanity**
    *   *Symptôme*: Warning Dash user.
    *   *Action*: Vérifier la gestion des permissions `BLUETOOTH_SCAN`/`CONNECT` et le flow de bonding. Ajouter un bouton "Reset Bluetooth Bond" dans l'UI Dash.
    *   *Package*: `pump.omnipod.dash`

## 🟡 Priorité Moyenne (Robustesse & Logique)

4.  **[Logic] Refactor Meal Modes**
    *   *Symptôme*: Logique `smbToGive` dupliquée et entremêlée dans `SmbInstructionExecutor`.
    *   *Action*: Extraire la logique "Apply Meal Factor" dans une classe dédiée `MealInterventionStrategy` qui retourne un multiplicateur, appliqué proprement sur la décision de base.
    *   *Package*: `smb`

5.  **[Physio] Data Validity Checks**
    *   *Symptôme*: Risque de panic sur données Health Connect corrompues.
    *   *Action*: Dans `AIMIPhysioManager`, rejeter les données hors bornes physiologiques (HR < 30, Sleep > 16h).
    *   *Package*: `physio`

6.  **[LLM] Latence Auditor**
    *   *Symptôme*: Timeout 3 min trop long.
    *   *Action*: Réduire timeout à 45s. Si timeout, logguer "Auditor Skipped" et continuer sans bloquer.
    *   *Package*: `advisor`

## 🟢 Priorité Basse (Maintenance & Confort)

7.  **[UI] Logs Dashboard**
    *   *Symptôme*: Logs console parfois trop verbeux sur petit écran.
    *   *Action*: Créer un mode "Compact Log" pour `DetermineBasalAIMI2`.
    *   *Package*: `ui`

8.  **[Tests] Golden Dataset LLM**
    *   *Symptôme*: Pas de régression testing sur les prompts.
    *   *Action*: Créer un set de JSON input/expected output pour CI.
    *   *Package*: `test`

---
Ce backlog est prêt pour insertion dans Jira/GitHub Issues.
