# Plan de Refactor Complet : Medtrum → Kotlin Coroutines

**Objectif** : Migrer le driver Medtrum d'une architecture Callbacks+Handler vers Kotlin Coroutines (comme Combo)

**Décision** : @mtr a validé le refactor complet pour une solution durable

**Date de début** : 2025-12-21T18:06+01:00

---

## 🎯 Objectifs du Refactor

### Technique
- ✅ Éliminer tous les callbacks BLE directs → `suspendCancellableCoroutine`
- ✅ Remplacer `Handler` + `HandlerThread` → `CoroutineScope` + `Dispatchers`
- ✅ Remplacer variables d'état Boolean → `StateFlow<ConnectionState>`
- ✅ Éliminer busy-wait loops → `suspend fun` avec `withTimeout`
- ✅ Ajouter gestion structurée de `CancellationException`

### Fonctionnel
- ✅ Maintenir 100% des fonctionnalités existantes
- ✅ Compatibility avec `MedtrumPlugin` existant
- ✅ Pas de changement dans l'API publique du service

---

## 📐 Architecture Cible

### Avant (Callbacks)
```
MedtrumService (Machine à états)
    ↓ callbacks
BLEComm (Handler + BluetoothGattCallback)
    ↓ callbacks Android
BluetoothGatt (Android API)
```

### Après (Coroutines)
```
MedtrumService (Sequential Flow)
    ↓ suspend calls
BLEConnection (Coroutines Wrapper)
    ↓ suspendCancellableCoroutine
BluetoothGatt (Android API)
```

---

## 🔧 Étapes d'Implémentation

### **Étape 1 : Créer BLEConnection (Nouveau Fichier)** ✅

**Fichier** : `/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/BLEConnection.kt`

**Responsabilités** :
- Wrapper coroutines autour de `BluetoothGatt`
- `suspend fun connect()` au lieu de callback
- `StateFlow<BLEState>` pour état
- `Channel` pour notifications/indications
- Gestion cleanup avec `CancellationException`

**Code Pattern** (inspiré Combo) :
```kotlin
sealed class BLEState {
    object Disconnected : BLEState()
    object Connecting : BLEState()
    data class Connected(val gatt: BluetoothGatt) : BLEState()
    data class Error(val reason: String) : BLEState()
}

class BLEConnection(
    private val context: Context,
    private val deviceSN: Long,
    scope: CoroutineScope
) : Closeable {
    private val _state = MutableStateFlow<BLEState>(BLEState.Disconnected)
    val state: StateFlow<BLEState> = _state.asStateFlow()
    
    private val notificationChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val indicationChannel = Channel<ByteArray>(Channel.UNLIMITED)
    
    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun sendCommand(data: ByteArray): Result<ByteArray>
    
    override fun close()
}
```

---

### **Étape 2 : Refactorer BLEComm.kt** ✅

**Transformation** :
- Conserver pour backward compatibility temporaire
- Wrapper autour de `BLEConnection`
- OU : Supprimer complètement et migrer users vers `BLEConnection`

**Décision** : Je vais créer `BLEConnection` et adapter `BLEComm` pour l'utiliser

---

### **Étape 3 : Transformer Machine à États → Flow Séquentiel** ✅

**Dans MedtrumService.kt** :

**AVANT** (Machine à états) :
```kotlin
private var currentState: State = IdleState()

fun connect() {
    toState(AuthState())
}

private inner class AuthState : State() {
    override fun onEnter() {
        mPacket = AuthorizePacket(...)
        bleComm.sendMessage(...)
    }
    override fun onIndication(data: ByteArray) {
        if (success) toState(GetDeviceTypeState())
    }
}
```

**APRÈS** (Flow séquentiel) :
```kotlin
private suspend fun connectFlow(): Result<Unit> = coroutineScope {
    try {
        bleConnection.connect().getOrThrow()
        authorize().getOrThrow()
        getDeviceType().getOrThrow()
        getTime().getOrThrow()
        synchronize().getOrThrow()
        subscribe().getOrThrow()
        Result.success(Unit)
    } catch (e: CancellationException) {
        disconnect("Cancelled")
        throw e
    } catch (e: Exception) {
        disconnect("Error: ${e.message}")
        Result.failure(e)
    }
}

private suspend fun authorize(): Result<Unit> {
    val packet = AuthorizePacket(...)
    return bleConnection.sendCommand(packet.getRequest())
        .mapCatching { response ->
            packet.handleResponse(response)
            if (packet.failed) throw Exception("Auth failed")
        }
}
```

---

### **Étape 4 : Éliminer Busy-Wait** ✅

**AVANT** :
```kotlin
fun waitForResponse(timeout: Long): Boolean {
    while (!responseHandled) {
        if (timeout) return false
        SystemClock.sleep(25)  // BUSY WAIT
    }
    return responseSuccess
}
```

**APRÈS** :
```kotlin
suspend fun waitForResponse(timeout: Long): Result<ByteArray> {
    return withTimeout(timeout.seconds) {
        indicationChannel.receive()
    }
}
```

---

### **Étape 5 : Ajouter Gestion CancellationException Partout** ✅

