plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

// Apex TruCare III driver. Fork work in progress: it compiles, but it is NOT a dependency of :app
// (see pumpExclusions in app/build.gradle.kts), so it is never registered and no user can select it.
// Android only: there is no CoreBluetooth driver for this pump.

android {
    namespace = "app.aaps.pump.apex"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":pump:common"))
    testImplementation(project(":shared:tests"))
}
