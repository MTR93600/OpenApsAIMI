plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.main"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("boolean", "AIMI_DASHBOARD_LEGACY_GRAPH_FALLBACK", "false")
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:graph"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":shared:impl"))
    implementation(project(":plugins:aps"))  // For AIMI Auditor UI components
    implementation(project(":ui")) // GraphViewModel + BgGraphCompose (Vico) for dashboard graph

    testImplementation(project(":implementation"))
    testImplementation(project(":plugins:aps"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(libs.androidx.fragment)
    api(libs.com.google.android.material)

    // Compose
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.compose.runtime.livedata)
    api(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Actions
    api(libs.androidx.gridlayout)

    //SmsCommunicator
    api(libs.com.eatthepath.java.otp)
    api(libs.com.github.kenglxn.qrgen.android)

    api(libs.kotlinx.datetime)

    // Overview
    api(libs.com.google.android.flexbox)

    // Food
    api(libs.androidx.work.runtime)

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.hilt.compiler)
    ksp(libs.com.google.dagger.android.processor)
}