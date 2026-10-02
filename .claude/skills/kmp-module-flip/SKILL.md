---
name: kmp-module-flip
description: Turn an ordinary Android library in this repo into a Kotlin Multiplatform module, and move its code to commonMain. Use when converting a module to KMP, when a KMP module fails to build after conversion, or when deciding what can move to commonMain.
---

# Flipping a module to Kotlin Multiplatform

This is the repeatable part of the KMP migration. It is written from modules already flipped
(`:implementation`, `:database:impl`, `:database:persistence`, `:core:*`, `:ui`, `:plugins:*`,
`:appshell`), so each step is something that has actually gone wrong at least once.

**Keep this file up to date.** When you flip a module and hit something that is not written here, add
it before you finish. When a step here turns out to be wrong or no longer needed, correct it in the
same change rather than working around it. A stale recipe costs more than no recipe, because it is
believed.

## The target state, and what a module arriving from elsewhere has to become

`:plugins:calibration` is the closest thing to the finished shape: 9 files in commonMain, 1 in
androidMain, 1 in iosMain, tests in androidHostTest. Aim at that.

| | target |
|---|---|
| module type | `kotlin("multiplatform")` + `libs.plugins.android.kmp.library` |
| flavours | none - only `:app`, `:wear` and `:wear:watchfacepush` have them |
| DI | Metro only. `@Inject`, `@SingleIn(AppScope::class)`, `@ContributesBinding`; a plugin registers itself with `@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())` **from commonMain** |
| UI | Compose Multiplatform in commonMain; `androidx.compose.*` package names are the same |
| strings | `XxxStrings` (`TextRef`) generated into commonMain; no `R.string` and no `@StringRes Int` in any shared signature |
| platform work | behind an interface in commonMain, implemented in androidMain (and iosMain when it exists) |
| source sets | `commonMain` holds the bulk; `androidMain` holds only ports and Android entry points |
| tests | `androidHostTest`, run by `testAndroidHostTest`; instrumented in `androidDeviceTest` |
| targets | `iosArm64()` and `iosSimulatorArm64()` declared from the start |

A module written against the old architecture - a pump driver from another fork, say - will arrive as
`com.android.library` with an annotation-processor DI framework, XML layouts and ViewBinding,
`R.string` throughout, and a `Context` threaded through its classes. Convert in this order, so each
step is green on its own and the DI stays working:

1. **DI first.** Any annotation-processor DI out, Metro in. Nothing else can proceed while a processor
   is in the module, and this is where the constructor traps below bite.
2. **UI to Compose**, if it still has XML. A View-based screen cannot move to common code at all.
3. **Strings** to the generated `TextRef`s.
4. **Platform ports** - replace `Context` and other Android types with interfaces, checking first
   whether the parameter is used at all.
5. **Flip the module type**, move the sources, add the iOS targets.
6. **Move files to commonMain** and let the iOS compile tell you what is really left.

Then wire it up: `include` in `settings.gradle`; add it to `:appshell` as `api(...)` if it has
screens the navigation graph reaches; add it to `migratedModules` in `ios/shell/build.gradle.kts`
once it builds for iOS; register its string owner in `MainApp` and `BaseTestApp`.

## The hard precondition: no annotation-processor DI in the module

No KMP module in this tree runs a DI annotation processor, and that is not a coincidence - a processor
that generates Java has nothing to generate into in a multiplatform module. Metro is a **compiler
plugin**, so it works everywhere and is the only DI here.

An earlier "it still builds" is usually stale generated output. **Always `rm -rf <module>/build`
before believing a processor is unnecessary.**

## Build file

Copy `core/ui/build.gradle.kts`. It is the closest template: resources, Compose and Robolectric.

- `kotlin("multiplatform")` + `alias(libs.plugins.android.kmp.library)`. **Not** `com.android.library`
  - AGP 9 refuses that plugin together with the multiplatform plugin.
- **No convention plugin can be applied** (`android-module-dependencies`, `test-module-dependencies`,
  `compose-test-module-dependencies`, `jacoco-module-dependencies`, `all-open-dependencies`) - they
  all apply `com.android.library`. Restate by hand what you need: the `lint { disable += ... }` block,
  `withHostTest { isIncludeAndroidResources = true }`, the test dependencies, the
  `JacocoTaskExtension` block, and `kotlin("plugin.allopen")` with its `allOpen { annotation(...) }`.
