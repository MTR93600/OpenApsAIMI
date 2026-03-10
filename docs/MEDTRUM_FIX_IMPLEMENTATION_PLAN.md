# Plan d'Implémentation - Fix Déconnexions Medtrum (Option 2)

**Objectif**: Stabiliser le driver Medtrum en 48h avec un hard-reset BLE et détection d'états zombies

---

## 📋 Checklist d'Implémentation

### **Étape 1: Méthode de Hard Reset BLE** (30 min)

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`

#### 1.1 Ajouter la méthode `forceResetBluetoothGatt()`

**Insérer après la ligne 247** (après `resetConnection()`):

```kotlin
/**
 * Forcefully reset the BluetoothGatt connection with aggressive cleanup.
 * This method uses Android BLE cache refresh to clear any stale state.
 * 
 * WARNING: Uses reflection - may break on future Android versions
 */
@SuppressLint("MissingPermission")
@Synchronized
private fun forceResetBluetoothGatt() {
    aapsLogger.warn(LTag.PUMPBTCOMM, "=== FORCE RESET BLUETOOTH GATT START ===")
    
    val gattToClose = mBluetoothGatt
    
    try {
        // Step 1: Stop all pending operations
        pendingRunnables.forEach { handler.removeCallbacks(it) }
        pendingRunnables.clear()
        stopScan()
        
        if (gattToClose != null) {
            try {
                // Step 2: Disconnect
                aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: calling disconnect()")
                gattToClose.disconnect()
                
                // Step 3: Wait for disconnect to propagate (Android BLE quirk)
                Thread.sleep(150)
                
                // Step 4: Refresh GATT cache using reflection
                // This clears Android's internal BLE cache which can get corrupted
                try {
                    val refreshMethod = gattToClose.javaClass.getMethod("refresh")
                    val refreshResult = refreshMethod.invoke(gattToClose) as? Boolean
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: GATT cache refresh result: $refreshResult")
                    
                    // Wait for cache clear to complete
                    Thread.sleep(150)
                } catch (e: Exception) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Force reset: Failed to refresh GATT cache", e)
                    // Continue anyway - close() might still help
                }
                
                // Step 5: Final close
                aapsLogger.debug(LTag.PUMPBTCOMM, "Force reset: calling close()")
                gattToClose.close()
                
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Force reset: Exception during cleanup", e)
            }
        }
        
    } finally {
        // Step 6: Clear all internal state
        mBluetoothGatt = null
        mWritePackets = null
        mReadPacket = null
        uartWrite = null
        uartRead = null
        isConnected = false
        isConnecting = false
        
        aapsLogger.warn(LTag.PUMPBTCOMM, "=== FORCE RESET BLUETOOTH GATT COMPLETE ===")
    }
}
```

**Lignes à modifier**: Insérer après ligne 247

---

### **Étape 2: Modifier la méthode `disconnect()`** (20 min)

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`

#### 2.1 Remplacer la méthode `disconnect()` existante (lignes 170-211)

**AVANT** (à supprimer):
```kotlin
@SuppressLint("MissingPermission")
@Synchronized
fun disconnect(from: String) {
    // ... code actuel ...
}
```

**APRÈS** (nouveau code):
```kotlin
@SuppressLint("MissingPermission")
@Synchronized
fun disconnect(from: String) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
        return
    }
    aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
    
    // Stop scanning if we were connecting
    if (isConnecting) {
        isConnecting = false
        stopScan()
    }

    // Clear any pending operations
    pendingRunnables.forEach { handler.removeCallbacks(it) }
    pendingRunnables.clear()

    if (mBluetoothGatt != null) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connected/Connecting, initiating disconnect with force reset backup")
        
        // Schedule force reset as backup if normal disconnect callback doesn't fire
        val forceResetRunnable = Runnable {
            synchronized(this) {
                if (mBluetoothGatt != null) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Normal disconnect callback not received within timeout, executing force reset")
                    forceResetBluetoothGatt()
                    mCallback?.onBLEDisconnected()
                }
            }
        }
        pendingRunnables.add(forceResetRunnable)
        handler.postDelayed(forceResetRunnable, 1500) // Reduced from 2000ms to 1500ms
        
        // Initiate normal disconnect
        try {
            mBluetoothGatt?.disconnect()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Exception during disconnect(), triggering immediate force reset", e)
            // If disconnect() itself throws, immediately force reset
            handler.removeCallbacks(forceResetRunnable)
            forceResetBluetoothGatt()
            mCallback?.onBLEDisconnected()
        }
    } else {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt is null, ensuring closed state")
        resetConnection("disconnect null gatt")
        isConnected = false
        mCallback?.onBLEDisconnected()
    }
}
```

