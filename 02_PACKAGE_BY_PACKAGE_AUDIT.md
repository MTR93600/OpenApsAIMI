# 📦 Audit par Package: AIMI v3.4

Cet audit détaille l'état de santé, le rôle et les risques de chaque package du plugin `openAPSAIMI`.

---

## 🟢 Core & Logic

### `app.aaps.plugins.aps.openAPSAIMI` (Root)
*   **Contenu**: `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`.
*   **Rôle**: Point d'entrée, orchestration, cycle de vie.
*   **Audit**:
    *   `DetermineBasalAIMI2`: Monolithique mais logique séquentielle claire.
    *   **Risque**: Complexité cyclomatique élevée. Difficile à tester unitairement sans mocks lourds.

### `smb` (Super Micro Bolus)
*   **Contenu**: `SmbInstructionExecutor.kt`, `SmbQuantizer.kt`.
*   **Rôle**: Calcul final de la dose SMB.
*   **Audit**:
    *   Logique "Meal Mode" invasive (facteurs directs).
    *   Dépendance forte aux préférences utilisateur (`DoubleKey`).
    *   **Risque**: Les facteurs peuvent s'empiler (Reactivity * MealFactor * Profile ISF).

### `safety`
*   **Contenu**: `HypoTools.kt`, `SafetyGuard`.
*   **Rôle**: Protection contre hypos et sur-dosage.
*   **Audit**:
    *   Les checks `isBelowHypo` sont robustes (utilisent predictedBG et eventualBG).
    *   **Manque**: Pas de protection explicite contre "Stacking massif" indépendante de l'IOB calculé (ex: limite max SMBs par heure).

---

## 🟡 Intelligence & Learning

### `physio`
*   **Contenu**: `AIMIPhysioManagerMTR`, adapters, repository.
*   **Rôle**: Intégration Health Connect (Sommeil/Activité).
*   **Audit**:
    *   **Android 14**: Permissions Health Connect bien gérées via `AIMIHealthConnectPermissions`.
    *   **Logique**: Utilisation de `Outcome` pour éviter les états fantômes (NEVER_SYNCED).
    *   **Risque**: Si Health Connect renvoie des données aberrantes (ex: 0h sommeil car montre non portée), le système peut passer en mode "Recovery" inutilement. Needs data validity checks (ex: HR > 30).

### `advisor` (LLM)
*   **Contenu**: `AuditorAIService`, `AiCoaching`, `AuditorPromptBuilder`.
*   **Rôle**: Analyse IA cloud (GPT/Gemini/Claude) pour audit et conseils.
*   **Audit**:
    *   **Network**: Appels HTTP manuels (`HttpURLConnection`). Rustique mais fonctionnel. Devrait utiliser Retrofit/OkHttp pour meilleure gestion timeouts/retries.
    *   **Prompts**: Bien isolés dans `PromptBuilder`. Format JSON strict.
    *   **Sécurité**: Le verdict "Auditor" est informatif (UI) et ne bloque pas *directement* la boucle (sauf si `implémentation` future le branche sur `SafetyDecision`).

### `learning` (UnifiedReactivity)
*   **Contenu**: `UnifiedReactivityLearner.kt`.
*   **Rôle**: Apprentissage long terme de la sensibilité.
*   **Audit**:
    *   Persistence JSON via `AimiStorageHelper`.
    *   **Risque**: "Runaway learning". Si l'utilisateur a une canule bouchée (insuline inefficace), le learner va augmenter la réactivité massivement. Au changement de canule, risque d'hypo.
    *   **Mitigation**: Bornes clamp (0.1 - X.X) indispensables.

---

## 🟠 Infrastructure & Compatibility

### `pump.omnipod.dash` (Driver)
*   **Contenu**: Driver bluetooth Omnipod Dash.
*   **Audit Android 14**:
    *   Utilisation de `createBond()` explicite.
    *   Dépend de `BLUETOOTH_CONNECT` (Runtime Permission).
    *   **Risque Élevé**: Le changement de stack BT sur Android 14 est connu pour causer des instabilités avec certains appareils médicaux (changements d'adresse MAC aléatoires, timeout GATT stricts). Le warning utilisateur est justifié.

### `utils` (Storage)
*   **Contenu**: `AimiStorageHelper.kt`.
*   **Audit**:
    *   Stratégie fallback implémentée.
    *   **Problème**: Sur Android 14, `/sdcard/Documents` est quasi-inaccessible en écriture pour les nouvelles I/O sans SAF (Storage Access Framework).
    *   **Conséquence**: Les fichiers logs csv/json atterriront probablement dans le stockage privé (`Android/data/...`) et l'utilisateur ne les trouvera pas via son explorateur de fichiers habituel.

---

## 📝 Conclusion Package
L'architecture est saine, mais la couche infrastructure (IO/Bluetooth) est fragilisée par les restrictions Android 14. La logique métier (SMB/Safety) est robuste mais complexe en raison de l'ajout successif de "Modes" et "Learners".

**Recommandation**: Refactoriser `SmbInstructionExecutor` pour séparer clairement "Calcul Dose" et "Application Facteurs".
