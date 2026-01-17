# 🤖 Android 14 (API 34) Compatibility Check

Audit de compatibilité pour le déploiement sur Pixel/Samsung récents.

## 1. Stockage (Scoped Storage)
Android 14 renforce l'isolation des fichiers. L'accès raw `File("/sdcard")` est mort pour les apps targeting SDK 30+ sans flag spécial.

*   **Code Actuel (`AimiStorageHelper`)**:
    *   Tente `Documents/AAPS`.
    *   Fallback `getExternalFilesDir` (Private).
    *   Fallback `filesDir` (Internal).
*   **Verdict**: ✅ **SAFE**. L'app ne crashera pas.
*   **Attention**: Si le fallback s'active, l'utilisateur perd l'accès facile aux logs CSV.
*   **Recommandation**: Implémenter un `FileProvider` pour permettre à l'utilisateur d'exporter ses logs/apprentissage via le menu "Partager".

## 2. Bluetooth (Omnipod Dash)
Android 12+ a introduit `BLUETOOTH_CONNECT` et `BLUETOOTH_SCAN`. Android 14 modifie la pile BT.

*   **Manifest**: Vérifier présence de:
    ```xml
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    ```
*   **Code Driver (`OmnipodDashBleManager`)**:
    *   Gère le bonding explicite (`createBond`).
    *   Utilise `checkSelfPermission` avant appel API.
*   **Verdict**: ✅ **Conforme**. Le warning utilisateur est une précaution valide due aux bugs firmware de certains téléphones sur Android 14, pas un bug de code AAPS.

## 3. Background Work (WorkManager)
Android 14 restreint les `ExactAlarm` et les services foreground lancés depuis le background.

*   **Plugin Physio (`AIMIPhysioManager`)**:
    *   Utilise `WorkManager` (Periodic). C'est la bonne pratique.
    *   **Risque**: Si l'app est tuée par l'OS ("Phantom Process Killer"), le WorkManager peut tarder.
*   **AAPS Core**: Doit avoir le privilège `SCHEDULE_EXACT_ALARM` pour la boucle de 5 min. (Déjà le cas standards AAPS).

## 4. Notifications & Foreground Types
Si `AIMIPhysioWorker` lance une notification, il doit déclarer un `foregroundServiceType` (ex: `health` ou `dataSync`).

*   **Action**: Vérifier le Manifest pour le service du Worker.
*   **Code**: Si `startForeground()` est appelé, passer le type bitmask (API 34 requirement).

## 5. Broadcast Receivers
Les receivers enregistrés dynamiquement doivent spécifier `RECEIVER_EXPORTED` ou `RECEIVER_NOT_EXPORTED`.

*   **Action**: Scanner le code pour `registerReceiver`. Si présent sans flag -> Crash sur Android 14.

---

## ✅ Checklist Avant Release

- [ ] Tester install propre sur Android 14 (Pixel 8/S24).
- [ ] Vérifier que les permissions "Alarms & Reminders" sont demandées.
- [ ] Vérifier que le dossier `Documents/AAPS` est bien créé (si permission All Files accordée).
- [ ] Vérifier absence de crash `SecurityException` lors du scan Bluetooth Dash.
