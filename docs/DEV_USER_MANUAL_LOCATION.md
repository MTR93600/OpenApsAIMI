# 📚 Manuel Utilisateur AIMI - Localisation dans le Code

## 🎯 Vue d'ensemble

Le Manuel Utilisateur d'OpenAPS AIMI est intégré directement dans l'application Android et accessible via l'interface utilisateur.

---

## 📍 **Localisation des Fichiers**

### 1. **Ressources Textuelles (Contenu du Manuel)**

| Langue | Fichier | Chemin Complet |
|--------|---------|----------------|
| 🇫🇷 **Français** | `manual_strings.xml` | `/plugins/main/src/main/res/values-fr-rFR/manual_strings.xml` |
| 🇬🇧 **Anglais** | `manual_strings.xml` | `/plugins/main/src/main/res/values/manual_strings.xml` |

### 2. **Code UI (Affichage)**

| Composant | Fichier | Chemin |
|-----------|---------|--------|
| **Activity** | `UserManualActivity.kt` | `/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/manual/UserManualActivity.kt` |
| **Layout XML** | `activity_user_manual.xml` | `/plugins/main/src/main/res/layout/activity_user_manual.xml` |

### 3. **Point d'Accès (Dashboard)**

| Composant | Fichier | Ligne |
|-----------|---------|-------|
| **Navigation** | `DashboardFragment.kt` | Ligne 399 |

---

## 📋 **Structure du Manuel (14 Sections)**

Le manuel est organisé en **14 sections thématiques** :

### **Sections du Manuel**

1. **🚀 Démarrage Rapide** (`manual_section_1`)
   - Installation et configuration initiale
   - Premier lancement et vérifications
   - Activation de l'algorithme AIMI

2. **🧠 Unified Reactivity Learner** (`manual_section_2`)
   - Système d'apprentissage automatique
   - Ajustement dynamique du facteur de réactivité
   - Analyse quotidienne des glycémies
   - Protection automatique contre les hypos

3. **📸 AIMI Meal Advisor** (`manual_section_3`)
   - Assistant photo de repas avec vision IA
   - Modèles supportés (GPT-4o, Gemini 2.5, DeepSeek, Claude)
   - Configuration des clés API
   - Estimation automatique des glucides et FPU

4. **🤖 AIMI Advisor** (`manual_section_4`)
   - Conseiller profil alimenté par IA
   - Analyse des 7-14 derniers jours
   - Recommandations d'ajustements détaillées
   - Validation de sécurité par l'Auditeur

5. **🎯 AIMI Context** (`manual_section_5`)
   - Déclaration des contextes (exercice, stress, maladie, alcool)
   - Impact sur le dosage d'insuline
   - Langage naturel vs boutons prédéfinis
   - Gestion des intentions actives

6. **🍽️ Modes Repas & Création des Boutons** (`manual_section_6`)
   - 8 modes repas spécialisés (bfast, lunch, dinner, highcarb, snack, meal, sport, stop, sleep)
   - Configuration via Automation + Careportal
   - Personnalisation des prébolus et facteurs

7. **🛡️ AIMI Auditor** (`manual_section_7`)
   - Système de sécurité en temps réel
   - Vérifications avant CHAQUE décision d'insuline
   - Types de verdict (Approuvé / Réduit / Rejeté)
   - Protection contre les empilements dangereux

8. **🎯 AIMI Trajectory** (`manual_section_8`)
   - Prédiction des glycémies futures (30-180 min)
   - Intégration IOB, COB, tendances, contexte
   - Ajustement proactif des décisions
   - Algorithmes PKPD et Kalman

9. **🧬 PKPD (Modèle Pharmacocinétique/Pharmacodynamique)** (`manual_section_9`)
   - Modélisation avancée de l'insuline
   - Absorption dynamique selon type d'insuline, site, température
   - Saturation des récepteurs
   - Prédictions 3x plus précises

10. **🔧 Réglages Essentiels** (`manual_section_10`)
    - Paramètres critiques (Max IOB, Max SMB, Basal)
    - Configuration de sécurité
    - Apprentissage et autoDrive

