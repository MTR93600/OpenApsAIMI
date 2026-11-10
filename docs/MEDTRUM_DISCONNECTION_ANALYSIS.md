# Analyse Approfondie : Déconnexions Medtrum Nécessitant Redémarrage Téléphone

**Date**: 2025-12-21  
**Analyste**: Lyra (Expert Kotlin & Architecture Produit)  
**Sévérité**: CRITIQUE 🔴

---

## 📋 Résumé Exécutif

La pompe Medtrum subit des déconnexions Bluetooth qui laissent le driver dans un **état "zombie"**, nécessitant un redémarrage complet du téléphone pour rétablir la communication. Ce problème est **identique architecturalement** au bug résolu pour le driver Combo (conversation `496e4c96-849f-4467-bae8-8b58f6c2462d`).

**Impact Utilisateur**: Perte de contrôle de la pompe → risque glycémique immédiat  
**Fréquence**: Intermittente mais récurrente  
**Workaround actuel**: Redémarrage téléphone (inacceptable)

---

## 🔍 Analyse Détaillée de l'Architecture Medtrum

### 1. **Architecture Bluetooth Actuelle**

Le driver Medtrum utilise une architecture **Handler-based** avec callbacks BLE natifs Android:

```kotlin
// BLEComm.kt - Ligne 68-69
private val handler =
    Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
```

#### Composants Principaux

1. **BLEComm.kt** (`/pump/medtrum/services/BLEComm.kt`)
   - Gère la connexion Bluetooth de bas niveau
   - Utilise `BluetoothGatt` Android API
   - **HandlerThread** pour opérations asynchrones
   - **Callbacks** pour communication avec `MedtrumService`

2. **MedtrumService.kt** (`/pump/medtrum/services/MedtrumService.kt`)
   - Machine à états pour le flow de communication
   - États: `IdleState` → `AuthState` → `GetDeviceTypeState` → `GetTimeState` → `ReadyState` → `CommandState`
   - Implémente `BLECommCallback`

### 2. **Points de Défaillance Identifiés**

#### 🚨 **PROBLÈME #1: Gestion Non-Atomique de l'État Bluetooth**

**Fichier**: `BLEComm.kt` lignes 170-211

```kotlin
@Synchronized
fun disconnect(from: String) {
    // ...
    if (mBluetoothGatt != null) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connected/Connecting, disconnecting gatt")
        mBluetoothGatt?.disconnect()  // ⚠️ ASYNC - callback peut ne jamais arriver
        
        // Post a timeout to force close if onConnectionStateChange doesn't fire
        val timeoutRunnable = Runnable {
            synchronized(this) {
                if (mBluetoothGatt != null) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnect timeout reached, forcing close")
                    resetConnection("disconnect timeout")
                    isConnected = false
                    mCallback?.onBLEDisconnected()
                }
            }
        }
        pendingRunnables.add(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 2000) // 2 seconds timeout
    }
}
```

**Analyse**:
- `mBluetoothGatt?.disconnect()` est **asynchrone**
- Si `onConnectionStateChange()` ne se déclenche PAS (bug Android BLE connu), le timeout de 2s force un `close()`
- **MAIS**: Entre `disconnect()` et le timeout, l'objet `BluetoothGatt` reste dans un état limbo
- **Race condition**: Si une nouvelle tentative de connexion arrive avant le timeout, `connectGatt()` peut réutiliser un Gatt corrompu

#### 🚨 **PROBLÈME #2: Réutilisation Potentielle de Ressources Corrompues**

**Fichier**: `BLEComm.kt` lignes 158-168

```kotlin
private fun connectGatt(device: BluetoothDevice) {
    mWriteSequenceNumber = 0
    if (mBluetoothGatt != null) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "connectGatt: mBluetoothGatt is not null, closing previous connection")
        resetConnection("connectGatt")  // ⚠️ Appelle disconnect() puis close()
    }
    mBluetoothGatt = device.connectGatt(context, false, mGattCallback, BluetoothDevice.TRANSPORT_LE)
}
```

**Problème**: 
- `resetConnection()` appelle `disconnect()` + `close()` de manière **synchrone**
- Mais `close()` peut échouer silencieusement si le Gatt est dans un mauvais état
- La nouvelle connexion démarre **immédiatement** après, potentiellement sur des ressources BLE corrompues

#### 🚨 **PROBLÈME #3: Callbacks BLE Non-Contrôlés**

**Fichier**: `BLEComm.kt` lignes 274-354

```kotlin
private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        onConnectionStateChangeSynchronized(gatt, status, newState)
    }
    
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        // ...
    }
    
    // Autres callbacks...
}
```

