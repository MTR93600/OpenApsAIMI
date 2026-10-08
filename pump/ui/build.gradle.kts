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
        compileSdk = Versions.compileSdk
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
                api(libs.cmp.runtime)
                api(libs.cmp.foundation)
                api(libs.cmp.ui)
                api(libs.cmp.material3)
                api(libs.cmp.material.icons.extended)
                implementation(libs.cmp.ui.tooling.preview)
            }
        }

        androidMain {
            dependencies {
                api(project.dependencies.platform(libs.androidx.compose.bom))
                api(libs.androidx.compose.material3)
                api(libs.androidx.compose.material.icons.extended)
                implementation(libs.androidx.compose.ui.tooling.preview)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