11. **🌙 Fonctionnalités Avancées** (`manual_section_11`)
    - WCycle (cycle menstruel)
    - Night Growth Resistance
    - Wear OS (pas/fréquence cardiaque)
    - ISF Dynamique
    - **Trajectory Guard** 🌀

12. **💡 Conseils d'Optimisation** (`manual_section_12`)
    - Solutions pour hypos fréquentes
    - Solutions pour hypers post-repas
    - Gestion de la variabilité
    - Recommandations générales

13. **🔧 Dépannage** (`manual_section_13`)
    - Aucun SMB délivré
    - Prébolus mode repas non envoyé
    - Erreurs API (Meal Advisor / AIMI Advisor)
    - Support et logs

14. **📊 Profils Utilisateurs Recommandés** (`manual_section_14`)
    - Profil conservateur (sujet aux hypos)
    - Profil équilibré (standard)
    - Profil agressif (contrôle serré)

---

## 💻 **Code Principal**

### **UserManualActivity.kt**

```kotlin
package app.aaps.plugins.main.general.manual

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.aaps.plugins.main.R

class UserManualActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_manual)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Setup des 12 sections (section 13 et 14 manquent dans le code actuel !)
        setupSection(R.id.section1, R.string.manual_section_1_title, R.string.manual_section_1_content)
        setupSection(R.id.section2, R.string.manual_section_2_title, R.string.manual_section_2_content)
        setupSection(R.id.section3, R.string.manual_section_3_title, R.string.manual_section_3_content)
        setupSection(R.id.section4, R.string.manual_section_4_title, R.string.manual_section_4_content)
        setupSection(R.id.section5, R.string.manual_section_5_title, R.string.manual_section_5_content)
        setupSection(R.id.section6, R.string.manual_section_6_title, R.string.manual_section_6_content)
        setupSection(R.id.section7, R.string.manual_section_7_title, R.string.manual_section_7_content)
        setupSection(R.id.section8, R.string.manual_section_8_title, R.string.manual_section_8_content)
        setupSection(R.id.section9, R.string.manual_section_9_title, R.string.manual_section_9_content)
        setupSection(R.id.section10, R.string.manual_section_10_title, R.string.manual_section_10_content)
        setupSection(R.id.section11, R.string.manual_section_11_title, R.string.manual_section_11_content)
        setupSection(R.id.section12, R.string.manual_section_12_title, R.string.manual_section_12_content)
        // ⚠️ Sections 13 et 14 sont dans les ressources mais PAS affichées !
    }

    private fun setupSection(viewId: Int, titleRes: Int, contentRes: Int) {
        val sectionView = findViewById<android.view.View>(viewId) ?: return
        sectionView.findViewById<TextView>(R.id.section_title)?.setText(titleRes)
        sectionView.findViewById<TextView>(R.id.section_content)?.setText(contentRes)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
```

### **Point d'Accès (DashboardFragment.kt ligne 399)**

```kotlin
private fun openHistory(): Boolean {
    startActivity(Intent(requireContext(), app.aaps.plugins.main.general.manual.UserManualActivity::class.java))
    return true
}
```

**Navigation** : Le manuel est ouvert en cliquant sur le bouton "Historique" (`R.id.dashboard_nav_history`) dans la navigation bottom du dashboard.

---

## 📱 **Affichage dans l'UI**

D'après les captures d'écran de l'utilisateur :

1. **Accès** : Bouton en bas de l'écran principal
2. **Icône** : ℹ️ Information circle
3. **Titre** : "Manuel Utilisateur - OpenAPS AIMI"
4. **Format** : Scrollable avec sections numérotées et emojis
5. **Style** : Fond sombre, texte blanc, sections collapsibles

---

## ⚠️ **Incohérence Détectée**

**Problème** : Les sections 13 et 14 sont définies dans `manual_strings.xml` mais **ne sont PAS affichées** dans `UserManualActivity.kt` !

### **Solution Proposée**

Ajouter dans `UserManualActivity.kt` après la ligne 29 :

```kotlin
setupSection(R.id.section13, R.string.manual_section_13_title, R.string.manual_section_13_content)
setupSection(R.id.section14, R.string.manual_section_14_title, R.string.manual_section_14_content)
```

**ET** ajouter les éléments correspondants dans le layout `activity_user_manual.xml`.

