plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as :plugins:dexcom_oneplus, :plugins:source and the :core modules.
    alias(libs.plugins.android.kmp.library)
    // No KSP and no Metro: this driver has no dependency injection at all. It is reached through
    // the Libre3CgmDrivers object from :plugins:source, not from the application graph.
}

kotlin {
    android {
        namespace = "app.aaps.plugins.libre3"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        // Creates the androidHostTest compilation. isReturnDefaultValues and
        // isIncludeAndroidResources are restated from test-module-dependencies, which a
        // multiplatform module cannot apply.
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
            }
        }

        androidMain {
            dependencies {
                // Kept as `api` exactly as the old android library exported it, so nothing that
                // resolved androidx.core through this module loses it.
                api(libs.androidx.core)
                // AAPS writes everything that comes through slf4j to AndroidAPS.log. JVM only,
                // which is why Libre3Log keeps its sink behind an expect/actual.
                implementation(libs.org.slf4j.api)
            }
        }

        // The shared helpers that replaced the JVM-only ones are tested here rather than beside
        // the other tests, so they run on Kotlin/Native as well as on the JVM. Everything else
        // stays in androidHostTest: those tests are written against JUnit 5 and Truth, which have
        // no Apple artifacts.
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used by a multiplatform module.
        getByName("androidHostTest") {
            dependencies {
                // Only what the test classes actually import: JUnit 5 and Truth. Mockito,
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
