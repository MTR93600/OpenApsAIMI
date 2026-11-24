plugins {
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same pattern as :core:ui.
    alias(libs.plugins.android.kmp.library)
    // The Compose COMPILER, which ships with Kotlin and compiles @Composable for every target.
    alias(libs.plugins.compose.compiler)
    // The Compose Multiplatform framework.
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "app.aaps.pump.ui"
        // 37.1 like the convention plugins: Compose 1.13 AARs refuse 37.0 (checkAarMetadata).
        compileSdk { version = release(Versions.compileSdk) { minorApiLevel = Versions.compileSdkMinor } }
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
    }

    // Apple klibs cross compile on Windows. Linking and running still need a Mac.
    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core:ui"))
                api(project(":core:interfaces"))
                api(project(":core:data"))

                // CMP rather than androidx. On Android CMP delegates to androidx.
                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.foundation)
                api(libs.jetbrains.compose.ui)
                api(libs.jetbrains.compose.material3)
                api(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.jetbrains.compose.ui.tooling.preview)
            }
        }

        // No androidMain Compose lines: dev dropped the androidx Compose BOM, and the commonMain
        // jetbrains artifacts above already give Android the same libraries.

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
