plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as the :core modules and the converted plugins.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)
}

// Generates MedtrumStrings (commonMain) and MedtrumStringIds (androidMain) from this module's
// res/values, the same generator the other modules use. The strings themselves do not move, and
// AAPT keeps resolving them on Android exactly as before. The patch wizard needs this to reach
// commonMain at all: R.string does not exist there.
val generateMedtrumStrings = tasks.register<GenerateKeyStringsTask>("generateMedtrumStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.pump.medtrum")
    owner.set("medtrum")
    objectName.set("MedtrumStrings")
    idsObjectName.set("MedtrumStringIds")
    reportFile.set(layout.buildDirectory.file("reports/medtrumStrings/translations.txt"))
    // Set explicitly: addGeneratedSourceDirectory only derives a convention from the task name, so
    // both properties would land on one directory and the second file written would delete the first.
    commonOutputDir.set(layout.buildDirectory.dir("generated/medtrumStrings/common"))
    androidOutputDir.set(layout.buildDirectory.dir("generated/medtrumStrings/android"))
}

kotlin {
    android {
        namespace = "app.aaps.pump.medtrum"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        // Restated from android-module-dependencies, which a multiplatform module cannot apply.
        // Without it MissingTranslation switches on here and every incomplete locale file fails a
        // release build.
        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    // What stops an Android import from quietly reaching shared code. The patch wizard is moving
    // to commonMain so that an iPhone runs the same screens, and these targets are the check.
    iosArm64()
    iosSimulatorArm64()

    // Desktop. Not because a desktop drives a pump - the plugin is androidMain and iosMain only -
    // but because this module owns strings now, and every shell's generated string-owner registry
    // names every owner. Without a jvm target the desktop shell cannot resolve MedtrumStrings and
    // does not compile. :pump:virtual is linked by the desktop for the same reason.
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateMedtrumStrings.flatMap { it.commonOutputDir })
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:objects"))
                implementation(project(":core:ui"))

                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.foundation)
                api(libs.jetbrains.compose.ui)
                api(libs.jetbrains.compose.material3)
                api(libs.jetbrains.lifecycle.viewmodel.compose)
                api(libs.jetbrains.lifecycle.runtime.compose)
            }
        }

        androidMain {
            kotlin.srcDir(generateMedtrumStrings.flatMap { it.androidOutputDir })
            dependencies {
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.compose.ui.tooling.preview)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        iosTest {
            dependencies {
                implementation(kotlin("test"))
                // The driver tests drive suspend functions, so they need runTest.
                implementation(project.dependencies.platform(libs.kotlinx.coroutines.bom))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used by a multiplatform module. Same as
        // :plugins:source and :plugins:aps.
        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(kotlin("test"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                // The real org.json: isReturnDefaultValues makes the platform stub answer null
                // rather than throwing, which NPEs the shared profile fixtures in TestBaseWithProfile.
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}

tasks.withType<Test> {
    // useJUnitPlatform() and the heap cap come from kmp-test-defaults; only the JaCoCo part is
    // specific here. Restated from jacoco-module-dependencies, which applies com.android.library.
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
