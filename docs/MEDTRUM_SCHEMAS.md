# Schémas : Déconnexions Medtrum - Problème vs Solution

Ce document contient des schémas ASCII pour visualiser rapidement le problème et la solution.

---

## 📊 Schéma 1 : Flow de Connexion Normal vs Zombie

### **NORMAL FLOW** ✅

```
┌─────────────────────────────────────────────────────────────────┐
│                     CONNEXION NORMALE                            │
└─────────────────────────────────────────────────────────────────┘

1. connect() appelé
   │
   ├─→ mBluetoothGatt = device.connectGatt(...)
   │
2. Android BLE Stack traite
   │
   ├─→ 1-2 secondes
   │
3. onConnectionStateChange() callback
   │
   ├─→ status=GATT_SUCCESS, newState=CONNECTED
   │
4. discoverServices()
   │
   ├─→ onServicesDiscovered()
   │
5. findCharacteristics() + enable notifications
   │
   ├─→ onDescriptorWrite()
   │
6. Machine à états : IdleState → AuthState → ... → ReadyState
   │
   └─→ ✅ CONNECTÉ ET OPÉRATIONNEL
```

---

### **ZOMBIE STATE FLOW** ❌

```
┌─────────────────────────────────────────────────────────────────┐
│                     ÉTAT ZOMBIE                                  │
└─────────────────────────────────────────────────────────────────┘

1. connect() appelé
   │
   ├─→ mBluetoothGatt = device.connectGatt(...)
   │
2. Réseau devient instable / Bug Android BLE
   │
   ├─→ Stack BLE Android entre en état inconsistant
   │
3. onConnectionStateChange() NE SE DÉCLENCHE JAMAIS 🚨
   │
   ├─→ ⏰ Timeout actuel : 2 secondes
   │
4. disconnect() appelé par timeout
   │
   ├─→ mBluetoothGatt?.disconnect()  [ASYNC - peut ne rien faire]
   ├─→ mBluetoothGatt?.close()       [Peut échouer silencieusement]
   │
5. État résultant (ZOMBIE) :
   │
   ├─→ mBluetoothGatt = BluetoothGatt@12345  [Objet existe mais mort]
   ├─→ isConnecting = true                    [État corrompu]
   ├─→ Cache BLE Android pollué               [Services stales]
   │
6. Tentative de reconnexion
   │
   ├─→ resetConnection() appelé
   ├─→ disconnect() + close() ne nettoient PAS le cache
   ├─→ Nouvelle connexion sur stack BLE corrompu
   │
   └─→ 🧟 DEUXIÈME ZOMBIE créé
        └─→ Après 10-20 cycles : SEUL FIX = REDÉMARRAGE TÉLÉPHONE
```

---

## 🔧 Schéma 2 : Quick Fix - Force Reset Flow

### **AVEC FORCE RESET** ✅

```
┌─────────────────────────────────────────────────────────────────┐
│              FORCE RESET + WATCHDOG PROTECTION                   │
└─────────────────────────────────────────────────────────────────┘

1. Détection d'un problème (timeout ou watchdog)
   │
   ├─→ Timeout après 1.5s (au lieu de 2s)
   └─→ OU Watchdog détecte 90s sans activité BLE
   │
2. forceResetBluetoothGatt() appelé
   │
   ├─→ Step 1: Arrêt de toutes opérations pending
   |    └─→ handler.removeCallbacks(all)
   │
   ├─→ Step 2: mBluetoothGatt?.disconnect()
   |    └─→ Thread.sleep(150)  [Laisse Android traiter]
   │
   ├─→ Step 3: ⭐ gatt.refresh() via reflection
   |    |   ┌─────────────────────────────────────┐
   |    |   │ VIDE LE CACHE BLE ANDROID           │
   |    |   │ - Services GATT                      │
   |    |   │ - Characteristics                    │
   |    |   │ - Descriptors                        │
   |    |   │ - État interne Android               │
   |    |   └─────────────────────────────────────┘
   |    └─→ Thread.sleep(150)  [Laisse refresh() s'exécuter]
   │
   ├─→ Step 4: mBluetoothGatt?.close()
   |    └─→ Libération finale des ressources
   │
   └─→ Step 5: Nettoyage état interne
        ├─→ mBluetoothGatt = null
        ├─→ isConnected = false
        ├─→ isConnecting = false
        ├─→ uartWrite = null
        ├─→ uartRead = null
        └─→ ✅ ÉTAT PROPRE - Prêt pour nouvelle connexion
```

