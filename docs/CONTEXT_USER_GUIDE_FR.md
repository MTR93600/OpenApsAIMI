# 🎯 GUIDE D'UTILISATION - MODULE CONTEXTE AIMI
## Guide Simple et Efficace pour Utilisateurs

**Version** : 1.0  
**Date** : 2026-01-03  
**Niveau** : Utilisateur Final

---

## 📖 **QU'EST-CE QUE LE MODULE CONTEXTE ?**

Le **Module Contexte** permet d'informer AAPS de votre situation actuelle (sport, maladie, stress, etc.) pour que l'algorithme adapte automatiquement la distribution d'insuline.

**En bref** : Vous dites à AAPS ce que vous faites → AAPS ajuste l'insuline en conséquence.

---

## 🚀 **DÉMARRAGE RAPIDE (3 ÉTAPES)**

### **ÉTAPE 1 : Activer le Module**

1. Ouvrez **AAPS**
2. Allez dans **Paramètres** → **OpenAPS AIMI**
3. Descendez jusqu'à **🔧 Tools & Analysis**
4. Tapez sur **🎯 AIMI Context**
5. Activez le switch **"Enable Context Module"**

✅ **Le module est maintenant actif !**

---

### **ÉTAPE 2 : Utiliser un Preset Rapide**

**Exemple** : Vous allez faire du sport

1. Dans l'écran "AIMI Context"
2. Tapez sur le chip **🏃 Cardio**
3. ✅ Un contexte "Activité Cardio" est créé pour 60 minutes