- `androidResources { enable = true }` - off by default here, unlike a plain Android library.
- There are **no product flavours and no build types**, so `debugImplementation` does not exist.
- Add `iosArm64()` and `iosSimulatorArm64()` as soon as anything lands in commonMain. They are what
  makes an Android import in common code fail the build instead of quietly compiling.

### Flavours are no longer a problem

Older modules carry a `ProductFlavorAttr` pin to disambiguate a flavoured dependency. **Do not copy
it into a new module.** Product flavours were removed from the library convention plugin, so only
`:app`, `:wear` and `:wear:watchfacepush` have flavours now, and an unflavoured
consumer resolves them without help. If you see the pin in an existing build file, it is left over
and can go.

### The dependency list the convention plugin used to supply

Read `buildSrc/src/main/kotlin/test-module-dependencies.gradle.kts` **before** flipping and copy the
whole list, rather than finding it one compile failure at a time: `kotlin("test")`,
`org-junit-jupiter`, `org-junit-jupiter-api`, `org-junit-platform-launcher`,
`org-mockito-junit-jupiter`, `org-mockito-kotlin`, `joda-time`, `com-google-truth`,
`org-skyscreamer-jsonassert`, `kotlinx-coroutines-test`. Add `libs.org.json.android` and
`org.robolectric` on top, plus the Compose test artifacts.

`libs.org.json.android` is the one that hurts if missed: `isReturnDefaultValues` makes the platform
`org.json` stub return null instead of throwing, and the shared profile fixtures in
`TestBaseWithProfile` then NPE. That once failed **121 of 210 tests** inside the shared base, nowhere
near the real cause.

### If the module has instrumented tests, it needs a second dependency list

`androidHostTest` is the easy one to remember, because a missing dependency there fails the build you
are already running. `androidDeviceTest` does not: nothing local compiles it, so a module can look
completely green and still be broken. `:plugins:sync` was pushed that way and only CI caught it.

Three separate things all have to be restated:

1. **The runner.** `withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }`.
   An empty `withDeviceTest { }` builds fine and has nothing to run the tests with.
2. **JUnit 4 dependencies**, from the `androidTestImplementation` lines of
   `test-module-dependencies.gradle.kts`: `androidx-test-ext`, `androidx-test-rules`,
   `com-google-truth`, `org-mockito-android`, `org-mockito-kotlin`, `kotlinx-coroutines-test`.
   Instrumented tests are JUnit 4; the host tests next to them are JUnit 5.
3. **An exclusion, if the module depends on `:shared:tests` from the device test.** That project
   carries JUnit 5, `TestBase` pulls it onto the device classpath, and dexing it fails with
   `Attempt to create a global synthetic for 'Record desugaring'` - JUnit 6 uses Java records.
   ```kotlin
   configurations.named("androidDeviceTestImplementation") {
       exclude(group = "org.junit.jupiter")
       exclude(group = "org.junit.platform")
   }
   ```

Verify locally before pushing - none of this needs a device:

```
./gradlew.bat :module:compileAndroidDeviceTest :module:assembleAndroidDeviceTest --no-daemon
```

Watch for a JUnit 4 test extending a JUnit 5 base class. `GarminDeviceClientTest` extends `TestBase`,
whose `@BeforeEach` never fires under `@RunWith(AndroidJUnit4)`. It happens to work because it only
touches a field initialised at construction, but anything relying on `openMocks` in that base would
get nulls and no warning.

## Source moves

`src/main` → `src/androidMain`, `src/test` → `src/androidHostTest`,
`src/androidTest` → `src/androidDeviceTest`.

**Grep the moved tests for the literal strings `src/test/` and `src/main/`.** A hard-coded path
compiles fine and fails only at runtime.

Task names change with the layout, and a wrong name **runs no tests and still exits 0**:

| source set | task |
|---|---|
| `androidHostTest` | `testAndroidHostTest` |
| `androidDeviceTest` | `connectedAndroidDeviceTest` |
| plain Android library | `testDebugUnitTest` |
| `:app` / `:wear` | `testFullDebugUnitTest` |

`.circleci/config.yml` names all of them, and
`buildSrc/src/main/kotlin/jacoco-aggregation.gradle.kts` picks the variant directory per module. If
you change a module's shape, check both.

## The Metro construction trap

This is the worst one, and it has happened four times. A class that nothing contributes is **never**
constructed by `app/src/test/.../di/metro/TestRoot.kt`. The moment it gets `@ContributesBinding`,
Metro builds it for real in every graph test, with every dependency resolved for real.

