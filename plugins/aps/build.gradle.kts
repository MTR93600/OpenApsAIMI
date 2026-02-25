plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.aps"
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
    implementation ("org.tensorflow:tensorflow-lite-metadata:0.1.0")
    implementation ("org.tensorflow:tensorflow-lite-gpu:2.3.0")
    implementation("androidx.core:core-i18n:1.0.0-alpha01")
    
    // 🏥 Health Connect - MTR Steps Integration (Android 14+)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(libs.androidx.swiperefreshlayout)
    api(libs.androidx.gridlayout)
    api(kotlin("reflect"))

    // APS (it should be androidTestImplementation but it doesn't work)
    api(libs.org.mozilla.rhino)

    //Logger
    api(libs.org.slf4j.api)

    testImplementation("io.mockk:mockk:1.13.8")
    ksp(libs.com.google.dagger.android.processor)
    
    // 📺 LiveKit WebRTC (Screen Sharing)
    api("io.livekit:livekit-android:2.3.0")
}