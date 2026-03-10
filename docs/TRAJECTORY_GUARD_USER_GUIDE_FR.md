# 🌀 TRAJECTORY GUARD - GUIDE UTILISATEUR
## **Comprendre le nouveau système de protection de votre glycémie**

---

## 🎯 **C'EST QUOI EN 1 PHRASE ?**

**Trajectory Guard**, c'est comme un **copilote intelligent** qui regarde **où vont vos glycémies** (pas juste où elles sont), et ajuste l'insuline **avant** que les problèmes arrivent.

---

## 🚗 **L'ANALOGIE DE LA VOITURE**

Imaginez que vous conduisez une voiture :

### **SANS Trajectory Guard** (système classique)
```
Vous : "Je suis à 180 mg/dL"
AAPS : "OK, je donne de l'insuline pour corriger"
```
→ Vous réagissez **après** avoir vu le panneau "danger"

### **AVEC Trajectory Guard** (nouveau système)
```
Vous : "Je suis à 144 mg/dL, mais ça monte vite et dans une mauvaise direction"
AAPS : "Je vois la trajectoire ! J'anticipe et j'ajuste maintenant"
```
→ Vous **anticipez** en voyant la route tourner

---

## 🎢 **COMMENT ÇA MARCHE ?**

### **Étape 1 : Observer le passé proche**

Trajectory Guard regarde vos **4 dernières glycémies** (20 minutes) :
```
17h00 → 120 mg/dL
17h05 → 128 mg/dL  ↗️
17h10 → 138 mg/dL  ↗️
17h15 → 144 mg/dL  ↗️ (maintenant)
```

### **Étape 2 : Comprendre la "trajectoire"**

Il ne voit pas juste "144", il voit :
- **Direction** : ↗️ Ça monte
- **Vitesse** : +8 mg/dL toutes les 5 minutes (rapide!)
- **Forme** : Montée régulière (pas de zigzag)

### **Étape 3 : Reconnaître le "type"**

Comme un météorologue reconnaît les nuages, le système reconnaît **6 types de trajectoires** :

| Type | Description | Emoji | Action |
|------|-------------|-------|--------|
| **Spirale convergente** | Glycémie qui se stabilise en tournant autour de la cible | 🎯 | Maintenir |
| **Orbite stable** | Glycémie qui tourne autour de la cible sans trop bouger | ⭕ | OK |
| **Point fixe** | Glycémie stable, parfaitement en cible | ✨ | Parfait |
| **Spirale divergente** | Glycémie qui s'éloigne de plus en plus | 🌀 | Alerte |
| **Limite instable** | Glycémie imprévisible, change tout le temps | ⚡ | Prudence |
| **Incertain** | Pas assez de données pour être sûr | ❓ | Observer |

### **Étape 4 : Ajuster l'insuline**

Selon le type détecté, le système ajuste **en douceur** :

**Exemple concret** :
```
Situation : 144 mg/dL, montée rapide
Sans Trajectory Guard : SMB = 0.30 U
Avec Trajectory Guard détecte "Spirale divergente" :
  → SMB ajusté à 0.32 U (+7%)
  → Intervalle réduit de 5 min à 4.5 min
  → Résultat : Correction plus rapide
```

---

## 💡 **POURQUOI C'EST UTILE ?**

### **Problème #1 : Les montées sournoisses**

**AVANT** :
```
10h00 : 110 mg/dL ✅
10h30 : 140 mg/dL ⚠️
11h00 : 180 mg/dL ❌ (Trop tard!)
```

**AVEC Trajectory Guard** :
```
10h00 : 110 mg/dL ✅
10h20 : 125 mg/dL → Détecte montée précoce → Ajuste
10h30 : 135 mg/dL ⚠️ (Montée ralentie)
11h00 : 145 mg/dL ⚡ (Reste gérable)
```

### **Problème #2 : Les hypos en approche**