| class | work done at construction | symptom |
|---|---|---|
| `VersionCheckerUtilsImpl` | property init opens `definition.json` | infinite read loop, OOM - looks like a machine problem |
| `OneTimePassword` | `init { configure() }` generates and persists an OTP secret | NPE, 11 graph tests |
| `AuthFlowOut` | property init builds `AuthorizationService`, which inspects installed browsers | `ExceptionInInitializerError` |
| `NotificationManagerImpl`, `FabricPrivacyImpl` | notification channel, Firebase flags, `while(true)` loop | not converted - see below |

**Before adding `@ContributesBinding` to a class, read its property initializers and `init` block for
I/O, sockets, or anything blocking.** Usually the fix is `by lazy { ... }`, which is better in
production too, since building the graph should not do file I/O.

**Laziness is not always right.** Where the `init` work is a *startup obligation* - registering a
receiver, setting analytics flags, starting a periodic loop - deferring it changes behaviour. Those
need an explicit `start()` from `MainApp.onCreate()`, which is a real refactor.

**And an explicit `start()` has to be called by three shells, not one.** `MainApp.onCreate()` calls
several; `desktop/shell/.../Main.kt` and `ios/shell/.../IosAppStartup.kt` call almost none. A missed
call is silent - the class exists, the screens render, the work never happens. Prefer `by lazy` unless
the work really is an obligation, and when it is, add the call to all three at the same time.

**The clients make this worse than tests do.** `DesktopAppGraph` and `IosAppGraph` are built
**eagerly at startup, before any window exists**, so a constructor that opens a file or a socket fails
at launch there rather than in a test you can re-run. Anything reachable from `ClientGraphBindings`
gets this treatment on both platforms.

**Constructors must not write.** Two classes still do - `InsulinImpl` (`init { bootstrap() }` can
`putRemote`) and `ProfileRepositoryImpl`. Building a graph should never author a persisted write, and
on a paired client `putRemote` is on the sync path, so a graph build can publish a config change.

To confirm this class of failure: `git stash` the change and re-run the same test task. A baseline
that passes in about a minute against a run that never finishes is unambiguous.

## Moving code to commonMain

Counting files with no `android`/`androidx`/`java` import over-estimates badly: a
file can name `app.aaps.core.ui.R` or take a `Context` indirectly. Compile for iOS to find out.

Beware the grep, too: `^import android` also matches `androidx`, so it hides every Compose file.
Anchor it as `^import android\.`.

**A missing import is a blocker an import scan cannot see.** `@Volatile` written with no import at
all resolves implicitly to `kotlin.jvm.Volatile` on JVM and Android and fails on Native with
`Unresolved reference 'Volatile'`; the fix is `kotlin.concurrent.Volatile`. The general form of this
and the paragraphs around it is one rule: **a file's blocker is a property of its body, not of its
imports, its folder, or what a previous report said about it.** One file in this campaign was
mis-classified as Android because it sat in a folder beside Android ones, and another because an
earlier report said it took a `Context` when it only carried a dead import.

**A type in the same package needs no import either.** An `internal object` next to the file you are
moving is invisible to any import scan, and it is the blocker the compiler finds after you have already
convinced yourself the file is clean. This has now bitten four separate probes in one campaign.

**And `java.lang` needs no import at all**, so an import scan cannot see `String.format`,
`System.currentTimeMillis`, `Thread`, `Math`, `StackTraceElement` or `Throwable.stackTrace`. A file
whose imports look completely clean can still fail to compile for iOS. Two real examples from this
repo: `AimiLoopTickRecovery` imported nothing but reads `error.stackTrace` frame by frame
(`className`, `methodName`, `lineNumber`), which has no shared equivalent; `AimiLoopTelemetry`
imported one `AtomicLong` and also used `@Volatile`, `synchronized` and a neighbour that needed a
timed `tryLock`. **Move one file, compile, keep or revert. Never move a batch on the strength of a
grep.**

**A generated declaration leaves nothing to grep for, and that cuts the other way too.** The mirror
of the rule above: absence of a grep hit is not absence of the thing. Searching this repo for
`TextRefIdRegistry.register(` finds two calls for sixteen string-owning modules, which reads as
fourteen owners with no resolver and 2 000+ call sites about to draw raw resource names. They are
all fine - the registries are generated from `StringOwnerModules.kt`, and the two hand-written calls
are the two modules the generator cannot reach. The same goes for `XxxStrings`/`XxxStringIds`
themselves: they live under `build/generated/`, so `find` says they do not exist until something has
built. **Before concluding a wiring is missing, read the generator, not the call sites.**