---

## 🔍 Schéma 3 : Watchdog Zombie Detection

```
┌─────────────────────────────────────────────────────────────────┐
│                   WATCHDOG DETECTION FLOW                        │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────┐
│  zombieCheckRunnable│
│  (s'exécute toutes │
│   les 30 secondes)│
└────────┬─────────┘
         │
         ├─→ CHECK 1: Connected mais no activity > 90s ?
         │   └─→ YES: 🧟 ZOMBIE DÉTECTÉ → forceReset()
         │
         ├─→ CHECK 2: Connecting depuis > 30s ?
         │   └─→ YES: 🧟 ZOMBIE DÉTECTÉ → forceReset()
         │
         ├─→ CHECK 3: Gatt exists mais !connected && !connecting ?
         │   └─→ YES: ⚠️ ÉTAT INCONSISTANT → forceReset()
         │
         └─→ Tous checks OK
             ├─→ Log : "BLE healthy"
             └─→ Reschedule dans 30s

┌──────────────────────────────────────────────────────────────────┐
│  lastBLEActivityTimestamp mis à jour dans TOUS les callbacks :   │
│  - onCharacteristicRead                                         │
│  - onCharacteristicWrite                                        │
│  - onCharacteristicChanged                                      │
│  - onDescriptorRead                                             │
│  - onDescriptorWrite                                            │
│  - onConnectionStateChange                                      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Schéma 4 : Comparaison Architecture

### **MEDTRUM (Actuel) - Callbacks**

```
┌────────────────────────────────────────────────────────────────┐
│                    ARCHITECTURE ACTUELLE                        │
└────────────────────────────────────────────────────────────────┘

    MedtrumService                BLEComm               Android BLE
         │                           │                        │
         │ connect()                 │                        │
         ├─────────────────────────→│                        │
         │                           │ connectGatt()          │
         │                           ├──────────────────────→│
         │                           │                        │
         │                           │                  [Async Processing]
         │                           │                        │
         │                           │ ← onConnectionStateChange()
         │                           │                        │
         │ ← onBLEConnected()       │                        │
         ├───────────────────────────┤                        │
         │                           │                        │
    [Machine à états]            [Handler]              [Binder Thread]
    
    PROBLÈMES:
    - 3 threads différents (Service, Handler, Binder)
    - États répartis (isConnected, currentState, connectionState)
    - Pas de cancellation handling
    - Busy-wait loops
```

---

### **COMBO (Référence) - Coroutines**

```
┌────────────────────────────────────────────────────────────────┐
│              ARCHITECTURE COROUTINES (CIBLE)                    │
└────────────────────────────────────────────────────────────────┘

    MedtrumService              BLEConnection           Android BLE
         │                           │                        │
         │ launch {                  │                        │
         │   connect()               │                        │
         ├─────────────────────────→│                        │
         │   [suspend]               │ connectGatt()          │
         │                           ├──────────────────────→│
         │                           │                        │
         │                           │ suspendCancellableCoroutine {
         │                           │   continuation →       │
         │                           │                        │
         │                           │ ← onConnectionStateChange()
         │                           │   continuation.resume()|
         │                           │ }                      │
         │ ← return                  │                        │
         ├───────────────────────────┤                        │
         │ }                         │                        │
    
    [CoroutineScope]           [StateFlow]            [Binder Thread]
    
    AVANTAGES:
    - Structured concurrency (auto-cleanup)
    - CancellationException propagation
    - État centralisé (StateFlow)
    - Pas de busy-wait (suspend)
```

---

## 📈 Schéma 5 : Timeline de Résolution

```
┌────────────────────────────────────────────────────────────────┐
│                       TIMELINE GLOBALE                          │
└────────────────────────────────────────────────────────────────┘