---

## 🎨 **Format des Ressources Strings**

### **Structure XML**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Titre principal -->
    <string name="manual_title">Manuel Utilisateur – OpenAPS AIMI</string>
    
    <!-- Introduction -->
    <string name="manual_intro">Bienvenue dans AIMI...</string>
    
    <!-- Section 1 -->
    <string name="manual_section_1_title">1. 🚀 Démarrage Rapide</string>
    <string name="manual_section_1_content">**Installation :**\n1. Allez dans...</string>
    
    <!-- ... 13 autres sections ... -->
</resources>
```

### **Formatage Markdown**

Le contenu utilise du **Markdown simplifié** :
- `**Texte en gras**` → **Texte en gras**
- `• Liste item` → • Liste item
- `\n` → Nouvelle ligne
- Emojis Unicode directs (🚀, 🧠, 📸, etc.)

---

## 🔄 **Mise à Jour du Manuel**

### **Pour ajouter/modifier une section** :

1. **Éditer le fichier XML** :
   - `/plugins/main/src/main/res/values-fr-rFR/manual_strings.xml` (français)
   - `/plugins/main/src/main/res/values/manual_strings.xml` (anglais)

2. **Ajouter la string resource** :
   ```xml
   <string name="manual_section_15_title">15. 🆕 Nouvelle Section</string>
   <string name="manual_section_15_content">**Contenu ici...**</string>
   ```

3. **Modifier UserManualActivity.kt** :
   ```kotlin
   setupSection(R.id.section15, R.string.manual_section_15_title, R.string.manual_section_15_content)
   ```

4. **Ajouter l'élément dans le layout XML** :
   Éditer `activity_user_manual.xml` pour ajouter `section15` view.

---

## 📊 **Statistiques**

| Métrique | Valeur |
|----------|--------|
| **Nombre de sections** | 14 (mais seulement 12 affichées) |
| **Taille totale (FR)** | ~26 KB |
| **Langues supportées** | 2 (FR, EN) |
| **Lignes de code Activity** | 46 |
| **Format contenu** | Markdown simplifié + Emojis |

---

## 🎯 **Recommandations**

### ✅ **Points forts**
- Organisation claire en sections thématiques
- Emojis pour faciliter la navigation visuelle
- Contenu exhaustif et pédagogique
- Multilingue (FR/EN)

### ⚠️ **À améliorer**
1. **Ajouter les sections 13 et 14 manquantes** dans l'Activity
2. **Système de recherche** dans le manuel
3. **Liens entre sections** (navigation interne)
4. **Version web** du manuel (export HTML/PDF)
5. **Captures d'écran** intégrées pour illustrer les concepts

---

## 📝 **Exemple de Contenu (Section Trajectory Guard)**

Voici comment le **Trajectory Guard** est documenté dans le manuel (Section 11) :

```
**Trajectory Guard** :
• Détecte patterns dangereux :
  - 🌀 ORBIT : Contrôle stable
  - 📈 DIVERGENT : Perte contrôle (intervention)
  - 📉 CONVERGENT : Amélioration
  - ⚠️ DRIFT : Dégradation lente
```

**Note** : Cette documentation est **minimale** par rapport à la complexité réelle du Trajectory Guard. Vous pourriez créer une **section dédiée complète** (Section 15) avec :
- Explication détaillée des 6 types de trajectoires
- Visualisations ASCII
- Métriques (κ, conv, health)
- Exemples concrets d'affichage dans le rT

---

## 🚀 **Fichiers Clés pour Mise à Jour**

| Action | Fichier à Modifier |
|--------|-------------------|
| Contenu FR | `/plugins/main/src/main/res/values-fr-rFR/manual_strings.xml` |
| Contenu EN | `/plugins/main/src/main/res/values/manual_strings.xml` |
| Affichage | `/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/manual/UserManualActivity.kt` |
| Layout | `/plugins/main/src/main/res/layout/activity_user_manual.xml` |
| Navigation | `/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardFragment.kt` |

---

**Date de Documentation** : 26 janvier 2026  
**Auteur** : Antigravity AI  
**Version AIMI** : Actuelle (avec Trajectory Guard intégré)