Not every blocker is a substitution. Some are a design question and should stop the lot rather than
be forced: `AapsLock` has `lock`/`unlock` and no timed `tryLock`, so anything built on
`ReentrantLock.tryLock(timeout)` or `isHeldByCurrentThread` cannot move without changing
`core:interfaces` first.

### Strings are usually the biggest single blocker

`R.string.x` cannot exist in commonMain. The fix is `GenerateKeyStringsTask`, which turns the
module's `strings.xml` into a `XxxStrings` object of `TextRef.Named` (commonMain) plus a
`XxxStringIds` map (androidMain). Copy the task registration from `ui/build.gradle.kts`, then:

1. Add `kotlin.srcDir(...)` for the common output to `commonMain` and the android output to
   `androidMain`, and `implementation(project(":core:keys"))` to commonMain for `TextRef`.
2. Add one line for the module to `buildSrc/src/main/kotlin/StringOwnerModules.kt`, giving the same
   four values the task was configured with. Every platform's resolver registry is **generated** from
   that single list, so there is no `register(` call to write by hand any more. A module generated but
   missing from the list draws raw resource names on screen; a module in the list but not generated
   fails the build, which is the better direction of the two. Older notes - and five KDoc comments
   still in the tree - tell you to edit `MainApp.registerStringOwners()` and `BaseTestApp`; both lists
   are gone, and that is why four hand-written copies drifted before (the desktop one had five of
   sixteen modules).
3. Swap `R.string.foo` for `XxxStrings.foo`. The substitution is name-preserving, so a wrong mapping
   cannot happen silently - it fails to compile.
4. In Composables import `app.aaps.core.ui.compose.stringResource` alongside the androidx one. Both
   are called `stringResource`; Kotlin picks by parameter type.
5. **A string whose argument is only known at callback time cannot use `stringResource`.** Hoisting
   it to a `val` in the composable body is the usual move and it works only for zero-argument
   strings. When the argument comes from inside a nested non-Composable function - a click handler,
   an `onSend()` - take a `TextResolver` parameter instead and call `textResolver.gs(ref, arg)`.
   `TextResolver` is in `core/interfaces/commonMain`, `ResourceHelper` already extends it (so the
   call site passes `rh`), and `SelectableListToolbar(rh: TextResolver)` in `core/ui/commonMain` is
   the precedent. Restructuring the callback into a `LaunchedEffect` to make hoisting possible is
   usually the wrong answer: if the handler suspends on `showSnackbar`, the work after it is
   deliberately deferred and moving it changes observable behaviour.

Sweep **every** receiver spelling, not just the obvious one: `rh.gs(R.string.x)`,
`resourceHelper.gs(...)`, the fully qualified `app.aaps.plugins.foo.R.string.x`, and any aliased
`FooR.string.x`. Each of these has been missed once and found only by a failing test.

After the swap, unwrap `TextRef.AndroidRes(XxxStrings.x)` - the argument is already a `TextRef`.

**Order the substitutions, or a blanket `sed` will break the cross-module ones.** Replacing
`R\.string\.` first turns `app.aaps.core.ui.R.string.back` into `app.aaps.core.ui.ApsStrings.back`,
which fails to compile with `Unresolved reference 'ApsStrings'` pointing somewhere unhelpful. Do the
fully-qualified and aliased forms (`app.aaps.core.ui.R.string.x`, `CoreUiR.string.x`) **first**, then
the bare `R.string.x`.

**`android.R.string.ok` / `.cancel` are the Android framework table**, not the app's, and have no
multiplatform form. `CoreUiStrings.ok` / `.cancel` carry identical English text, so the swap is
parity-safe - but say so explicitly when reporting, because one of these turned out to be the
`contentDescription` of a back arrow reading "Cancel", a pre-existing oddity a port should carry
across rather than fix.

**Tests need the same swap**: `whenever(rh.gs(R.string.x))` becomes `whenever(rh.gs(XxxStrings.x))`,
and a blanket `rh.gs(anyInt())` stub becomes `doAnswer { ... }.whenever(rh).gs(any<TextRef>())` -
written that way round because `rh.gs(any<TextRef>())` on its own is ambiguous against the vararg
overload. If the module's owner is not registered in `shared/tests/TextRefStubs.kt`, an unstubbed
name resolves to itself, so expectations like `isNull()` become the string's own name. A Robolectric
Compose test must still call `TextRefIdRegistry.register(owner) { XxxStringIds.idOf(it) }` in its
setup - a host test does not go through the generated registry the way the app does.

