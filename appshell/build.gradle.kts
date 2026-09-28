plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
}

/**
 * The app's shell: navigation and the composable root.
 *
 * `:app` cannot be multiplatform - it is `com.android.application`, and it owns the product flavours
 * and the Android entry points. So the part of it that is really just UI lives here instead, in a
 * module that can become multiplatform.
 *
 * The plugin dependencies moved here from `:app` rather than being copied: they are `api`, so `:app`
 * still sees them for its DI graph. That keeps the number of edges in the build graph the same bar
 * one, which matters because every plugin is on this list.
 *
 * `AppRoute` and `AppNavGraph` are both in **commonMain**, and every module this one depends on
 * already has Apple targets. Three things still have to happen before this module can declare its
 * own, and they are all inside `AppNavGraph`:
 *
 *  1. **Seven `R.string` references**, aliased in from `:plugins:main` and `:core:ui`. Both modules
 *     already generate `TextRef`s, so this is the usual name-preserving swap.
 *  2. **`backStackEntry.arguments?.getString(...)`** - `arguments` is a `Bundle` on Android and a
 *     `SavedState` in multiplatform navigation, so those reads need the `SavedState` API.
 *  3. A stale `androidx.compose.ui.res.stringResource` import.
 *
 * The navigation dependency is already the JetBrains republish, so nothing is waiting on that.
 * `:plugins:sync` is multiplatform now too - an older note here said otherwise.
 */
/**
 * Feature plugins that are Android only and have no iOS variant to resolve.
 *
 * They are the fork's native CGM drivers, built on Android Bluetooth and NFC, so there is nothing to
 * build for Apple targets. Listing them in `commonMain` made every iOS compilation of `:appshell`
 * (and of `:ios:shell` behind it) fail at dependency resolution, before a single source file was
 * read: Gradle cannot choose an `ios_arm64` variant of a plain Android library. They belong to
 * `androidMain`, which is the only place that can use them.
 */
val ANDROID_ONLY_PLUGINS = listOf(
    ":plugins:dexcom_oneplus",
    ":plugins:libkeks",
    ":plugins:libre3",
)

kotlin {
    android {
        namespace = "app.aaps.appshell"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }


    iosArm64()
    iosSimulatorArm64()

    // Desktop (Windows/macOS/Linux). Compose Multiplatform resolves its `desktop` variant from a
    // plain jvm() target, so no special target name is needed.
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core:data"))
                api(project(":core:interfaces"))
                api(project(":core:keys"))
                api(project(":core:objects"))
                api(project(":core:ui"))
                api(project(":ui"))

                // Feature plugins self-register into the plugin map. This list moved here from `:app`
                // with the navigation graph that routes to them; `api` so `:app` still builds its
                // graph against them. Adding or removing a plugin stays an edit in settings.gradle.
                rootProject.subprojects
                    .filter {
                        it.path.startsWith(":plugins:") &&
                            it.buildFile.exists() &&
                            // The AIMI KMP modules target jvm and iOS only - they have no Android
                            // variant to resolve, and they are engine libraries rather than feature
                            // plugins, so they never self-register into the plugin map.
                            !it.path.startsWith(":plugins:aimi-") &&
                            it.path !in ANDROID_ONLY_PLUGINS
                    }
                    .forEach { api(project(it.path)) }

                api(libs.cmp.runtime)
                api(libs.cmp.foundation)
                api(libs.cmp.ui)
                api(libs.cmp.material3)
                api(libs.cmp.material.icons.extended)
                // The JetBrains republishes, not the plain androidx ones: same package names, with
                // Apple targets. Same choice as :core:ui and :ui.
                api(libs.androidx.compose.navigation)
                api(libs.jetbrains.lifecycle.runtime.compose)
            }
        }

        androidMain {
            dependencies {
                api(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.activity.compose)

                // The Android only feature plugins, kept out of commonMain on purpose - see
                // ANDROID_ONLY_PLUGINS above. `:app` still reaches them through this source set.
                ANDROID_ONLY_PLUGINS.forEach { api(project(it)) }
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
