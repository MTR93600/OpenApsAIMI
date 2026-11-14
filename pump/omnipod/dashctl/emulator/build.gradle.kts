plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
}

// The simulated Omnipod Dash pod, for tests only: the pod side of pairing, session and commands
// (`FakeDashPod`), the BLE centrals in front of it (`FakeDashPodCentral`, `HeldPodCentral`), the pod
// of an activation (`ActivatingPodModel`), a running pod (`RunningPodModel`) and the crypto of each
// platform for the pod side (`parity*`).
//
// A module of its own so that tests outside :pump:omnipod:dashctl can drive the real Dash plugin
// against it, as the end-to-end test of :ios:shell does through the real command queue. Nothing
// here is in the app: no shell links it, and :ios:shell leaves it out of its framework (see
// `checkMigratedModules` there).
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(Versions.jvmTarget)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":pump:omnipod:dashctl"))
                api(project(":core:interfaces"))
                // FakeDashPod checks the pod side with kotlin.test (`assertNoProblems`).
                api(kotlin("test"))
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