### `org.json`: which shim depends on which accessors the old code used

`OrgJsonCompat` in `core/data/commonMain` replaces `org.json` **`opt*`** accessors and preserves their
quirks (a missing key gives `""`, never null), so a straight type swap changes nothing downstream.

**It is the wrong tool for a reader that used the throwing getters.** `getJSONArray`, `getJSONObject`
and `getString` raise when what they ask for is not there, and that throw is often load-bearing: it is
what lands in the `catch` that produces a user-visible error. Swapping it for `optStringCompat` turns
a visible failure into a silent empty result. For those, mirror
`AuditorAIService.extractContentText` instead - `getValue(...).jsonArray[0].jsonObject.getValue(...)
.jsonPrimitive.content` - which raises at every step exactly as the getters did.

Writing is a third case. `OrgJsonCompat`'s own KDoc says writing changes the bytes, which is true, but
the question that matters is **who reads the result**: an HTTP request body is re-parsed by a JSON
parser that does not care about key order or whitespace, while JSON pasted into prompt text is read by
a language model character by character. The first is a port; the second is a decision.

### A new `expect` in `:plugins:aps` needs four halves, not two

This module has `commonMain`, `androidMain`, `iosMain` **and** `jvmMain`, with the JVM actuals
deliberately duplicated rather than shared through a `jvmSharedMain`. An `expect` with only Android
and iOS actuals passes an Android+iOS pre-gate and then fails the full gate with
`Expected … has no actual declaration in module <commonMain> for JVM`. This is why `compileKotlinJvm`
belongs in the pre-gate.

### An `Int` in an interface is a hard stop

`PumpEnactResult.comment(Int)` and `HardLimits.verifyHardLimits(..., valueName: Int, ...)` take a
resource id in the **interface**. A `TextRef` overload exists for `comment`; where one does not, the
implementation cannot move until the interface changes.

### Kotlin/Native rejects a comma in a backticked test name

`fun \`the tag is appended, making it longer\`()` compiles on JVM and fails Native with
`Name contains illegal characters: ","`. It only shows up once a test reaches commonTest, so a
JVM-only test can carry one for years. Rewrite the name; do not rename the test's meaning.

### Moving crypto: the provider is stricter than javax was

`javax.crypto` built a fresh `Cipher` on every call, which hid API misuse. A multiplatform provider
reuses objects and enforces the rules, so a migration can fail on something that was always wrong.
Moving `ClientControlCrypto` turned up **two tests reusing one IV with one key** for AES-GCM -
forbidden, and the provider says so (`Cannot reuse iv for GCM encryption`). Production was fine
because the IV is generated per use; only the fixtures were wrong.

Two rules when the format is already on the wire:

- **Keep golden vectors and put them in commonTest.** Digests minted by the old implementation are
  what prove the new one emits the same bytes, and in commonTest they run on every target the module
  builds for rather than only on the JVM.
- **Watch the packaging, not the algorithm.** The primitives interoperate by definition; the silent
  breakage is in how they are assembled - whether the AEAD nonce is prepended or stored separately,
  whether the GCM tag is appended, hex case. In cryptography-kotlin the plain `encryptBlocking`
  generates and prepends its own nonce; `encryptWithIvBlocking` (behind `@DelicateCryptographyApi`)
  is the one that matches a format storing the IV separately.

### Positional `mock()` constructor arguments hide a wrong wiring

Tests here build big plugins positionally, with long runs of bare `mock()`. Adding or removing a
constructor parameter shifts everything after it, and nothing complains: `mock()` fits any type.

The failure surfaces far away and looks nothing like the cause. Passing an unstubbed `mock()` where
the class collects a `Flow` gives a **null** upstream, which fails as
`UncaughtExceptionsBeforeTest` in whatever test happens to run next - not in the test that caused
it, and not with a message naming the parameter.

- Do not target these lines with `sed -i '<line>s/.../.../'`. Line numbers shift as soon as an
  import or a field is added above, and the edit then lands on the wrong call.
- After changing a constructor, grep every construction site and check the argument that matters is
  the **named field**, not a fresh `mock()`.
- `git stash` and re-run to tell "my change broke this" from "this was already flaky". The suite has
  a real `UncaughtExceptionsBeforeTest` flake, so the two are easy to confuse.

### Splitting a WorkManager worker