**Lignes à modifier**: 170-211

---

### **Étape 3: Modifier `onConnectionStateChangeSynchronized()`** (15 min)

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`

#### 3.1 Améliorer la gestion des erreurs (lignes 419-446)

**Modifier la méthode**:

```kotlin
@SuppressLint("MissingPermission")
@Synchronized
private fun onConnectionStateChangeSynchronized(gatt: BluetoothGatt, status: Int, newState: Int) {
    aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange newState: $newState status: $status")

    // Clear pending disconnect/reset runnables since callback fired
    pendingRunnables.forEach { handler.removeCallbacks(it) }
    pendingRunnables.clear()

    if (status != BluetoothGatt.GATT_SUCCESS) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "onConnectionStateChange error status: $status")
        
        // Use force reset on error status to ensure clean state
        forceResetBluetoothGatt()
        mCallback?.onBLEDisconnected()
        return
    }

    if (newState == BluetoothProfile.STATE_CONNECTED) {
        isConnected = true
        isConnecting = false
        aapsLogger.debug(LTag.PUMPBTCOMM, "BLE Connected, discovering services")
        mBluetoothGatt?.discoverServices()
    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        if (isConnecting) {
            val resetDevice = preferences.get(MedtrumBooleanKey.MedtrumScanOnConnectionErrors)
            if (resetDevice) {
                // When we are disconnected during connecting, we reset the device address to force a new scan
                aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnected while connecting! Reset device address")
                mDeviceAddress = null
            }
        }
        
        // Use force reset for clean disconnection
        forceResetBluetoothGatt()
        mCallback?.onBLEDisconnected()
        aapsLogger.debug(LTag.PUMPBTCOMM, "Device was disconnected " + gatt.device.name)
    }
}
```

**Lignes à modifier**: 419-446

---

### **Étape 4: Watchdog pour Détection d'États Zombies** (45 min)

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`

#### 4.1 Ajouter les variables membres (après ligne 87)

```kotlin
// Zombie state detection
private var lastBLEActivityTimestamp = 0L
private val ZOMBIE_DETECTION_INTERVAL_MS = 30_000L  // Check every 30s
private val ZOMBIE_THRESHOLD_MS = 90_000L           // 90s without activity = zombie
private var zombieCheckRunnable: Runnable? = null
```

#### 4.2 Créer le Runnable de détection zombie (après ligne 91)

```kotlin
private fun createZombieCheckRunnable() = object : Runnable {
    override fun run() {
        synchronized(this@BLEComm) {
            val now = System.currentTimeMillis()
            val timeSinceLastActivity = now - lastBLEActivityTimestamp
            
            if (isConnected) {
                aapsLogger.debug(
                    LTag.PUMPBTCOMM,
                    "Zombie check: last activity ${timeSinceLastActivity}ms ago, threshold ${ZOMBIE_THRESHOLD_MS}ms"
                )
                
                if (timeSinceLastActivity > ZOMBIE_THRESHOLD_MS) {
                    aapsLogger.error(
                        LTag.PUMPBTCOMM,
                        "🧟 ZOMBIE STATE DETECTED - No BLE activity for ${timeSinceLastActivity}ms (threshold: ${ZOMBIE_THRESHOLD_MS}ms)"
                    )
                    
                    // Force reset and notify
                    forceResetBluetoothGatt()
                    mCallback?.onBLEDisconnected()
                } else if (timeSinceLastActivity > ZOMBIE_THRESHOLD_MS / 2) {
                    // Warning at 50% threshold
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "⚠️ BLE communication slow: ${timeSinceLastActivity}ms since last activity"
                    )
                }
            }
            
            // Reschedule check
            handler.postDelayed(this, ZOMBIE_DETECTION_INTERVAL_MS)
        }
    }
}
```

#### 4.3 Démarrer/Arrêter le watchdog

**Modifier `connect()` (ligne 123)**:

```kotlin
@Synchronized
fun connect(from: String, deviceSN: Long): Boolean {
    // ... code existant ...
    
    // Start zombie detection
    if (zombieCheckRunnable == null) {
        zombieCheckRunnable = createZombieCheckRunnable()
        handler.post(zombieCheckRunnable!!)
    }
    lastBLEActivityTimestamp = System.currentTimeMillis()
    
    // ... reste du code ...
}
```

**Modifier `forceResetBluetoothGatt()` pour arrêter le watchdog**:

```kotlin
// Dans finally block de forceResetBluetoothGatt()
zombieCheckRunnable?.let { handler.removeCallbacks(it) }
zombieCheckRunnable = null
```

#### 4.4 Mettre à jour timestamp dans TOUS les callbacks

**À ajouter au début de chaque callback BLE** (lignes 287-353):

```kotlin
// Dans onCharacteristicRead
override fun onCharacteristicRead(...) {
    lastBLEActivityTimestamp = System.currentTimeMillis()
    // ... code existant ...
}

// Dans onCharacteristicChanged
override fun onCharacteristicChanged(...) {
    lastBLEActivityTimestamp = System.currentTimeMillis()
    // ... code existant ...
}

// Dans onCharacteristicWrite
override fun onCharacteristicWrite(...) {
    lastBLEActivityTimestamp = System.currentTimeMillis()
    // ... code existant ...
}

// Dans onDescriptorWrite
override fun onDescriptorWrite(...) {
    lastBLEActivityTimestamp = System.currentTimeMillis()
    // ... code existant ...
}

// Dans onDescriptorRead
override fun onDescriptorRead(...) {
    lastBLEActivityTimestamp = System.currentTimeMillis()
    // ... code existant ...
}
```

---

### **Étape 5: Logs Détaillés pour Diagnostic** (30 min)

#### 5.1 Ajouter une méthode helper de logging

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`

```kotlin
/**
 * Log detailed BLE state for debugging zombie states
 */
private fun logBLEState(context: String) {
    aapsLogger.debug(LTag.PUMPBTCOMM, """
        === BLE State Debug [$context] ===
        - mBluetoothGatt: ${if (mBluetoothGatt != null) "NOT NULL" else "NULL"}
        - isConnected: $isConnected
        - isConnecting: $isConnecting
        - pendingRunnables: ${pendingRunnables.size}
        - mWritePackets: ${if (mWritePackets != null) "present (consumed=${mWritePackets?.allPacketsConsumed()})" else "null"}
        - mReadPacket: ${if (mReadPacket != null) "present" else "null"}
        - uartWrite: ${if (uartWrite != null) "configured" else "null"}
        - uartRead: ${if (uartRead != null) "configured" else "null"}
        - lastActivity: ${System.currentTimeMillis() - lastBLEActivityTimestamp}ms ago
        - thread: ${Thread.currentThread().name}
        ================================
    """.trimIndent())
}
```

#### 5.2 Appeler `logBLEState()` aux points critiques

```kotlin
// Dans connect()
fun connect(from: String, deviceSN: Long): Boolean {
    logBLEState("connect called from $from")
    // ... code existant ...
}

// Dans disconnect()
fun disconnect(from: String) {
    logBLEState("disconnect called from $from")
    // ... code existant ...
}

// Dans onConnectionStateChangeSynchronized()
private fun onConnectionStateChangeSynchronized(...) {
    logBLEState("onConnectionStateChange status=$status newState=$newState")
    // ... code existant ...
}

