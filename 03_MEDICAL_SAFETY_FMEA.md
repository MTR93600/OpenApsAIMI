# 🏥 Medical Safety Analysis (FMEA)

**Failure Mode and Effects Analysis** pour le système OpenAPS AIMI v3.4.

| ID | Hazard (Danger) | Cause Logicielle Possible | Gravité (1-5) | Probabilité (1-5) | Détectabilité (1-5) | RPN | Mitigation Existante | Mitigation Manquante / Recommandée |
|----|---|---|---|---|---|---|---|---|
| **H1** | **Hypoglycémie Sévère (<54)** | Sur-correction SMB due à un "Reactivity Factor" excessif (ex: 2.0x) après une période de résistance, suivi d'un retour à la normale (changement cathéter). | 5 | 3 | 3 | **45** | `maxSmb` cap, `isBelowHypo` check sur prédictions. | **Clamp dynamique** : Limiter la variation du learner à +/- 20% par 24h. Réinitialiser learner sur détection "New Cannula". |
| **H2** | **Insulin Stacking (Invisible)** | Envoi de multiples SMBs rapprochés car `IOB` est sous-estimé (DIA trop court ou courbe profil erronée). | 4 | 4 | 4 | **64** | `SmbDamping` sur activité insuline élevée. Intervalle min SMB. | **Integrator Check** : Refuser tout SMB > 0.5U si `ActiveInsulin > 2 * BasalRate` (règle empirique de sécurité). |
| **H3** | **Meal Mode Override** | Utilisateur active "Lunch" alors que BG descend déjà. Le facteur "Lunch" force un SMB agressif. | 4 | 3 | 2 | **24** | La `CostFunction` devrait voir le BG descendant et proposer 0. | Vérifier que `SmbInstructionExecutor` ne bypass pas la `CostFunction` si `predictedBG < target`. Le facteur ne doit s'appliquer que si `base > 0`. |
| **H4** | **Hyperglycémie Prolongée (Panne Silencieuse)** | Driver Dash déconnecté (Android 14 BT issue) mais UI ne le montre pas clairement ou Loop continue de "penser" qu'elle injecte. | 3 | 4 | 2 | **24** | Gestion d'erreurs driver, "Zombie pod" checks. | **Heartbeat Watchdog** : Si aucune confirmation de bolus réussie depuis 60min → Alerte Sonore Prioritaire. |
| **H5** | **Hallucination LLM (Advisor)** | Le module Advisor/Auditor propose un paramétrage dangereux (ex: ISF trop bas) et l'utilisateur l'applique aveuglément. | 3 | 2 | 1 | **6** | Disclaimer "Conseil seulement". Prompt safety instructions. | **Safety Parser** : Le code doit refuser de parser une réponse LLM contenant des valeurs hors bornes (ex: ISF < 20). |
| **H6** | **Physio Panic** | Données Health Connect corrompues (HR=0 ou HR=200) interprétées comme "Stress Extrême" → Augmentation massive agressivité. | 3 | 2 | 3 | **18** | Validation `isValid()` (supposée). | **Sanity Check** : Ignorer HR < 30 ou > 220. Ignorer sommeil > 16h. Fallback "Neutral" si données suspectes. |

---

## 🛡️ Invariants de Sécurité (Must-Not-Fail Rules)

Le code **DOIT** garantir ces règles en toutes circonstances (Hard Code) :

1.  **Hypo-Guard Ultime** : `IF (PredictedBG_Min < Threshold_Suspend) THEN SMB = 0`.
    *   *Status Code*: ✅ Implémenté dans `Hooks.applySafety` et `HighBgOverride`.
2.  **Hard Cap SMB** : `SMB <= Preferences.MaxSMB`.
    *   *Status Code*: ✅ Implémenté (ligne 271 SmbInstructionExecutor).
3.  **IOB Limit** : `SMB = 0 IF (Total_IOB > Preferences.MaxIOB)`.
    *   *Status Code*: ✅ Vérifié via `input.iob < maxIob`. (Attention: les meal modes ne doivent pas bypasser ça).
4.  **Intervalle Min** : `TimeSinceLastBolus > MinInterval`.
    *   *Status Code*: 🟠 Partiel. Certains chemins (Advisor auto-actions) pourraient théoriquement forcer un SMB. À vérifier dans l'intégration Advisor.

---

## 🚦 Conclusion Sécurité
Le système est robuste pour une utilisation standard, mais les fonctionnalités avancées (Learners, Meal Modes, Advisor) introduisent des vecteurs de risque "intelligents" (le système se croit plus malin que la sécurité de base).

**Priorité Absolue** : Appliquer un "Safety Layer" final qui agit APRES tous les learners et modes, juste avant l'envoi pompe. Ce layer doit être "bête et méchant" (règles physiques simples).