A worker is almost always a body wrapped in a class WorkManager can construct. `RunnerWorker` and
`WorkOutcome` in `:core:objects` exist for this: the body becomes a `XxxRunner` in commonMain with
`suspend fun run(): WorkOutcome`, and the worker keeps only the `@AssistedInject` scaffolding.

The nine NS client workers all transformed the same way, so it is scriptable - drop the
`@Assisted context`/`params` and `fabricPrivacy` parameters, make `aapsLogger` a `private val`, swap
`@AssistedInject constructor` for `@Inject`, drop the `LoggingWorker` supertype and the
`@AssistedFactory`, and map the returns:

| worker | runner |
|---|---|
| `Result.success()` | `WorkOutcome.Success` |
| `Result.success(workDataOf("Result" to x))` | `WorkOutcome.Skipped(x)` |
| `Result.failure(workDataOf("Error" to x))` | `WorkOutcome.Failure(x)` |

**Review the mapping table by hand afterwards** - it is the only part that carries meaning. A
`Result.success` with output data is not the same as a bare one: `WorkOutcome.Skipped` was added
precisely because `LoadBgWorker` reported "Load not enabled" that way, and collapsing it into
`Success` silently dropped a signal a test was asserting on.

Worker tests construct the worker directly, so each needs its argument list wrapped:
`XxxWorker(appContext, params, aapsLogger, fabricPrivacy, XxxRunner(aapsLogger, ...rest))`.

### `@IntKey` collides with the preference keys

Registering a plugin needs `dev.zacsweers.metro.IntKey`, and any class that also reads preferences
imports **`app.aaps.core.keys.IntKey`**. Kotlin then refuses both: `Conflicting import: imported name
'IntKey' is ambiguous`. The fix is an alias, which `LoopPlugin` and `AutotunePlugin` have always used:

```kotlin
import dev.zacsweers.metro.IntKey as MetroIntKey
...
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(310)
```

This is worth knowing because the clash has been misdiagnosed before: `SyncPluginsBindings` recorded a
**Dagger** error (`InjectProcessingStep`, `error.NonExistentClass`) as proof a plugin "cannot" be
annotated, and that false reason was then copied to another module. Metro has no annotation processor
and emits no such diagnostic. If a class seems to refuse `@ContributesIntoMap`, read the actual
compiler error before writing down a reason.

### Two Metro forms this version will not take

Neither is a blocker, but both look like the obvious idiom and both fail:

- **`@Binds` cannot live in an `object`** - "Extension property must have accessors or be abstract".
  Every binding container here is an `object`, so an alias `@Provides fun x(impl: XImpl): X = impl` is
  the right form, not a thing to convert.
- **`binding<@Qualifier Type>()` is rejected** - `Inapplicable candidate(s): constructor(scope:
  KClass<*>, binding: binding<*> = ...)`. So a **qualified** map entry still needs a stated
  `@Provides` in a container; only unqualified ones can move onto the class.

### The five gates, and why the iOS test one is not optional any more

Run all five, and measure the baseline **before** you touch anything, or an "after" number means
nothing:

```
:<module>:testAndroidHostTest
:<module>:jvmTest
:<module>:iosSimulatorArm64Test
:<module>:compileKotlinIosArm64
:app:assembleFullDebug
```

`:app:assembleFullDebug` is required, not a nicety: a missing or duplicated Metro binding only fails
when the app graph is linked, so no module-level task can catch it.

`iosSimulatorArm64Test` used to be skipped on every machine, which is why several traps below went
unnoticed for months. It runs on macOS **once a simulator runtime is installed** - that is a separate
download from Xcode (`xcodebuild -downloadPlatform iOS`). If gradle says *"Xcode does not support
simulator tests for ios_simulator_arm64"*, that is what it means, not a broken build file.

Never pipe a gradle run: the pipe's exit code hides a failure. Redirect to a log and grep it.

**`^e: ` alone is not enough, and relying on it has already produced two "clean" reads of failing
builds.** Only the Kotlin/Native and JVM paths print `e: `. The Android path
(`:plugins:aps:compileAndroidMain`) prints nothing of the sort - it reports a block like

```
Problem found: Kotlin compiler error (id: kotlin:compiler:error:compiler-error)
  Kotlin compiler error
    Function invocation 'context(...)' expected.
    Location: /…/DetermineBasalAIMI2.kt line 16055
```

Two real logs from this campaign carry 4 and 2 of those, with `BUILD FAILED`, and **zero `e: ` lines**.
So grep for all of it:

```
grep -E '^e: |Kotlin compiler error|BUILD FAILED|BUILD SUCCESSFUL' <log>
```