// Dans forceResetBluetoothGatt() - début et fin
private fun forceResetBluetoothGatt() {
    logBLEState("BEFORE force reset")
    // ... code reset ...
    logBLEState("AFTER force reset")
}
```

---

### **Étape 6: Notification Utilisateur en Cas de Zombie** (20 min)

**Fichier**: `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/MedtrumService.kt`

#### 6.1 Ajouter une gestion des états zombies détectés

**Modifier `onBLEDisconnected()` (ligne 775-778)**:

```kotlin
override fun onBLEDisconnected() {
    aapsLogger.debug(LTag.PUMPCOMM, "<<<<< onBLEDisconnected")
    
    // Check if disconnection happened in a bad state (potential zombie)
    if (currentState !is IdleState && medtrumPump.connectionState == ConnectionState.CONNECTED) {
        aapsLogger.error(LTag.PUMPCOMM, "Unexpected disconnection detected - may have been zombie state")
        
        // Notify user
        uiInteraction.addNotification(
            Notification.PUMP_UNREACHABLE,
            rh.gs(R.string.medtrum_pump_reconnecting_after_error),
            Notification.NORMAL
        )
    }
    
    currentState.onDisconnected()
}
```

#### 6.2 Ajouter la string de notification

**Fichier**: `/pump/medtrum/src/main/res/values/strings.xml`

```xml
<string name="medtrum_pump_reconnecting_after_error">Medtrum pump was disconnected unexpectedly. Attempting to reconnect...</string>
```

---

## 🧪 Tests à Effectuer

### Test 1: Déconnexion Forcée
1. Connecter la pompe
2. Mettre le téléphone en mode avion pendant la communication
3. **Attendre**: Observer les logs pour le timeout force reset (1.5s)
4. **Vérifier**: `forceResetBluetoothGatt()` est appelé
5. Désactiver mode avion
6. **Vérifier**: Reconnexion automatique fonctionne

### Test 2: Zombie Detection
1. Connecter la pompe
2. Simuler interruption réseau pendant 90s (pas de packets BLE)
3. **Vérifier**: Watchdog détecte zombie à 90s
4. **Vérifier**: Logs montrent "ZOMBIE STATE DETECTED"
5. **Vérifier**: Force reset est déclenché

### Test 3: Reconnexion Rapide
1. Déconnecter/reconnecter rapidement 5 fois
2. **Vérifier**: Pas de leak de BluetoothGatt
3. **Vérifier**: Logs montrent force resets propres

### Test 4: Stress Test
1. Laisser la pompe connectée pendant 24h
2. **Vérifier**: Pas de zombie (watchdog reste silencieux)
3. **Vérifier**: Communication stable

---

## 📊 Métriques de Succès

- ✅ Aucun redémarrage téléphone nécessaire après 48h
- ✅ Force reset se déclenche dans 100% des cas de timeout
- ✅ Zombie détecté en <90s dans 100% des cas simulés
- ✅ Reconnexion automatique fonctionne après force reset

---

## ⚠️ Risques et Mitigations

### Risque 1: Reflection `refresh()` peut échouer sur Android 15+
**Mitigation**: Wrapped dans try-catch, continue avec `close()` si échec

### Risque 2: Thread.sleep() peut bloquer temporairement
**Mitigation**: Durées minimales (150ms), méthode synchronized

### Risque 3: Force reset trop agressif
**Mitigation**: Seulement déclenché si timeout > 1.5s ou zombie > 90s

---

## 🚀 Timeline

| Tâche | Durée | Début | Fin |
|-------|-------|-------|-----|
| Étape 1: forceResetBluetoothGatt() | 30min | J1 09:00 | J1 09:30 |
| Étape 2: Modifier disconnect() | 20min | J1 09:30 | J1 09:50 |
| Étape 3: Modifier onConnectionStateChange | 15min | J1 09:50 | J1 10:05 |
| **PAUSE CAFÉ** | 10min | J1 10:05 | J1 10:15 |
| Étape 4: Watchdog zombie | 45min | J1 10:15 | J1 11:00 |
| Étape 5: Logs détaillés | 30min | J1 11:00 | J1 11:30 |
| Étape 6: Notification utilisateur | 20min | J1 11:30 | J1 11:50 |
| **Test Phase 1** | 2h | J1 14:00 | J1 16:00 |
| **Test Phase 2 (device réel)** | 4h | J2 09:00 | J2 13:00 |
| **Review & Polish** | 2h | J2 14:00 | J2 16:00 |

**Total**: ~10h sur 2 jours

---

## 📝 Checklist Avant Commit

- [ ] Tous les fichiers modifiés compilent sans erreur
- [ ] Tests unitaires passent (si existants)
- [ ] Device réel testé avec déconnexions forcées
- [ ] Logs vérifiés sur 24h de fonctionnement
- [ ] Documentation mise à jour (MEDTRUM_DISCONNECTION_ANALYSIS.md)
- [ ] Commit message descriptif avec référence à l'analyse
- [ ] Branch créée: `fix/medtrum-zombie-state-detection`

---

**Document créé par**: Lyra  
**Date**: 2025-12-21  
**Version**: 1.0
