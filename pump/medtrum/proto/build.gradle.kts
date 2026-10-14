plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.aaps.pump.medtrum.proto"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
        lint {
            checkReleaseBuilds = false
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                // The catalog gives coroutines no version of its own any more, the BOM does. Most
                // modules inherit it through `:core:interfaces`, which exports it with `api`. This
                // source set depends on no such module, so it has to import the BOM itself or the
                // dependency cannot be resolved for android and jvm.
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        iosMain {
            dependencies {
                implementation(project(":core:interfaces"))
                implementation(project(":core:data"))
                implementation(project(":core:keys"))
            }
        }

        iosTest {
            dependencies {
                implementation(kotlin("test"))
                // The driver test drives suspend functions, so it needs runTest. Same BOM reason as
                // above: this source set depends on no module that exports the coroutines BOM.
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
