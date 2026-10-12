---
name: aps-screen-test-harness
description: How to render a full :plugins:aps AIMI screen under Robolectric - the LocalPreferences trap, and JsonBackedPreferences + a stub AimiStorage as a ready-made default-valued Preferences
metadata:
  type: reference
---

Rendering a whole AIMI screen in `plugins/aps/src/androidHostTest` needs three things that are not
obvious.

**1. Provide `LocalPreferences` *outside* the screen, or nothing renders.**
`app.aaps.core.ui.compose.LocalPreferences` is `compositionLocalOf { error(...) }`, and
`ProvidePreferenceTheme` reads it. A screen that calls `ProvidePreferenceTheme` and only *then*
provides `LocalPreferences` still needs the local from its host - which is what the production
preference-screen shell does. So the test must wrap:

```
CompositionLocalProvider(LocalPreferences provides preferences) { MaterialTheme { Screen(...) } }
```

Without it every test dies with "No Preferences provided" inside `AapsTheme.kt`, and the stack
points at `ProvidePreferenceTheme`, not at your test.

**2. `JsonBackedPreferences` is a free in-memory `Preferences`.** It lives in commonMain
(`openAPSAIMI/tpo/`), is `internal` - which androidHostTest can see - and every `get` falls back to
the key's own default. Hand it an `AimiStorage` stub whose reads all answer "no file" and you get a
complete, deterministic preference store with no Mockito stubbing per key type. Caveat: it does not
persist `put` for Int or String keys, so it cannot be used to assert a write-back.

**3. Stub `AimiStorage` by hand, do not mock it.** 28 methods, several returning a non-null
`AimiPath`; a Mockito mock hands back `null` for those and the screen NPEs somewhere unrelated. An
`object` implementing all of them with "nothing there" values is ~30 lines and never surprises you.

Register the string owner as everywhere else - `TextRefIdRegistry.register("aps") { ApsStringIds.idOf(it) }`
in `@Before` - and read expected text from `RuntimeEnvironment.getApplication().getString(R.string.x)`
rather than hardcoding English. That way a broken string owner fails the test instead of rendering
blank.

For nodes inside the screen's `verticalScroll` column, chain `.performScrollTo()` before
`assertIsDisplayed()` or `performClick()`; they are composed but off-screen.

Related: [[ui-screen-test-fixture]], [[aimi-control-center-move]], [[move-breaks-two-test-harnesses]]
