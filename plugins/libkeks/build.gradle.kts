plugins {
    alias(libs.plugins.android.library)

    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "jamorham.libkeks"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Vendored from NightscoutFoundation/xDrip pin 1e86d9a2a525… (GPL-3.0). See NOTICE.
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation("junit:junit:4.13.2")
}