**Analyse**:
- Ces callbacks sont invoqués par le **Binder thread Android**, pas par notre HandlerThread
- **Pas de gestion d'exceptions structurée** pour `CancellationException` ou exceptions Bluetooth
- Si un callback throw une exception non-catchée, le stack BLE Android peut entrer en état inconsistant

#### 🚨 **PROBLÈME #4: Machine à États Non-Résiliente**

**Fichier**: `MedtrumService.kt` lignes 859-875

```kotlin
fun waitForResponse(timeout: Long): Boolean {
    val startTime = System.currentTimeMillis()
    val timeoutMillis = T.secs(timeout).msecs()
    while (!responseHandled) {
        if (System.currentTimeMillis() - startTime > timeoutMillis) {
            aapsLogger.debug(LTag.PUMPCOMM, "Medtrum Service State timeout")
            disconnect("Timeout")  // ⚠️ Déconnexion sur timeout
            toState(IdleState())
            return false
        }
        SystemClock.sleep(25)  // ⚠️ BUSY WAIT - bloque le thread
    }
    return responseSuccess
}
```

**Problèmes**:
1. **Busy-wait**: `SystemClock.sleep(25)` dans une boucle **bloque le thread**
2. **Pas de coroutine cancellation**: Si le service/coroutine est annulé, cette boucle continue
3. **Disconnect synchrone**: En cas de timeout, appelle `disconnect()` qui peut lui-même bloquer

---

## 🔬 Comparaison avec le Fix Combo

### Architecture Combo (FONCTIONNE ✅)

Le driver Combo utilise **Kotlin Coroutines** avec gestion structurée des `CancellationException`:

**Fichier**: `/pump/combov2/comboctl/src/androidMain/kotlin/info/nightscout/comboctl/android/AndroidBluetoothDevice.kt`

```kotlin
// Gestion explicite des CancellationException
try {
    // Opération BLE
} catch (e: CancellationException) {
    // Propagation propre pour déconnecter la state machine
    throw e
}
```

**Avantages**:
1. **Structured Concurrency**: Annulation propre de toutes les opérations
2. **Exception Propagation**: `CancellationException` remonte correctement
3. **State Machine Cleanup**: La pompe se déconnecte proprement sans état zombie

### Architecture Medtrum (BUGUÉ ❌)

- **Callbacks + Handler**: Pas de gestion de cancellation
- **Busy-wait loops**: Bloquent les threads indéfiniment
- **Pas de catch CancellationException**: Exceptions silencieuses

---

## 🎯 Options de Résolution

### **OPTION 1: Refactoring Complet vers Coroutines** ⭐ RECOMMANDÉ
**Complexité**: Élevée (15-20h)  
**Impact**: Maximal  
**Risque**: Modéré (tests extensifs requis)

#### Plan d'Implémentation

1. **Remplacer `BLECommCallback` par `Flow`**
   ```kotlin
   // Au lieu de
   interface BLECommCallback {
       fun onBLEConnected()
       fun onBLEDisconnected()
   }
   
   // Utiliser
   class BLEComm {
       private val _connectionState = MutableStateFlow(BLEState.DISCONNECTED)
       val connectionState: StateFlow<BLEState> = _connectionState.asStateFlow()
   }
   ```

2. **Transformer callbacks BLE en suspending functions**
   ```kotlin
   suspend fun connectAndWaitForReady(): Result<Unit> = suspendCancellableCoroutine { continuation ->
       val callback = object : BluetoothGattCallback() {
           override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
               if (newState == BluetoothProfile.STATE_CONNECTED) {
                   continuation.resume(Result.success(Unit))
               } else if (status != BluetoothGatt.GATT_SUCCESS) {
                   continuation.resume(Result.failure(BLEException(status)))
               }
           }
       }
       
       continuation.invokeOnCancellation {
           try {
               mBluetoothGatt?.disconnect()
               mBluetoothGatt?.close()
           } catch (e: Exception) {
               // Log mais ne pas throw
           }
       }
       
       mBluetoothGatt = device.connectGatt(context, false, callback)
   }
   ```

3. **Remplacer machine à états par coroutines séquentielles**
   ```kotlin
   private suspend fun connectFlow(): Result<Unit> = coroutineScope {
       try {
           connectAndWaitForReady().getOrThrow()
           authorize().getOrThrow()
           getDeviceType().getOrThrow()
           synchronize().getOrThrow()
           subscribe().getOrThrow()
           Result.success(Unit)
       } catch (e: CancellationException) {
           disconnect("Cancelled")
           throw e // Propager pour cleanup
       } catch (e: Exception) {
           disconnect("Error: ${e.message}")
           Result.failure(e)
       }
   }
   ```

