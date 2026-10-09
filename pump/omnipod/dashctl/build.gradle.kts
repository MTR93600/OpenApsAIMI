plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library: the multiplatform plugin owns the Android target,
    // same as :pump:virtual and the :core modules.
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.aaps.pump.omnipod.dashctl"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:objects"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
            }
        }

        androidMain {
            dependencies {
                // SpongyCastle (AES-CCM/CMAC) and Tink (X25519), as on the Android driver.
                implementation("com.madgag.spongycastle:core:1.58.0.0")
                implementation("com.google.crypto.tink:tink-android:1.23.0")
            }
        }

        iosMain {
            dependencies {
                // Pure-Kotlin crypto (AES-CCM/CMAC via CommonCrypto AES-ECB, X25519 per
                // RFC 7748); no third-party crypto dependency on iOS.
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation(libs.com.google.truth)
                implementation(libs.commons.codec)
                implementation(libs.io.kotlintest.runner.junit5)
                runtimeOnly(libs.org.junit.jupiter.engine)
            }
        }
    }
}