2025-12-21 (AUJOURD'HUI)
   │
   └─→ [J+0] 📋 Analyse complète
        ├─→ Architecture review
        ├─→ Comparaison Combo
        ├─→ 5 documents créés
        └─→ ✅ FAIT
   
   ↓
   
   [J+1] 🔧 Implémentation Quick Fix
        ├─→ 09:00-13:00 : Dev (4h)
        │    ├─→ forceResetBluetoothGatt()
        │    ├─→ Watchdog zombie
        │    └─→ Logs détaillés
        ├─→ 14:00-16:00 : Tests compilation (2h)
        └─→ Livrable: Branch prête
   
   ↓
   
   [J+2] 🧪 Tests Device Réels
        ├─→ 09:00-13:00 : Tests scenarios (4h)
        │    ├─→ Mode avion
        │    ├─→ Déconnexions forcées
        │    └─→ Stress test
        ├─→ 14:00-16:00 : Review + polish (2h)
        └─→ Livrable: Code validé
   
   ↓
   
   [J+3 à J+9] 📊 Beta Testing
        ├─→ Déploiement beta
        ├─→ Monitoring logs
        └─→ Collecte feedback
   
   ↓
   
   [J+10] 🚀 Production Release
        └─→ ✅ Quick Fix déployé
   
   ↓
   
2026-Q1 (Phase 2)
   │
   ├─→ Janvier: Spec + Design refactor
   ├─→ Février: Implémentation coroutines
   ├─→ Mars: Beta testing étendu
   └─→ Avril: ✅ Architecture finale
```

---

## 🎨 Schéma 6 : État Before vs After

### **BEFORE (Problème Actuel)** ❌

```
┌──────────────────────────────────────────────┐
│         USER EXPERIENCE ACTUELLE              │
└──────────────────────────────────────────────┘

Jour 1: 🟢 Connexion OK
         ↓
Jour 2: 🟡 Déconnexion (réseau instable)
         ↓
        Tentative reconnexion...
         ↓
        🔴 ÉCHEC - État zombie
         ↓
        Retry...  🔴 ÉCHEC
        Retry...  🔴 ÉCHEC
        Retry...  🔴 ÉCHEC
         ↓
    😤 UTILISATEUR FRUSTRÉ
         ↓
    📱 REDÉMARRAGE TÉLÉPHONE (5 min)
         ↓
Jour 3: 🟢 Re-connexion OK
         ↓
Jour 4: 🔴 Même problème...
         ↓
    💢 ABANDON DU MEDTRUM ?

FRÉQUENCE: Hebdomadaire à quotidienne
IMPACT: ⭐⭐⭐⭐⭐ CRITIQUE
```

---

### **AFTER (Avec Quick Fix)** ✅

```
┌──────────────────────────────────────────────┐
│       USER EXPERIENCE AVEC FIX                │
└──────────────────────────────────────────────┘

Jour 1: 🟢 Connexion OK
         ↓
Jour 2: 🟡 Déconnexion (réseau instable)
         ↓
        Tentative reconnexion...
         ↓
        🟠 Timeout détecté (1.5s)
         ↓
        🔧 Force Reset automatique
         ↓
        🟢 RECONNEXION RÉUSSIE (3 sec total)
         ↓
    😊 UTILISATEUR NE REMARQUE RIEN
         ↓
Jour 3: 🟢 Fonctionnement normal
         ↓
Jour 4: 🟢 Stable
         ↓
    ✅ CONFIANCE DANS LE SYSTÈME

FRÉQUENCE: 0 redémarrage téléphone
IMPACT: ⭐⭐⭐⭐⭐ RÉSOLU
```

---

## 🔬 Schéma 7 : Diagnostic d'État Zombie

```
┌────────────────────────────────────────────────────────────────┐
│                  COMMENT IDENTIFIER UN ZOMBIE                   │
└────────────────────────────────────────────────────────────────┘

SYMPTÔMES VISIBLES:
├─→ ["Connecting..." pendant >30s]
├─→ ["Connected" mais aucune donnée reçue]
├─→ [Reconnexion échoue systématiquement]
└─→ [Redémarrage téléphone = seule solution]

DANS LES LOGS (TAG: PUMPBTCOMM):
├─→ "disconnect timeout reached" répété
├─→ "mBluetoothGatt is not null" lors de nouvelle connexion
├─→ "onConnectionStateChange error status: 133" (BLE error)
├─→ Absence de "onCharacteristicChanged" sur >60s
└─→ "Medtrum Service State timeout" répété

ÉTAT INTERNE (visible avec BLEDiagnostics):
├─→ gattExists: true
├─→ isConnected: false
├─→ isConnecting: true      ← ⚠️ Incohérent
├─→ lastActivity: 90000ms   ← ⚠️ >90s
└─→ pendingRunnables: 5     ← ⚠️ Accumulés

DIAGNOSTIC: 🧟 ÉTAT ZOMBIE CONFIRMÉ
SOLUTION: Force Reset BLE
```

---

## 📚 Schéma 8 : Structure des Documents

```
docs/
│
├─→ README_MEDTRUM_ANALYSIS.md       [📖 INDEX - START HERE]
│    └─→ Guide de navigation
│
├─→ MEDTRUM_MESSAGE_POUR_MTR.md      [💌 POUR TOI - Lis en 2ème]
│    └─→ Résumé personnalisé + Options d'action
│
├─→ MEDTRUM_EXECUTIVE_SUMMARY.md     [📊 DÉCISION - 5 min]
│    └─→ TL;DR, recommandations, FAQ
│
├─→ MEDTRUM_DISCONNECTION_ANALYSIS.md [🔬 ANALYSE - 20 min]
│    └─→ Architecture, problèmes, options
│
├─→ MEDTRUM_FIX_IMPLEMENTATION_PLAN.md [🛠️ IMPLÉMENTATION - Guide]
│    └─→ Code étape par étape
│
├─→ MEDTRUM_VS_COMBO_TECHNICAL_DEEP_DIVE.md [🎓 DEEP DIVE - 30 min]
│    └─→ Comparaison technique détaillée
│
└─→ MEDTRUM_SCHEMAS.md (CE DOCUMENT)  [📐 SCHÉMAS]
     └─→ Visualisations ASCII

pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/
│
└─→ util/
     └─→ BLEDiagnostics.kt           [🔧 MONITORING]
          └─→ Classe diagnostic prête à utiliser
```

---

## ✅ Schéma 9 : Checklist d'Implémentation

```
┌────────────────────────────────────────────────────────────────┐
│                  QUICK FIX IMPLEMENTATION                       │
└────────────────────────────────────────────────────────────────┘

BLEComm.kt
│
├─→ [ ] Ajouter forceResetBluetoothGatt()
│    ├─→ [ ] Reflection gatt.refresh()
│    ├─→ [ ] Thread.sleep() delays
│    └─→ [ ] Try-catch wrapping
│
├─→ [ ] Modifier disconnect()
│    ├─→ [ ] Réduire timeout à 1.5s
│    └─→ [ ] Appeler forceReset en backup
│
├─→ [ ] Modifier onConnectionStateChangeSynchronized()
│    ├─→ [ ] Clear pending runnables
│    └─→ [ ] Use forceReset sur error
│
├─→ [ ] Ajouter watchdog zombie
│    ├─→ [ ] zombieCheckRunnable
│    ├─→ [ ] lastBLEActivityTimestamp
│    └─→ [ ] Update dans tous callbacks
│
├─→ [ ] Ajouter logBLEState()
│    └─→ [ ] Appeler aux points critiques
│
└─→ [ ] Tests compilation
     └─→ [ ] ./gradlew :pump:medtrum:assembleDebug

MedtrumService.kt
│
├─→ [ ] Modifier onBLEDisconnected()
│    └─→ [ ] Notification zombie détecté
│
└─→ [ ] Tests compilation
     └─→ [ ] ./gradlew assembleDebug

strings.xml
│
└─→ [ ] Ajouter medtrum_pump_reconnecting_after_error

Tests Device
│
├─→ [ ] Mode avion test
├─→ [ ] Déconnexions forcées
├─→ [ ] Stress test 24h
└─→ [ ] Logs vérifiés

Documentation
│
├─→ [ ] Commit messages
├─→ [ ] Release notes
└─→ [ ] Update README

TOTAL ESTIMÉ: 10h sur 2 jours
```

---

**Auteur**: Lyra  
**Date**: 2025-12-21  
**Usage**: Référence visuelle pour comprendre le problème et la solution
