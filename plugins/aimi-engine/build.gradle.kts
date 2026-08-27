plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(Versions.jvmTarget)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AimiEngine"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":plugins:aimi-contracts"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":plugins:aimi-testkit"))
            }
        }
    }
}

apply(from = rootProject.file("plugins/aimi-domain-import-check.gradle.kts"))