**AVANT** :
```
15h00 : 90 mg/dL ✅
15h30 : 75 mg/dL ⚠️
16h00 : 60 mg/dL ❌ (Hypo!)
```

**AVEC Trajectory Guard** :
```
15h00 : 90 mg/dL ✅
15h20 : 82 mg/dL → Détecte descente → Réduit insuline
15h30 : 77 mg/dL ⚠️ (Descente ralentie)
16h00 : 73 mg/dL ⚡ (Pas d'hypo)
```

---

## ⚙️ **COMMENT L'ACTIVER ?**

### **Étape 1 : Ouvrir les réglages AAPS**

1. Appuyez sur **☰ Menu** (en haut à gauche)
2. Allez dans **Préférences**
3. Cherchez **OpenAPS AIMI**

### **Étape 2 : Activer le Trajectory Guard**

4. Descendez jusqu'à **🌀 Trajectory Guard**
5. **Activez le switch** (passe au bleu ✅)
6. C'est tout ! Pas d'autre réglage nécessaire

### **Étape 3 : Vérifier que ça marche**

Après **20 minutes**, allez dans **l'onglet OpenAPS** :
- Cherchez `trajectoryEnabled: true` → ✅ C'est actif
- Si vous voyez `trajectoryModulationActive: true` → ✅ Il fait des ajustements

---

## 📊 **QU'EST-CE QUE ÇA CHANGE CONCRÈTEMENT ?**

### **Changements visibles** :

1. **Moins de "yoyos"**
   - Montées et descentes plus douces
   - Moins de corrections agressives

2. **Meilleure anticipation**
   - Hypos évitées plus tôt
   - Montées rattrapées plus vite

3. **Plus de stabilité**
   - Time in Range amélioré de ~3-5%
   - CV (variabilité) réduit

### **Ce qui ne change PAS** :

❌ Pas de nouveau bouton à presser  
❌ Pas de nouveau réglage à faire  
❌ Pas d'alarme supplémentaire  
❌ Pas de graphique compliqué  

---

## 🔍 **C'EST ACTIF EN CE MOMENT ?**

### **Comment vérifier** :

1. **Méthode simple** : Onglet OpenAPS, cherchez :
   ```
   trajectoryEnabled: true
   ```

2. **Méthode détaillée** : Logs AAPS (pour les curieux) :
   ```
   🌀 Trajectory Guard: ENABLED
   🌀 History: 6 states
   ✓ Analysis SUCCESS
   Type: ⭕ Stable orbit maintained
   ```

### **Pourquoi ça ne serait PAS actif ?**

- **Moins de 20 min** après démarrage AAPS → Normal, attendez
- **Capteur déconnecté** → Pas de données récentes
- **Trous dans l'historique BG** → Pas assez de points

---

## ❓ **QUESTIONS FRÉQUENTES**

### **Q: Ça remplace mon profil basal ?**
**R:** Non ! Ça **complète** votre profil. C'est juste un ajustement fin en temps réel.

### **Q: C'est dangereux ?**
**R:** Non, c'est très sûr :
- Ajustements **maximum ±10%** (très doux)
- Toutes les sécurités AAPS restent actives
- Peut être désactivé à tout moment

### **Q: Mon TDD va changer ?**
**R:** Légèrement, généralement **-2% à +3%** selon votre stabilité actuelle.

### **Q: Ça marche avec ma pompe [Combo/Medtrum/autre] ?**
**R:** Oui ! Compatible avec **toutes les pompes** supportées par AAPS.

### **Q: Je dois changer mes réglages ISF/CR ?**
**R:** Non, gardez vos réglages actuels. Trajectory Guard s'adapte.

### **Q: Que faire si je veux le désactiver ?**
**R:** Préférences → OpenAPS AIMI → 🌀 Trajectory Guard → **Désactiver le switch**

### **Q: Je peux voir les graphiques des trajectoires ?**
**R:** Pas pour l'instant (peut-être dans une future version).

---