**Résultat** :
- 📉 SMB réduit de ~25% (moins d'insuline rapide)
- ⏱️ Intervalle entre SMB augmenté de 3-5 minutes
- ⚠️ Préférence pour basal temporaire

---

### **ÉTAPE 3 : Vérifier que ça Marche**

1. Attendez le prochain cycle de la boucle (3-5 minutes)
2. Allez dans **OpenAPS** → **APS** → **Dernier Run**
3. Cherchez dans les logs :
   ```
   ═══ CONTEXT MODULE ═══
   🎯 Active Contexts: 1
     • Activity
     SMB: 2.5→1.9U (×0.75)
   ```

✅ **Si vous voyez ça, le module fonctionne !**

---

## 🎮 **MODES D'UTILISATION**

### **MODE 1 : Presets Rapides (Recommandé)**

**Quand ?** Situations courantes et prévisibles

**Comment ?**
1. Tapez sur un des 10 chips prédéfinis :
   - 🏃 **Cardio** : Course, vélo, natation (60 min)
   - 💪 **Strength** : Musculation, fitness (45 min)
   - 🧘 **Yoga** : Yoga, stretching (60 min)
   - ⚽ **Sport** : Sport d'équipe (90 min)
   - 🚶 **Walking** : Marche modérée (30 min)
   - 🤒 **Sick** : Maladie, fièvre (24h)
   - 😰 **Stress** : Stress intense (2h)
   - 🍕 **Meal Risk** : Repas non annoncé possible (2h)
   - 🍷 **Alcohol** : Consommation d'alcool (4h)
   - ✈️ **Travel** : Voyage, décalage horaire (24h)

2. Le contexte est **immédiatement actif**
3. Il **expire automatiquement** après la durée prédéfinie

**Avantages** :
- ✅ Ultra-rapide (1 tap)
- ✅ Paramètres optimaux pré-configurés
- ✅ Pas besoin de réfléchir

---

### **MODE 2 : Texte Libre avec IA (Avancé)**

**Quand ?** Situations complexes ou spécifiques

**Prérequis** :
1. Activer **"Enable AI Parsing (LLM)"**
2. Configurer une **clé API** (voir section Configuration)

**Comment ?**
1. Tapez votre situation en langage naturel :
   - ✍️ "Heavy cardio session 90 minutes"
   - ✍️ "Feeling sick with flu"
   - ✍️ "Intense stress at work all day"
   - ✍️ "Starting mountain hike 3 hours"

2. Tapez sur **🤖 AI Parse**
3. L'IA analyse et crée le(s) contexte(s) approprié(s)

**Avantages** :
- ✅ Flexible et précis
- ✅ Comprend le langage naturel
- ✅ Peut combiner plusieurs intents

**Note** : Si l'IA échoue, le système utilise automatiquement le parser offline (moins précis mais fonctionnel)

---

## 📋 **GÉRER LES CONTEXTES ACTIFS**

### **Voir les Contextes Actifs**

Dans l'écran "AIMI Context", vous voyez une liste de cartes :

```
┌─────────────────────────────────┐
│ 🏃 Activity: CARDIO MEDIUM      │
│ 45min restantes  ✓ High conf    │
│ [Extend]  [Remove]              │
└─────────────────────────────────┘
```

**Infos affichées** :
- **Type** : Activity, Illness, Stress, etc.
- **Détails** : Intensité, spécificités
- **Temps restant** : Compte à rebours
- **Confiance** : Si créé par IA (High/Medium/Low)

---

### **Prolonger un Contexte**

**Exemple** : Votre séance de sport dure plus longtemps que prévu

1. Tapez sur **[Extend]**
2. Choisissez la durée supplémentaire :
   - 15 min
   - 30 min
   - 1 heure
   - 2 heures

✅ Le contexte est prolongé immédiatement

---

### **Supprimer un Contexte**

**Exemple** : Vous arrêtez votre sport plus tôt

1. Tapez sur **[Remove]**
2. Le contexte est supprimé immédiatement

✅ AAPS revient au comportement normal au prochain cycle

---

### **Tout Supprimer**

1. Tapez sur **[Clear All]** (en haut à droite)
2. Confirmez
3. Tous les contextes sont supprimés

---

## ⚙️ **CONFIGURATION AVANCÉE**

### **Activer le Parsing IA (LLM)**

**Pourquoi ?** Pour utiliser le mode texte libre

**Comment ?**

1. Dans l'écran "AIMI Context", activez **"Enable AI Parsing (LLM)"**

2. Allez dans **Paramètres** → **OpenAPS AIMI** → **AI Assistant**

3. Configurez votre provider favori (un seul suffit) :

   **Option A : OpenAI (GPT-4)**
   - Créez une clé API sur https://platform.openai.com/api-keys
   - Collez dans "OpenAI API Key"
   - Sélectionnez "OpenAI" comme provider pour Context

   **Option B : Google Gemini (Recommandé - Gratuit)**
   - Créez une clé sur https://aistudio.google.com/app/apikey
   - Collez dans "Gemini API Key"
   - Sélectionnez "Gemini" comme provider pour Context

   **Option C : DeepSeek (Économique)**
   - Créez une clé sur https://platform.deepseek.com/api_keys
   - Collez dans "DeepSeek API Key"
   - Sélectionnez "DeepSeek" comme provider pour Context

   **Option D : Claude (Anthropic)**
   - Créez une clé sur https://console.anthropic.com/
   - Collez dans "Claude API Key"
   - Sélectionnez "Claude" comme provider pour Context

4. Testez en tapant un texte et en appuyant sur "🤖 AI Parse"

---

### **Mode de Contexte**

Définit le niveau de prudence des ajustements :

**CONSERVATIVE** (Prudent)
- Ajustements réduits de ~5%
- Recommandé : Débutants, forte variabilité glycémique
- Exemple : Cardio → -20% SMB au lieu de -25%

**BALANCED** (Équilibré) ⭐ **Par défaut**
- Ajustements standards
- Recommandé : La plupart des utilisateurs
- Exemple : Cardio → -25% SMB

**AGGRESSIVE** (Confiant)
- Ajustements augmentés de ~5%
- Recommandé : Utilisateurs expérimentés, boucle stable
- Exemple : Cardio → -30% SMB

**Comment changer ?**
1. Paramètres → OpenAPS AIMI → Context Mode
2. Sélectionnez votre préférence

---

## 📊 **EXEMPLES CONCRETS**

### **Exemple 1 : Séance de Sport**

**Situation** : Vous allez courir 45 minutes

**Actions** :
1. 10 minutes avant : Tapez **🏃 Cardio**
2. Lancez votre course
3. AAPS réduit automatiquement les SMB
4. Risque d'hypo diminué

**Résultat typique** :
- SMB : 2.5U → 1.9U (-24%)
- Intervalle : 3min → 6min
- Moins de risque d'hypo pendant l'effort

---

### **Exemple 2 : Maladie**

**Situation** : Vous êtes grippé, glycémie qui monte

**Actions** :
1. Tapez **🤒 Sick**
2. Le contexte dure 24h (renouvelable)
3. Si glycémie > 160 mg/dL : AAPS peut être légèrement plus agressif (+5% SMB)
4. Si glycémie normale/basse : AAPS reste prudent

**Note** : La maladie peut causer une résistance à l'insuline, le module s'adapte intelligemment selon votre glycémie.

---

### **Exemple 3 : Stress Important**

**Situation** : Journée stressante au travail

**Actions** :
1. Le matin : Tapez **😰 Stress**
2. Durée par défaut : 2h (prolongez si besoin)
3. AAPS applique une légère réduction de prudence (-2% SMB)

**Résultat** :
- Adaptation mineure (le stress a un impact faible mais réel)
- Peut être combiné avec d'autres contextes

---

### **Exemple 4 : Repas Non Annoncé**

**Situation** : Vous suspectez avoir oublié d'annoncer des glucides

**Actions** :
1. Tapez **🍕 Meal Risk**
2. Durée : 2h
3. AAPS reste réactif MAIS augmente l'intervalle de sécurité

**Résultat** :
- SMB maintenus (pour rattraper la montée)
- Intervalle +4min (pour plus de marge de sécurité)
- Réduit le risque de sur-correction

---

### **Exemple 5 : Alcool**

**Situation** : Soirée avec 2-3 verres de vin

**Actions** :
1. Au début de la soirée : Tapez **🍷 Alcohol**
2. Durée : 4h (couvre l'absorption + effet retardé)
3. AAPS devient TRÈS prudent

**Résultat** :
- SMB : -35% (forte réduction)
- Intervalle : +7min
- ⚠️ Préfère fortement le basal
- Protège contre l'hypo retardée

**⚠️ IMPORTANT** : Surveillez tout de même votre glycémie de près !

---

### **Exemple 6 : Combinaison**

**Situation** : Sport + Stress le même jour

**Actions** :
1. Matin stressant : **😰 Stress**
2. Midi : Sport → **🏃 Cardio**
3. Les deux contextes sont actifs simultanément

**Résultat** :
- Les modulations se **combinent** multiplicativement
- Cardio: ×0.75, Stress: ×0.98 → **Total: ×0.735** (environ -26.5% SMB)
- Le système reste cohérent et sûr

---

## 🔍 **VÉRIFIER QUE ÇA FONCTIONNE**

### **Méthode 1 : Console Log (Expert)**

1. **OpenAPS** → **APS** → **Dernier Run**
2. Cherchez :
   ```
   ═══ CONTEXT MODULE ═══
   🎯 Active Contexts: 1
     • Activity
     SMB: 2.5→1.9U (×0.75)
     Interval: 3→6min (+3)
     ⚠️ Prefers TEMP BASAL over SMB
     → Activity MEDIUM detected: reduce SMB by 25%
   ```

3. Si vous voyez cette section → ✅ **Fonctionne**

---

### **Méthode 2 : Observer les Décisions**

**Sans contexte** :
- SMB typique : 2.5U toutes les 3 minutes

**Avec contexte Cardio actif** :
- SMB réduit : ~1.9U toutes les 6 minutes
- Plus de TBR (basal temporaire) visibles

**Indicateur visuel** : Moins de micro-bolus dans l'historique traitements

---

### **Méthode 3 : Test Simple**

1. Glycémie stable à 150 mg/dL
2. Ajoutez **🏃 Cardio**
3. Attendez 5-10 minutes (2 cycles de boucle)
4. Vérifiez les traitements récents → SMB devrait être plus petit qu'avant

---

## ⚠️ **PRÉCAUTIONS ET LIMITES**

### **Ce que le Module FAIT** ✅

- ✅ Ajuste SMB et intervalles de manière **bornée** (-50% à +10%)
- ✅ Suggère préférence basal quand approprié
- ✅ Se combine intelligemment avec Trajectory Guard
- ✅ Expire automatiquement (pas d'oubli)
- ✅ Fonctionne en **offline** (parser de secours)

### **Ce que le Module NE FAIT PAS** ❌

- ❌ **Ne remplace PAS** votre jugement clinique
- ❌ **Ne dispense PAS** de surveiller votre BG
- ❌ **N'annonce PAS** automatiquement des glucides
- ❌ **Ne détecte PAS** automatiquement les situations (vous devez informer)
- ❌ **Ne garantit PAS** l'absence d'hypo/hyper

### **Quand Rester Prudent** ⚠️

- **Alcool** : Surveillez de près (risque d'hypo retardée réelle)
- **Sport intense** : Vérifiez BG régulièrement
- **Maladie** : Contrôles fréquents (réponse imprévisible)
- **Première utilisation** : Testez sur situations connues d'abord

---

## 🛠️ **DÉPANNAGE**

### **Problème 1 : Le Module Ne S'Active Pas**

**Symptôme** : Contextes ajoutés mais pas d'effet dans les logs

**Solutions** :
1. Vérifiez que **"Enable Context Module"** est ☑️ activé
2. Relancez AAPS (parfois nécessaire au premier usage)
3. Attendez 2-3 cycles de boucle (5-10 min)
4. Vérifiez les logs : cherchez "CONTEXT MODULE"

---

### **Problème 2 : L'IA Ne Parse Pas**

**Symptôme** : "🤖 AI Parse" ne crée rien ou erreur

**Solutions** :
1. Vérifiez **"Enable AI Parsing (LLM)"** = ☑️
2. Vérifiez la **clé API** configurée et valide
3. Vérifiez la connexion **Internet**
4. Testez avec un texte simple : "running 30 minutes"
5. En dernier recours : **Désactivez LLM** → le parser offline prend le relais

---

### **Problème 3 : Contexte Pas Supprimé**

**Symptôme** : Le contexte reste actif après expiration

**Solutions** :
1. Vérifiez l'heure système (le timestamp est crucial)
2. Supprimez manuellement : **[Remove]**
3. Ou : **[Clear All]**

---

### **Problème 4 : Effet Trop Fort/Faible**

**Symptôme** : L'ajustement ne vous convient pas

**Solutions** :
1. Changez le **Mode de Contexte** :
   - Trop fort → **AGGRESSIVE** (réduit l'effet)
   - Trop faible → **CONSERVATIVE** (augmente l'effet)

2. Ou supprimez le contexte et testez sans

---

## 📚 **QUESTIONS FRÉQUENTES (FAQ)**

### **Q1 : Combien de contextes puis-je avoir en même temps ?**
**R** : Illimité. Le système les combine intelligemment.

### **Q2 : Que se passe-t-il si j'oublie de supprimer un contexte ?**
**R** : Il expire automatiquement après sa durée prédéfinie. Pas de souci.

### **Q3 : Le module fonctionne-t-il sans Internet ?**
**R** : Oui ! Seul le parsing IA nécessite Internet. Le parser offline est toujours disponible et les contextes actifs fonctionnent même hors ligne.

### **Q4 : Puis-je personnaliser les presets ?**
**R** : Pas dans cette version. Utilisez le mode texte libre pour des situations spécifiques.

### **Q5 : Le module remplace-t-il autosens ?**
**R** : Non, ils sont **complémentaires**. Autosens détecte automatiquement, Context vous permet d'informer proactivement.

### **Q6 : Comment savoir quelle intensité choisir ?**
**R** : Les presets ont des intensités par défaut (généralement MEDIUM). En mode texte, l'IA devine ou mettez "light/moderate/heavy/intense" dans votre phrase.

### **Q7 : Ça consomme de la batterie ?**
**R** : Négligeable. L'IA parsing (si activé) fait un appel API occasionnel, mais rien de significatif.

### **Q8 : Les données sont-elles partagées ?**
**R** : Si vous utilisez l'IA : votre texte est envoyé au provider LLM (OpenAI/Gemini/etc). Le reste est 100% local. Si vous n'activez pas l'IA, tout est local.

---

## 📈 **CONSEILS D'EXPERT**

### **Astuce 1 : Anticipez**
Ajoutez le contexte **10-15 minutes AVANT** l'activité pour que AAPS ait le temps d'adapter.

### **Astuce 2 : Combinez avec Temp Target**
Pour le sport : **Context Cardio** + **Temp Target 140 mg/dL** = Protection maximale.

### **Astuce 3 : Journal**
Notez vos résultats les premières fois pour affiner votre usage (mode Conservative vs Balanced vs Aggressive).

### **Astuce 4 : Presets d'abord**
Commencez par les presets, maîtrisez-les, puis explorez le mode texte IA si besoin.

### **Astuce 5 : Vérifiez Trajectory**
Si Trajectory Guard ET Context sont actifs, les effets se cumulent → Double protection dans les situations à risque.

---

## ✅ **CHECKLIST PREMIÈRE UTILISATION**

Avant de vraiment compter sur le module :

- [ ] Module activé et testé sur situation simple (preset)
- [ ] Logs vérifiés (section "CONTEXT MODULE" présente)
- [ ] Effet observé sur SMB (réduction visible)
- [ ] Test sur 2-3 situations différentes
- [ ] Compréhension des limites
- [ ] Surveillance BG maintenue

**Quand cette checklist est complète** → ✅ Vous pouvez utiliser en confiance !

---

## 🎓 **RÉSUMÉ ULTRA-RAPIDE**

1. **Activer** : Settings → AIMI Context → "Enable Context Module" ☑️
2. **Utiliser** : Tapez un preset (🏃 Cardio, 🤒 Sick, etc.)
3. **Vérifier** : Logs APS → Cherchez "🎯 Active Contexts"
4. **Gérer** : Extend/Remove dans la liste
5. **Profiter** : AAPS s'adapte automatiquement !

---

**Module Contexte AIMI - Votre Diabète, Vos Situations, Notre Adaptation** 🎯

**Support** : Consultez les logs ou contactez votre technicien AAPS.  
**Version** : 1.0 (2026-01-03)