**Avantages**:
- ✅ Élimination complète des états zombies
- ✅ Gestion structurée de la cancellation
- ✅ Code plus lisible et maintenable
- ✅ Alignement avec architecture Combo

**Inconvénients**:
- ⚠️ Refactoring important
- ⚠️ Tests de régression nécessaires
- ⚠️ Risque de régression temporaire

---

### **OPTION 2: Fix Minimal - Gestion Forcée du Reset BLE** 
**Complexité**: Faible (2-4h)  
**Impact**: Modéré  
**Risque**: Faible

#### Implémentation

**1. Ajouter un hard-reset du BluetoothGatt**

```kotlin
// BLEComm.kt
@SuppressLint("MissingPermission")
@Synchronized
private fun forceResetBluetoothGatt() {
    aapsLogger.warn(LTag.PUMPBTCOMM, "Forcing BluetoothGatt hard reset")
    
    // Arrêter toutes les opérations en cours
    pendingRunnables.forEach { handler.removeCallbacks(it) }
    pendingRunnables.clear()
    
    // Fermeture brutale avec reflection pour vider le cache Android
    try {
        mBluetoothGatt?.let { gatt ->
            // 1. Disconnect
            gatt.disconnect()
            
            // 2. Attendre 100ms pour que disconnect() se propage
            Thread.sleep(100)
            
            // 3. Clear service cache via reflection (fix Android BLE bug)
            val clearCacheMethod = gatt.javaClass.getMethod("refresh")
            clearCacheMethod.invoke(gatt)
            
            // 4. Attendre 100ms pour que refresh() s'exécute
            Thread.sleep(100)
            
            // 5. Close final
            gatt.close()
        }
    } catch (e: Exception) {
        aapsLogger.error(LTag.PUMPBTCOMM, "Error during force reset: ${e.message}", e)
    } finally {
        mBluetoothGatt = null
        isConnected = false
        isConnecting = false
    }
}
```

**2. Utiliser le hard-reset dans les points critiques**

```kotlin
@Synchronized
fun disconnect(from: String) {
    aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
    
    if (isConnecting) {
        isConnecting = false
        stopScan()
    }

    if (mBluetoothGatt != null) {
        // Au lieu du timeout, faire un hard-reset immédiat après 2s
        val resetRunnable = Runnable {
            synchronized(this) {
                if (mBluetoothGatt != null) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnect callback not received, forcing hard reset")
                    forceResetBluetoothGatt()
                    mCallback?.onBLEDisconnected()
                }
            }
        }
        
        // Déclencher disconnect normal
        mBluetoothGatt?.disconnect()
        
        // Scheduler le hard-reset en backup
        pendingRunnables.add(resetRunnable)
        handler.postDelayed(resetRunnable, 2000)
    } else {
        resetConnection("disconnect null gatt")
        mCallback?.onBLEDisconnected()
    }
}
```

**3. Ajouter un watchdog pour détecter les états zombies**

```kotlin
// BLEComm.kt
private var lastActivityTimestamp = 0L
private val zombieDetectionRunnable = object : Runnable {
    override fun run() {
        synchronized(this@BLEComm) {
            if (isConnected && System.currentTimeMillis() - lastActivityTimestamp > 60_000) {
                aapsLogger.error(LTag.PUMPBTCOMM, "ZOMBIE STATE DETECTED - No BLE activity for 60s")
                forceResetBluetoothGatt()
                mCallback?.onBLEDisconnected()
            }
            handler.postDelayed(this, 30_000) // Check every 30s
        }
    }
}

// Appeler dans onCreate/connect
fun startZombieDetection() {
    handler.post(zombieDetectionRunnable)
}

// Mettre à jour lastActivityTimestamp dans tous les callbacks
override fun onCharacteristicChanged(...) {
    lastActivityTimestamp = System.currentTimeMillis()
    // ...
}
```

**Avantages**:
- ✅ Fix rapide et ciblé
- ✅ Risque minimal de régression
- ✅ Compatible avec architecture existante
- ✅ Détection proactive des zombies

**Inconvénients**:
- ⚠️ Ne résout pas la cause racine (architecture callback)
- ⚠️ Utilise reflection (peut casser sur futures versions Android)
- ⚠️ Reste un workaround, pas une solution élégante

---

### **OPTION 3: Hybrid - Timeout Agressif + Service Restart**
**Complexité**: Moyenne (6-8h)  
**Impact**: Bon  
**Risque**: Faible

#### Implémentation

**1. Réduire drastiquement les timeouts**

