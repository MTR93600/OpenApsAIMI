plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as :plugins:source and the :core modules.
    alias(libs.plugins.android.kmp.library)
    // No KSP and no Metro: this driver has no dependency injection at all. It is reached through
    // the OnePlusCgmDrivers object from :plugins:source, not from the application graph.
}

kotlin {
    android {
        namespace = "app.aaps.plugins.dexcomoneplus"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        // Creates the androidHostTest compilation. isReturnDefaultValues and
        // isIncludeAndroidResources are restated from test-module-dependencies, which a
        // multiplatform module cannot apply: the scanner test touches android.bluetooth classes
        // that are stubs on the unit-test classpath.
        withHostTest {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
        // Restated from android-module-dependencies, which a multiplatform module cannot apply.
        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    // These are what stop an android-only import from quietly reaching the shared protocol code.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:interfaces"))
                // The vendored xDrip KEKS authentication library. It is Kotlin Multiplatform
                // since the port, so OnePlusSessionAuthKeks and OnePlusKeksGuideCerts live in
                // commonMain and the handshake compiles for Apple as well as for Android.
                implementation(project(":plugins:libkeks"))
            }
        }

        androidMain {
            dependencies {
                // Kept as `api` exactly as the old android library exported it, so nothing that
                // resolved androidx.core through this module loses it.
                api(libs.androidx.core)
                // AAPS writes everything that comes through slf4j to AndroidAPS.log. JVM only,
                // which is why OnePlusLog keeps its sink behind an expect/actual.
                implementation(libs.org.slf4j.api)
            }
        }

        // The packet helpers that replaced java.nio.ByteBuffer are tested here rather than beside
        // the other packet tests, so they run on Kotlin/Native as well as on the JVM. Everything
        // else stays in androidHostTest: those tests are written against JUnit 5 and Truth, which
        // have no Apple artifacts.
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // The iOS BLE scanner and GATT client are driven here through a fake BleCentral, the same
        // way the Medtrum iOS transport is tested. These really run on the simulator, so the
        // Kotlin/Native rules apply: no test name may contain a comma.
        iosTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used by a multiplatform module.
        getByName("androidHostTest") {
            dependencies {
                // Only what the 27 test classes actually import: JUnit 5 and Truth. Mockito,
                // joda-time, jsonassert and coroutines-test came from the convention plugin and
                // are not used here.
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.com.google.truth)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
