plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)

    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.dexcomoneplus"
}

dependencies {
    implementation(project(":core:interfaces"))
    implementation(project(":plugins:libkeks"))
    api(libs.androidx.core)
    implementation(libs.org.slf4j.api)
}
