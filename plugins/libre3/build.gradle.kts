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
    // Deliberately `implementation`, not `api`: nothing in this module's public API uses
    // androidx.core, and exporting it changed how `String.toUri()` resolves in :plugins:source,
    // which made IntelligoPluginTest and GlunovoPluginTest fail on Uri.parse returning null.
    implementation(libs.androidx.core)
}
