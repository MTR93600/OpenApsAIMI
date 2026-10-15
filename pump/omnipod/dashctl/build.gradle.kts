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

    // Needed because jvmSharedMain below is wired by hand. Without this call the default
    // hierarchy is switched off and iosMain is unwired.
    applyDefaultHierarchyTemplate()

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

        // Shared by android and jvm: the crypto seams use plain JVM libraries only.
        val jvmSharedMain = create("jvmSharedMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        jvmSharedMain.dependencies {
            // SpongyCastle (AES-CCM/CMAC) and Tink (X25519), as on the Android driver.
            implementation("com.madgag.spongycastle:core:1.58.0.0")
            implementation("com.google.crypto.tink:tink-android:1.23.0")
        }

        iosMain {
            dependencies {
                // Pure-Kotlin crypto (AES-CCM/CMAC via CommonCrypto AES-ECB, X25519 per
                // RFC 7748); no third-party crypto dependency on iOS.
            }
        }

        // Parity vectors recorded from the Android driver run here, on the JVM and on iOS.
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        iosTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.test)
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
