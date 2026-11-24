plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library: the multiplatform plugin owns the Android target,
    // same as :pump:virtual and the :core modules.
    alias(libs.plugins.android.kmp.library)
    // The iOS Dash plugin (iosMain, `ios/plugin`) is contributed to the iOS app graph with
    // `@ContributesIntoMap`, as `IosMedtrumPlugin` in :pump:medtrum. A build plugin, not a module
    // dependency. Nothing in commonMain or androidMain is annotated, so Android gets nothing from it.
    alias(libs.plugins.metro)
    // The pump screens of the iPhone (overview, activation and deactivation wizards) are Compose
    // Multiplatform in commonMain, the way the Medtrum patch wizard is in :pump:medtrum.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Generates DashStrings (commonMain): the English texts of the iPhone Dash screens and notices,
// from src/androidMain/res/values/strings.xml, with the generator every other module uses.
//
// Not through registerKeyStrings and StringOwnerModules on purpose: every shell registers each
// owner of that list, so the Android app and the desktop would have to link this module. Only the
// iPhone uses it, so the owner is registered at run time by the iOS plugin
// (`registerDashStrings`, called by `IosDashDriverHost`). The Android id map the generator also
// writes is not compiled: the Android app does not use this module.
val generateDashStrings = tasks.register<GenerateKeyStringsTask>("generateDashStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.pump.omnipod.dashctl")
    owner.set("dashctl")
    objectName.set("DashStrings")
    idsObjectName.set("DashStringIds")
    reportFile.set(layout.buildDirectory.file("reports/dashStrings/translations.txt"))
    commonOutputDir.set(layout.buildDirectory.dir("generated/dashStrings/common"))
    androidOutputDir.set(layout.buildDirectory.dir("generated/dashStrings/android"))
}

kotlin {
    android {
        namespace = "app.aaps.pump.omnipod.dashctl"
        // 37.1 like the convention plugins: Compose 1.13 AARs refuse 37.0 (checkAarMetadata).
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
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
            kotlin.srcDir(generateDashStrings.flatMap { it.commonOutputDir })
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:objects"))
                // The shared pump screens (wizard layout, overview, profile gate, site location,
                // insulin choice) and the theme. :pump:medtrum uses the same module for the same reason.
                implementation(project(":core:ui"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)

                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.foundation)
                api(libs.jetbrains.compose.ui)
                api(libs.jetbrains.compose.material3)
                api(libs.jetbrains.lifecycle.viewmodel.compose)
                api(libs.jetbrains.lifecycle.runtime.compose)
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
                // The simulated pod (FakeDashPod and the centrals in front of it). A module of its own
                // so the end-to-end test of :ios:shell can use it too.
                implementation(project(":pump:omnipod:dashctl:emulator"))
                // Virtual time for the bolus wait (DashBolusWaitTest), on the JVM and on iOS.
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.test)
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
