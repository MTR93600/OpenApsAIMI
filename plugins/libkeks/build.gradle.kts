plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as :plugins:dexcom_oneplus, :plugins:libre3 and the :core modules.
    alias(libs.plugins.android.kmp.library)
    // No KSP, no Metro and no annotation processor at all. This module is vendored third party
    // code reached through jamorham.keks.Plugin, never through the application graph. Lombok is
    // gone with the Java: Kotlin needs no generated getters.
}

kotlin {
    android {
        namespace = "jamorham.libkeks"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        // Creates the androidHostTest compilation, which is what makes the golden values run
        // against the Android actuals - javax.crypto, SecureRandom and android.util.Log - and not
        // only against the JVM and iOS ones. isReturnDefaultValues is restated from
        // test-module-dependencies, which a multiplatform module cannot apply: the handshake logs
        // through android.util.Log, which is a stub on the unit test classpath.
        withHostTest {
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
        // Restated from android-module-dependencies, which a multiplatform module cannot apply.
        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    // These are what stop an android-only import from quietly reaching the shared handshake code.
    iosArm64()
    iosSimulatorArm64()

    // Desktop, and a third platform the golden vectors run on.
    jvm()

    sourceSets {
        // commonMain has no dependencies at all, on purpose. Keeping the vendored sources free of
        // project dependencies is what lets them be compared against the upstream pin, and it is
        // why this module carries its own two line lock rather than using :core:interfaces.
        commonMain {
            dependencies { }
        }

        // Every test is a golden value taken from the Java and BouncyCastle code that shipped on
        // Android, so all of them live in commonTest and run on the JVM, on Android and on the
        // iOS simulator. A handshake that is right on one platform and wrong on another is the
        // exact failure this module has to rule out.
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