`BUILD FAILED` is the one marker that never lies, so make it the thing you look for first. Read test
counts from the XML under `<module>/build/test-results/<task>/`, not from the console line.

**Task names**: the KMP-library plugin calls the Android compile `:<module>:compileAndroidMain`.
There is no `compileFullDebugKotlinAndroid` or `compileDebugKotlinAndroid`. `compileAndroidMain`
plus `compileKotlinIosArm64` plus `compileKotlinJvm` is a useful ~1 minute pre-gate before the
~7 minute full one. Include the JVM one whenever the module has a `jvmMain` source set: without it a
missing or broken `jvm` actual survives the pre-gate and fails later.

`--rerun` has two gotchas, both measured here, and the first one keeps being paid for even by people
who were warned about it in writing - assume you will get it wrong and check, rather than assume you
got it right. A trailing flag does **not** apply to every task in the invocation - attach it per task
(`:a --rerun :b --rerun`), or a run comes back UP-TO-DATE in eleven seconds and looks like a pass.
**`BUILD SUCCESSFUL` and `UP-TO-DATE` are indistinguishable in a grep for the result line**, so make
the check explicit and part of the gate, not an afterthought:

```
grep -E '^> Task :plugins:aps:(testAndroidHostTest|jvmTest|iosSimulatorArm64Test)' <log>
```

Any of those three printed with `UP-TO-DATE` means that gate did not run and the result is worthless. And `--rerun` on
`:app:assembleFullDebug` does nothing, because that is an action-less lifecycle task; to force the APK,
put it on `:app:packageFullDebug`.

**And look for a test in `src`, never in `build`.** A stale `build/test-results/…Test.xml` from an old
run makes a test that no longer exists look as if it still runs. That is how two lost tests stayed
hidden in this campaign.

### When strings move, the argument count is what will bite you

Converting `context.getString(R.string.x, a, b)` to `rh.gs(XxxStrings.x, a, b)` is safe in the one way
people check and unsafe in the way they do not. A wrong **id** does not compile, because the members
are generated from the XML. A wrong **argument count or order** compiles perfectly and throws at
runtime - and in this repo those strings end up in `rT.reason`, which reaches Nightscout.

Worse, the two are not equivalent on failure: `ResourceHelperImpl.gs(id, vararg)` catches the format
error and returns a fallback, where `context.getString` threw. So a mistake stops being a crash and
becomes a wrong line the user reads.

Cover it with a table test of every id and its argument count, which fills each template with
arguments of the type each placeholder asks for and asserts nothing is left unfilled. Then:

- **Cross-check that table against the real call sites, not only against the XML.** The table is
  hand-written; if the same person wrote both, they agree with each other and not with reality. One
  wrong entry survived a green run and only failed when two lots were combined.
- A naive argument counter over Kotlin source is wrong twice: a **trailing comma** in a multi-line
  call reads as an empty argument, and a **nested** `rh.gs(...)` inside an argument list reads as a
  call site of its own. Both produce false mismatches.
- Check the **conversion letter against the argument type** (`%d` with an Int, `%f` with a Double).
  The compiler never looks at it.
- A template with non-positional specifiers (`%.2f ... %.2f`) is held together by argument order
  alone. Keep the order byte-identical.

### Atomics: the shared API is smaller than the JVM one

`kotlin.concurrent.atomics` with `@OptIn(ExperimentalAtomicApi::class)` replaces
`java.util.concurrent.atomic`, with the documented renames `get`→`load`, `set`→`store`,
`getAndSet`→`exchange`. Two traps found by compiling, not by reading:

- **`getAndUpdate` does not exist**, and neither does `addAndFetch`. A `getAndUpdate` has to become a
  `compareAndSet` retry loop - and **the decision must sit inside the loop**, not before it. Written
  the naive way (load, decide, store) two concurrent paths both read the old value and the later one
  wins, which is exactly how an invariant like "an engaged row is never replaced by a shadow row"
  disappears.
- **`incrementAndFetch` is an extension**, so it needs its own import line; importing the type is not
  enough.

### `AapsLock` does not cover every lock

It is `lock`/`unlock`/`withLock` plus `tryLock()`/`tryWithLock` - a non-blocking attempt. It has **no
timed `tryLock(timeout)` and no `isHeldByCurrentThread`**. Code built on those needs its own
`expect`/`actual`, and the iOS side is real work: `NSRecursiveLock` has `lockBeforeDate` for a bounded
wait but never publishes its owner, so an actual that needs the owner has to track the thread and the
hold count itself. Keep the Android actual byte-identical to the code you replaced, so the shipping
platform cannot change.