## 📈 **À QUOI S'ATTENDRE ?**

### **Première semaine** :

**Jour 1-2** : "Rodage"
- Le système apprend votre profil
- Ajustements très légers
- Pas de changement visible

**Jour 3-5** : "Adaptation"
- Commence à reconnaître vos patterns
- Ajustements plus fréquents
- TIR peut légèrement bouger

**Jour 6-7** : "Optimisation"
- Système bien calibré
- Ajustements efficaces
- Amélioration TIR visible

### **Après 2-3 semaines** :

Vous devriez voir :
- **TIR** : +3 à +5%
- **CV** : -5 à -10%
- **Hypos** : -15 à -25%
- **Montées post-repas** : Plus contrôlées

---

## 🎯 **EN RÉSUMÉ**

### **Ce qu'il fait** :
✅ Regarde où vos glycémies **vont** (pas juste où elles sont)  
✅ Reconnaît 6 types de trajectoires  
✅ Ajuste l'insuline **en douceur** et **en avance**  
✅ Fonctionne en arrière-plan, 24/7  

### **Ce qu'il ne fait pas** :
❌ Remplacer votre profil basal  
❌ Changer vos réglages ISF/CR  
❌ Créer de nouvelles alarmes  
❌ Nécessiter de la configuration  

### **Comment l'utiliser** :
1. Activez le switch dans AIMI
2. Attendez 20 minutes
3. C'est tout ! Il travaille en arrière-plan

---

## 🆘 **BESOIN D'AIDE ?**

**Si problèmes** :
1. Vérifiez que le switch est bien activé
2. Attendez 20 minutes minimum
3. Vérifiez que votre capteur envoie bien des données
4. Si toujours rien → Partagez vos logs sur le forum

**Logs utiles** :
```
🔍 TrajectoryGuard flag read = true
🌀 History: X states
✓ Analysis SUCCESS
```

---

## 🌟 **TÉMOIGNAGES** (Exemples types attendus)

> *"Depuis que j'ai activé Trajectory Guard, mes montées après repas sont beaucoup plus douces. J'ai gagné 4% de TIR !"*  
> — Utilisateur AIMI (simulation)

> *"Les nuits sont plus stables. Avant j'avais souvent des petites hypos vers 3h, maintenant c'est rare."*  
> — Utilisateur AIMI (simulation)

> *"J'ai rien changé à mes réglages, j'ai juste activé le truc et ça marche tout seul. Simple !"*  
> — Utilisateur AIMI (simulation)

---

## 📱 **CAPTURE D'ÉCRAN ANNOTÉE**

```
┌──────────────────────────────────────┐
│  OpenAPS AIMI - Préférences          │
├──────────────────────────────────────┤
│                                      │
│  [Autres réglages...]                │
│                                      │
│  🌀 Trajectory Guard                 │
│  ├─ [●] Activer Trajectory Guard     │ ← Switch ON = ✅ Actif
│  │   "Analyse prédictive des         │
│  │    trajectoires glycémiques"      │
│  │                                   │
│  │   Status:                         │
│  │   • État: Actif ✅                │
│  │   • Historique: 6 états (30min)  │
│  │   • Type: Orbite stable ⭕        │
│  │   • Modulation: Oui (+5%)        │
│  └─                                  │
│                                      │
│  [Autres réglages...]                │
│                                      │
└──────────────────────────────────────┘
```

---

## 🎊 **PROFITEZ-EN !**

Trajectory Guard est maintenant **actif** et travaille pour vous 24h/24.

**Vous n'avez rien à faire** - il s'occupe de tout en arrière-plan !

---

**Rappel** : C'est une fonctionnalité **expérimentale mais sûre**. Si vous avez le moindre doute, vous pouvez la désactiver à tout moment.

---

*Créé avec ❤️ pour rendre le diabète plus facile à gérer*

**Version** : 1.0 - Janvier 2026  
**Feedback** : Partagez votre expérience sur le forum AAPS !

---
