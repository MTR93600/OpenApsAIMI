import kotlin.math.min

plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    alias(libs.plugins.android.kmp.library)
    kotlin("plugin.allopen")
    // Metro, a Kotlin compiler plugin - no KSP, no generated sources.
    alias(libs.plugins.metro)
}

// Restated from all-open-dependencies, which applies com.android.library and cannot be used here.
allOpen {
    annotation("app.aaps.annotations.OpenForTesting")
}

kotlin {
    android {
        namespace = "app.aaps.core.objects"
        compileSdk = Versions.compileSdk
        minSdk = min(Versions.minSdk, Versions.wearMinSdk)
        // This module owns no resources, but its tests read R classes from :core:interfaces and
        // :core:ui through :shared:tests. Without this the R jars never reach the test classpath and
        // every test touching one dies with NoClassDefFoundError.
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

    // CryptoUtil is plain javax.crypto with no Android in it, and its output is a STORED format, so
    // Android and desktop share the one implementation rather than keeping two that could drift.
    // Applied explicitly, because the manual dependsOn below would otherwise switch the automatic
    // hierarchy off and silently unwire iosMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(commonMain.get())
            dependencies {
                // X25519, ECDH and ECDSA. AES-CCM and AES-CMAC do not use this library: they are the
                // common constructions over JCE AES/ECB/NoPadding. BouncyCastle is a test oracle
                // only (see jvmTest), same 1.81 coordinate as :plugins:libkeks.
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.optimal)
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain {
            dependencies {
                api(project(":core:data"))
                api(project(":core:interfaces"))
                api(project(":core:keys"))
                api(project(":core:utils"))
            }
        }
        androidMain {
            dependencies {
                api(libs.kotlin.stdlib.jdk8)
            }
        }
        iosMain {
            dependencies {
                // Kotlin/Native has no javax.crypto, so the iOS side of CryptoPrimitives goes
                // through this instead. It wraps CommonCrypto and CryptoKit rather than
                // implementing anything, which is what is wanted in a path that protects an export.
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.optimal)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // BouncyCastle is not a production dependency. The JVM tests use it as an independent
        // AES-CCM oracle, and `cryptography-kotlin` finds the same classes when it derives an
        // X25519 public key (SunEC cannot). iOS never sees this jar.
        getByName("jvmTest") {
            dependencies {
                implementation("org.bouncycastle:bcprov-jdk18on:1.81")
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(project(":shared:impl"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                // The platform org.json on the Android unit-test classpath is a stub.
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.junit.platform.launcher)
                // Same test-only jar as jvmTest: the X25519 public-key vector needs the classes
                // cryptography-kotlin looks up. Not a production dependency.
                implementation("org.bouncycastle:bcprov-jdk18on:1.81")
            }
        }
    }
}
