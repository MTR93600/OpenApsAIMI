# 🚨 DAGGER CROSS-MODULE INJECTION ISSUE

## Problem

`DashboardFragment` (in `plugins:main`) cannot `@Inject` classes from `plugins:aps`:
- `AuditorStatusLiveData`
- `AuditorNotificationManager` 
- `AuditorStatusIndicator`

KSP Error: PROCESSING_ERROR

## Root Cause

Dagger modules are scoped per Gradle module. Classes in `plugins:aps` are not exposed to `plugins:main`'s Dagger graph.

## Solutions

### Option 1: Manual Instantiation (Quick Fix)
Remove `@Inject` and create instances manually in `onViewCreated()`:

```kotlin
private lateinit var auditorStatusLiveData: AuditorStatusLiveData
private lateinit var auditorNotificationManager: AuditorNotificationManager

override fun onViewCreated(...) {
    // Manual init
    auditorStatusLiveData = AuditorStatusLiveData(...)
    auditorNotificationManager = AuditorNotificationManager(...)
    
    setupAuditorIndicator()
}
```

### Option 2: Get from ActivePlugin (Recommended)
Use `activePlugin.activeAPS` cast to access:

```kotlin
val auditSystem = (activePlugin.activeAPS as? OpenAPSAIMIPlugin)?.auditor
```

### Option 3: Add to Dagger Module
Create a `@Provides` in `plugins:aps` module that's accessible cross-module.

---

**Recommendation**: Try Option 2 first (activePlugin), fallback to Option 1 if needed.