```kotlin
// BLEComm.kt
companion object {
    private const val DISCONNECT_TIMEOUT_MS = 500L  // Au lieu de 2000ms
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val GATT_OPERATION_TIMEOUT_MS = 3_000L
}
```

**2. Ajouter un mécanisme de restart du service**

```kotlin
// MedtrumService.kt
private var reconnectionAttempts = 0
private val MAX_RECONNECTION_ATTEMPTS = 3

fun handleZombieState() {
    if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
        aapsLogger.error(LTag.PUMPCOMM, "Max reconnection attempts reached, requesting service restart")
        
        // Notifier l'utilisateur
        uiInteraction.addNotificationWithSound(
            Notification.PUMP_UNREACHABLE,
            rh.gs(R.string.pump_unreachable_restart_service),
            Notification.URGENT,
            app.aaps.core.ui.R.raw.alarm
        )
        
        // Auto-restart du service
        scope.launch {
            delay(1000)
            restartService()
        }
    } else {
        reconnectionAttempts++
        bleComm.forceResetBluetoothGatt()
        connect("zombie recovery")
    }
}

private fun restartService() {
    stopSelf()
    context.startService(Intent(context, MedtrumService::class.java))
}
```

**Avantages**:
- ✅ Récupération automatique sans intervention utilisateur
- ✅ Timeouts agressifs limitent la période zombie
- ✅ Service restart nettoie complètement l'état

**Inconvénients**:
- ⚠️ Restart service = interruption de service temporaire
- ⚠️ Ne résout pas la cause racine

---

## 📊 Matrice de Décision

| Critère | Option 1 (Coroutines) | Option 2 (Hard Reset) | Option 3 (Hybrid) |
|---------|----------------------|----------------------|-------------------|
| **Temps de dev** | 15-20h | 2-4h | 6-8h |
| **Résolution cause racine** | ✅ Oui | ❌ Non | ⚠️ Partiel |
| **Risque de régression** | Modéré | Faible | Faible |
| **Maintenabilité** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| **Alignment Combo** | ✅ Oui | ❌ Non | ❌ Non |
| **Quick Win** | ❌ Non | ✅ Oui | ⚠️ Moyen |
| **Production Ready** | Post-tests | Immédiat | Court terme |

---

## 🎯 Recommandation Finale

### **Approche en 2 Phases** ⭐

#### **Phase 1 (Immédiate - J+2)**
Implémenter **Option 2** pour stabiliser en production:
1. Hard-reset avec reflection + cache clear
2. Watchdog zombie detection
3. Logs détaillés pour monitoring

**Livrable**: Patch de stabilité en 48h

#### **Phase 2 (Q1 2026 - Refactoring complet)**
Migrer vers **Option 1** (architecture Coroutines):
1. Refactoring BLEComm en suspending functions
2. Migration machine à états vers flow séquentiel
3. Tests de régression extensifs
4. Déploiement progressif (beta → stable)

**Livrable**: Architecture pérenne alignée avec Combo

### **Justification**

1. **Urgence**: Les utilisateurs ont besoin d'une solution **maintenant** → Option 2
2. **Qualité long-terme**: Architecture actuelle est fragile → Option 1 nécessaire
3. **Risque**: Phase 2 permet tests approfondis sans pression production

---

## 🔧 Next Steps Immédiats

### À faire dans les prochaines 24h:
1. ✅ Valider l'analyse avec @mtr
2. ⬜ Implémenter Option 2 (hard reset + watchdog)
3. ⬜ Ajouter logs détaillés BLE pour diagnostic
4. ⬜ Tester sur device réel avec déconnexions forcées
5. ⬜ Créer issue GitHub pour Phase 2 (refactoring coroutines)

### Logs à ajouter pour diagnostic:
```kotlin
// À chaque transition d'état BLE
aapsLogger.debug(LTag.PUMPBTCOMM, """
    BLE State Transition:
    - From: $oldState
    - To: $newState  
    - mBluetoothGatt: ${mBluetoothGatt != null}
    - isConnected: $isConnected
    - isConnecting: $isConnecting
    - pendingRunnables: ${pendingRunnables.size}
    - Thread: ${Thread.currentThread().name}
""")
```

---

## 📚 Références

1. **Android BLE Known Issues**: https://github.com/NordicSemiconductor/Android-BLE-Library/issues
2. **Combo Driver Fix**: Conversation `496e4c96-849f-4467-bae8-8b58f6c2462d`
3. **BluetoothGatt refresh() workaround**: https://stackoverflow.com/questions/22596951

---

**Document maintenu par**: Lyra  
**Dernière mise à jour**: 2025-12-21T17:18:31+01:00
