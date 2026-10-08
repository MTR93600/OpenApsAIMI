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
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