**Pattern à appliquer** :
```kotlin
try {
    // Operation
} catch (e: CancellationException) {
    aapsLogger.debug("Operation cancelled")
    cleanup()
    throw e  // TOUJOURS re-throw
} catch (e: Exception) {
    aapsLogger.error("Operation failed", e)
    throw e
}
```

---

### **Étape 6 : Migrer Callbacks vers Suspend Functions** ✅

**AVANT** :
```kotlin
interface BLECommCallback {
    fun onBLEConnected()
    fun onBLEDisconnected()
    fun onIndication(data: ByteArray)
}
```

**APRÈS** :
```kotlin
// Pas de callback - utiliser StateFlow + Channel
scope.launch {
    bleConnection.state.collect { state ->
        when (state) {
            is BLEState.Connected -> handleConnected()
            is BLEState.Disconnected -> handleDisconnected()
        }
    }
}

scope.launch {
    bleConnection.indications.collect { data ->
        handleIndication(data)
    }
}
```

---

## 📂 Fichiers à Créer/Modifier

### **Nouveaux Fichiers**

1. **`/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/BLEConnection.kt`**
   - Classe principale wrapper Coroutines
   - ~400 lignes

2. **`/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/BLEState.kt`**
   - Sealed class pour états
   - ~20 lignes

### **Fichiers à Modifier**

1. **`/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/BLEComm.kt`**
   - Adapter pour utiliser `BLEConnection` en backend
   - OU marquer `@Deprecated` et migrer users
   - ~200 lignes modifiées

2. **`/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/MedtrumService.kt`**
   - Refactor machine à états → flow séquentiel
   - Remplacer `waitForResponse()` par suspend
   - ~500 lignes modifiées

3. **`/pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/di/MedtrumCommModule.kt`**
   - Ajouter injection de `BLEConnection`
   - ~10 lignes

---

## ⚠️ Points d'Attention

### **Threading**
- Tout sur `Dispatchers.IO` pour opérations BLE
- UI updates via `withContext(Dispatchers.Main)`

### **Cancellation**
- Tous les scopes doivent être `supervisorScope` pour isoler failures
- `invokeOnCancellation` dans `suspendCancellableCoroutine` pour cleanup

### **Backward Compatibility**
- `MedtrumPlugin` ne doit PAS changer d'API
- Tests existants doivent passer (si existants)

### **Performance**
- Channel buffer size = `Channel.UNLIMITED` pour notifications
- `conflate()` pour StateFlow si trop rapide

---

## 🧪 Plan de Tests

### **Tests Unitaires** (À créer)
```kotlin
class BLEConnectionTest {
    @Test
    fun `connect then disconnect should cleanup properly`()
    
    @Test
    fun `cancellation during connect should cleanup`()
    
    @Test
    fun `sendCommand timeout should fail gracefully`()
}
```

### **Tests d'Intégration**
1. Connect → Disconnect cycle
2. Connect → Cancel → Reconnect
3. Multiple rapid connect/disconnect
4. Timeout scenarios

### **Tests Device Réels** (@mtr)
1. Connection normale
2. Mode avion pendant communication
3. Déconnexions forcées
4. Stress test 24h

---

## 📊 Timeline Estimée

| Étape | Durée | Cumul |
|-------|-------|-------|
| 1. BLEConnection.kt | 2h | 2h |
| 2. BLEState.kt | 15min | 2h15 |
| 3. Adapter BLEComm.kt | 1h | 3h15 |
| 4. Refactor MedtrumService.kt | 3h | 6h15 |
| 5. Gestion CancellationException | 1h | 7h15 |
| 6. Dependency Injection | 30min | 7h45 |
| 7. Tests unitaires | 2h | 9h45 |
| 8. Compilation & fix errors | 1h | 10h45 |
| 9. Documentation code | 1h | 11h45 |
| **TOTAL DEV** | | **~12h** |
| Tests device (@mtr) | 8h | |
| **TOTAL** | | **~20h** |

---

## ✅ Checklist de Completion

### Phase Dev
- [ ] BLEConnection.kt créé et compilé
- [ ] BLEState.kt créé
- [ ] BLEComm.kt adapté
- [ ] MedtrumService.kt refactoré
- [ ] CancellationException handling partout
- [ ] Dependency injection configuré
- [ ] Busy-wait loops éliminés
- [ ] Tests unitaires créés
- [ ] Compilation sans erreurs
- [ ] Documentation code à jour

### Phase Tests (@mtr)
- [ ] Connection normale fonctionne
- [ ] Disconnection propre
- [ ] Reconnection après erreur
- [ ] Mode avion test
- [ ] Stress test 24h
- [ ] Aucune régression fonctionnelle

---

## 🚀 Démarrage Immédiat

Je commence maintenant par l'**Étape 1 : BLEConnection.kt**.

**Status** : 🟢 EN COURS

**Prochaine update** : Après création de `BLEConnection.kt` (~2h)

---

**Auteur** : Lyra  
**Date** : 2025-12-21T18:06+01:00  
**Approuvé par** : @mtr  
**Type** : Refactor majeur - Solution durable
