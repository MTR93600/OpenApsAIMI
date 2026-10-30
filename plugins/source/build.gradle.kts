plugins {
    id("kmp-android-library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.metro)
}

metro {
    interop {
        // Upstream dropped this block, because upstream's :plugins:source carries no javax annotation
        // any more. That is not true on this fork: the Dexcom ONE+ and Libre 3 sources added here are
        // still on javax, 14 files of them, including DexcomOnePlusWarmupBasalGuard's @Inject
        // constructor and CgmDriverLogActivity's @Inject fields. Without interop Metro does not process
        // those declarations and :app fails to build them with UnprocessedUpstreamDeclaration and
        // MissingBinding<MembersInjector<CgmDriverLogActivity>>.
        //
        // Remove this block when those 14 files move to dev.zacsweers.metro annotations, not before.
        includeDagger()
    }
}

registerKeyStrings("source")

kotlin {
    android {
        namespace = "app.aaps.plugins.source"
        androidResources { enable = true }
        // isIncludeAndroidResources is what makes Robolectric work - see :core:ui for the detail.
        // InstaraStaleCheckWorker's test needs a real Context for WorkManager and Intent broadcasts.
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // The iOS and jvm targets come from the kmp-android-library convention.
    // Explicit: the manual dependsOn below would otherwise switch the automatic hierarchy off and
    // silently unwire iosMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Dexcom ONE+ lives here rather than in commonMain, and the reason is the desktop client.
        //
        // `:plugins:source` builds for the desktop shell too, but a desktop has no Bluetooth CGM
        // and `:plugins:dexcom_oneplus` has no jvm target at all. A plugin in commonMain would be
        // contributed to the desktop graph as well and would then need a third host that could
        // only refuse. So the ONE+ plugin is shared between the two phones and simply absent on
        // the desktop, which is the truthful answer for that build.
        val androidAppleMain = create("androidAppleMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":plugins:dexcom_oneplus"))
            }
        }
        androidMain.get().dependsOn(androidAppleMain)
        iosMain.get().dependsOn(androidAppleMain)

        // The shared half of the ONE+ tests, so the same assertions run on the JVM host and on the
        // iOS simulator. A test name here must not contain a comma: Kotlin/Native rejects it.
        val androidAppleTest = create("androidAppleTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidHostTest").dependsOn(androidAppleTest)
        iosTest.get().dependsOn(androidAppleTest)

        commonMain {
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:objects"))
                implementation(project(":core:ui"))
                implementation(project(":core:utils"))
                // :ui is multiplatform now, so the shared screens it hosts (ContentContainer) reach here.
                implementation(project(":ui"))

                api(libs.jetbrains.compose.runtime)
                api(libs.jetbrains.compose.foundation)
                api(libs.jetbrains.compose.ui)
                api(libs.jetbrains.compose.material3)
                api(libs.jetbrains.lifecycle.viewmodel.compose)
                api(libs.jetbrains.lifecycle.runtime.compose)
            }
        }

        androidMain {
            dependencies {
                // Fork-only CGM source plugin whose wrapper is still Android only. Libre 3 stays
                // here until it gets a shared caller, the way Dexcom ONE+ just did.
                implementation(project(":plugins:libre3"))

                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.work.runtime)
            }
        }

        // Hand written rather than taken from test-module-dependencies and
        // compose-test-module-dependencies, because both apply com.android.library and so cannot be
        // used by a multiplatform module. Same approach as :plugins:aps.
        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                // InstaraWorkerTest uses kotlin.test assertions; test-module-dependencies used to supply this.
                implementation(kotlin("test"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.work.testing)
                // Robolectric for the InstaraStaleCheckWorker test: it needs a real Android Context
                // (WorkManager.getInstance + Intent broadcasts). The vintage engine bridges its JUnit4
                // runner onto the JUnit Platform alongside the Jupiter tests.
                implementation(libs.org.robolectric)
                // The real org.json: isReturnDefaultValues makes the platform stub answer null rather than
                // throwing, which NPEs the shared profile fixtures in TestBaseWithProfile.
                implementation(libs.org.json.android)
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                runtimeOnly(libs.org.junit.vintage.engine)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}