### Other common blockers

`javax.inject` (swap to `dev.zacsweers.metro.Inject` only for a class Metro already builds),
`@Synchronized`, `org.json`, `java.util.Calendar`, and `System.currentTimeMillis()` - the last is
just `Clock.System.now().toEpochMilliseconds()`.

`Provider<T>` is deprecated: the compiler says *"Using the desugared `Provider<T>` type is
discouraged. Prefer the function syntax form `() -> T`."* Write `() -> T` in new code. Call sites are
identical - `provider()` either way - so only the type and the import change.

### Lift the platform call out, keep the rule

When a class is blocked by one platform call, put that call behind an interface in commonMain and
implement it in androidMain, rather than leaving the whole class on Android. `PairedBtDevices` and
`LastKnownLocation` in `:plugins:automation` are the pattern: the trigger keeps its inputs,
serialization and matching logic in shared code, and only the Bluetooth or location call is
platform-specific. Implement the Android side straight away; other platforms can follow later.

A port must express *intent*, not steps. If the caller is coordinating platform timing on the
implementation.s behalf, the port is drawn in the wrong place. `LocationServiceController` was
`startService(): Boolean` / `stopService()`, and `AutomationRuntime` wrapped it in a
`DeferredForegroundStart` (Android 12 blocks `startForegroundService` from the background) plus its
own latch to retry after a location permission grant - two Android rules living in the rule engine.
Collapsing it to one idempotent `setLocationUpdatesEnabled(enabled)`, with the deferral and the latch
inside the Android implementation, removed the last non-UI blocker from the class. The test for a
suspicious port: ask whether iOS would need the same dance. If not, it belongs on the other side.

A related tell is an event that carries a platform type it does not need. `EventLocationChange` held
an `android.location.Location` but only ever fed a debug log - the distance a trigger compares comes
from `LastKnownLocation.distanceTo`, set separately. Check what a payload actually decides before
assuming it has to stay.

Two cautions. Keep the platform maths on the platform where an exact result matters -
`LastKnownLocation.distanceTo` still calls `Location.distanceTo`, so no distance changes. And a port
whose implementation on some target would be a silent no-op is a safety problem in this app: a rule
the user relies on would quietly stop firing, so the feature should be visibly absent on that target
instead.

### Test libraries are JVM-only, so a fixtures module barely moves

JUnit 5, Mockito and RxJava have no Kotlin/Native artifacts. Anything built on them is Android by
nature, not by accident, and no amount of work moves it. In `:shared:tests` that left exactly one
file in commonMain out of eleven:

| stays on Android | why |
|---|---|
| `TestBase`, `TestBaseWithProfile` | `@ExtendWith(MockitoExtension)`, JUnit 5 lifecycle |
| `TestAapsSchedulers` | RxJava |
| `TextRefStubs` | the generated `*StringIds` maps only exist in androidMain |
| `TestPumpPlugin` | `ResourceHelper` is androidMain; `PumpEnactResultObject` is in `:implementation` |
| `HardLimitsMock` | `HardLimits` still has abstract `Int` (resource id) overloads |
| `BundleMock`, `SharedPreferencesMock` | Android types are the point of them |
| `MemberInjectorCoverage`, `SplitBrainCoverage` | `JarFile` reflection over compiled output |

Flip such a module for the module type and the processor removal, not for the sharing. Say so up
front rather than discovering it file by file.

**A fixtures module must be excluded from `checkMigratedModules`.** It declares `iosArm64()` so that
common tests can use it, but `migratedModules` feeds the exported framework header, and test helpers
do not belong in the API Swift sees. There is a `filterNot` in `ios/shell/build.gradle.kts` for this.

## `:ios:shell:checkMigratedModules` will fail next

Once a module builds for iOS, the ios-branch guard fails until it is listed. It names the module and
what to do: add it to `migratedModules` in `ios/shell/build.gradle.kts` and bump
`ShellInfo.LINKED_MODULES`. Expect this on every flip.

## Do not run a javax-stripping sweep over `:app`'s DI files

`AppRootGraph`, `MetroGraphs`, `AapsLeaves` and `CoreObjectsModule` legitimately import
`javax.inject.Singleton` and `javax.inject.Inject`. A helper that strips javax while adding an import
breaks them with `Unresolved reference 'Singleton'`. Edit those by hand.
