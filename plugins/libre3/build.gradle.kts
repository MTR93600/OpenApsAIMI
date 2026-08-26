plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)

    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.libre3"
}

dependencies {
    implementation(project(":core:interfaces"))
    api(libs.androidx.core)
    implementation(libs.org.slf4j.api)
}
