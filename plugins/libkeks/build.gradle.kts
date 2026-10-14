plugins {
    alias(libs.plugins.android.library)

    id("android-module-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "jamorham.libkeks"
    // Java version comes from android-module-dependencies (this branch: 21).
    // Do not force 17: the rest of the app compiles with 21.
}

dependencies {
    // Vendored from NightscoutFoundation/xDrip pin 1e86d9a2a525… (GPL-3.0). See NOTICE.
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    // This module has no project dependency, so nothing brings the coroutines BOM in for it. The
    // shared "test-module-dependencies" plugin adds kotlinx-coroutines-test without a version,
    // because the BOM is meant to supply it, so the test classpath could not resolve at all.
    testImplementation(platform(libs.kotlinx.coroutines.bom))
    testImplementation("junit:junit:4.13.2")
}
