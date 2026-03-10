# 🤰 AIMI Pregnancy: Analyse et Rénovation Innovante

Ce document analyse l'état actuel du support "Grossesse" dans AIMI et propose une refonte complète basée sur la physiologie dynamique de la gestation.

## 1. Audit de l'existant : Une approche binaire obsolète

L'implémentation actuelle dans AIMI (`BooleanKey.OApsAIMIpregnancy`) est rudimentaire :
*   **Logique :** Si `Enceinte` ET `BG > 110` ET `Delta > 0` → Appliquer un boost basal standard.
*   **Problème :** Cette logique traite la grossesse comme une simple "maladie" temporaire (type rhume) ou un switch ON/OFF.
*   **Risque :** Elle ignore totalement l'évolution drastique des besoins. Une femme à 8 SA (sensible aux hypos) recevra le même traitement qu'une femme à 32 SA (résistante massive). **C'est dangereux et insuffisant.**

---

## 2. La Physiologie Réelle : "Le Marathon Métabolique"

La grossesse n'est pas un état stable. C'est une courbe dynamique en 4 phases distinctes :

| Phase | Semaines (SA) | Physiologie | Impact Insulino-Gestion |
| :--- | :--- | :--- | :--- |
| **T1 (Début)** | 0 - 14 SA | Sensibilité accrue, Nausées. | **Risque Hypos Sévères.** Les besoins baissent souvent de 10-20%. |
| **T2 (Milieu)** | 14 - 28 SA | Le placenta grandit, sécrète HPL/Cortisol. | **Résistance Linéaire.** Les besoins augmentent chaque semaine. |
| **T3 (Pic)** | 28 - 36 SA | Résistance massive (+100% à +200%). | **Agressivité Requise.** Il faut frapper fort et tôt. |
| **Terme** | 36 - 40 SA | Vieillissement placentaire. | Légère baisse des besoins (signe d'alerte). |
| **Post-Partum** | Jour J | Expulsion du placenta (usine à hormones). | **CRASH TOTAL.** Retour instantané aux besoins pré-grossesse (voire moins). |

---

## 3. Proposition Innovante : "AIMI Gestational Autopilot"

Ne plus demander "Est-ce que je suis enceinte ?" (Switch), mais "Où en suis-je ?" (Date).

### A. Le Moteur Temporel ("Gestational Clock")
L'utilisatrice rentre une seule donnée : **Date Prévue d'Accouchement (DPA)**.
Le système calcule la *Semaine d'Aménorrhée (SA)* actuelle.

### B. Scalers Dynamiques (Innovant)
Au lieu de modifier le profil manuellement chaque semaine, AIMI applique un **"Gestational Multiplier"** sur le profil de base (supposé être le profil pré-grossesse ou T1).

*Formule conceptuelle :*
```kotlin
val sa = gestationalWeek // ex: 24
val resistanceFactor = when {
    sa < 12 -> 0.9  // T1: Prudence (-10%)
    sa < 20 -> 1.0 + ((sa - 12) * 0.05) // T2: Montée progressive
    sa < 36 -> 1.4 + ((sa - 20) * 0.08) // T3: Montée forte
    else -> 1.3 // Terme: Légère baisse
}
// Application automatique :
// Basal = Profil * resistanceFactor
// ISF = Profil / resistanceFactor (Plus résistant = ISF plus petit)
// CR = Profil / resistanceFactor
```

### C. Le mode "Safety Net" (Fœtus-First)
La grossesse exige des cibles plus strictes (70-140 mg/dL) pour la santé du bébé, mais l'hypoglycémie est redoutée par la mère.
*   **Target :** Forcé à **85-95 mg/dL** (plus bas que le standard 100-110).
*   **Hypo-Guard :** Si la prédiction descend sous 70 dans les 30 min → **Zero Temp immédiat** (Couper tout, plus tôt que d'habitude).

### D. Le "Delivery Button" (Kill Switch)
Un bouton d'urgence "Accouchement / Délivrance" :
*   **Action :** Réinitialise instantanément tous les facteurs à 1.0 (ou 0.8 pour l'allaitement).
*   **Pourquoi :** Évite l'hypoglycémie massive post-partum quand les hormones s'effondrent.

---

## 4. Implémentation proposée

Créer un plugin dédié ou étendre `WCycle` (car c'est hormonal) :
`app.aaps.plugins.aps.openAPSAIMI.physio.gestation`

Fichiers clés :
1.  **GestationalCalculator.kt** : Calcule SA et trimestres.
2.  **PregnancyProfileScaler.kt** : Applique les maths sur le profil.
3.  **PregnancyPreference.kt** : Stocke la DPA.

*Cette approche transformerait AIMI en le premier système Open Source avec un "Pilote Automatique Obstétrique" intégré.*
