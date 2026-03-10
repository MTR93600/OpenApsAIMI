# 🔥 PLAN B - Si Kotlin cache persiste
## 2026-01-09 12:05

---

## 🎯 **SITUATION**

Le cache Kotlin daemon persiste malgré :
- ✅ Invalidate Caches Android Studio (fait par MTR)
- ✅ `./gradlew clean`
- ✅ `pkill -9 kotlin.*daemon`

**Root cause** : Le compiler Kotlin a un cache **TRÈS** résistant qui stocke les métadonnées des data classes.

---

## 💡 **PLAN B : Approche minimaliste**

**Au lieu de lutter contre le cache**, on va **travailler AVEC** :

### **Option 1 : Utiliser un wrapper temporaire**

Créer une nouvelle data class `CircleTopState` qui WRAP `StatusCardState` :

```kotlin
data class CircleTopState(
    val bg: String,
    val delta: String,
    val time: String,
    // ... etc
) {
    companion object {
        fun from(state: StatusCardState) = CircleTopState(
            bg = state.glucoseText,
            delta = state.deltaText,
            time = state.timeAgo,
            // ... map all fields
        )
    }
}
```

**Avantage** : Pas de dépendance au cache de `StatusCardState`  
**Inconvénient** : Code dupliqué

---

### **Option 2 : Utiliser Reflection**

Dans `CircleTopStatusHybridView`, utiliser la réflexion pour accéder aux propriétés :

```kotlin
fun update(state: Any) {  // Accept Any instead of StatusCardState
    val stateClass = state::class.java
    val glucoseText = stateClass.getMethod("getGlucoseText").invoke(state) as String
    binding.glucoseRing.update(glucoseText = glucoseText, ...)
}
```

**Avantage** : Bypass complet du cache
**Inconvénient** : Moins performant, moins type-safe

---

### **Option 3 : Compilation séparée**

Compiler le ViewModel dans UN module séparé, puis le View dans un autre :

```bash
# 1. Compile ONLY ViewModel
./gradlew :plugins:main:kspFullDebugKotlin
./gradlew :plugins:main:compileFullDebugKotlin --parallel=false

# 2. Wait for daemon refresh
sleep 5

# 3. Compile View (will see fresh metadata)
./gradlew :plugins:main:assembleFullDebug
```

**Avantage** : Force le compiler à rafraîchir  
**Inconvénient** : Plus long

---

## 🚀 **PLAN B SIMPLE : Desactivate CircleTopStatusHybridView**

**Si rien ne marche**, on peut :

1. ✅ Garder tout le code ViewModel (qui compile OK)
2. ✅ Garder le layout XML
3. ⚠️ Intégrer le layout **DIRECTEMENT** dans DashboardFragment sans passer par CircleTopStatusHybridView

```kotlin
// Dans DashboardFragment.kt
val circleTopBinding = ComponentCircleTopStatusHybridBinding.inflate(...)

viewModel.statusCardState.observe(viewLifecycleOwner) { state ->
    // Bind manually
    circleTopBinding.glucoseRing.update(
        bgMgdl = state.glucoseMgdl,
        mainText = state.glucoseText,
        ...
    )
    // ... etc
}
```

**Avantage** : Bypass complet de CircleTopStatusHybridView
**Inconvénient** : Code moins propre, mais FONCTIONNEL

---

## 💬 **RECOMMANDATION**

**1. Attendre le résultat du build en cours** (avec Kotlin daemon killed)

**2. Si ça échoue encore** → **PLAN B Simple** (intégration directe)

**3. Documenter le bug Kotlin** pour fix ultérieur

---

**Date** : 2026-01-09 12:05  
**Status** : Waiting for build result  
**Fallback** : Direct binding in DashboardFragment
