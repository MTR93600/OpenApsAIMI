import java.util.Properties

plugins {
    // A plain Android library on purpose. The driver talks to the transmitter through Android BLE
    // and uses JVM BouncyCastle for the 365 handshake, so it cannot build for iOS. It is listed in
    // ANDROID_ONLY_PLUGINS in appshell/build.gradle.kts for the same reason, and the iOS app has no
    // Eversense entry at all.
    alias(libs.plugins.android.library)

    id("kotlinx-serialization")
    id("android-module-dependencies")
    id("test-module-dependencies")
}

/**
 * The client secret the Eversense cloud (DMS) asks for at sign in. It is the vendor app's own value,
 * not a user credential, but it is still a secret, so it is never written in the source. It comes
 * from the environment variable EVERSENSE_CLIENT_SECRET, or else from `eversense.clientSecret` in
 * the root local.properties (which git ignores). Without it the build still works and E3 readings
 * still arrive over Bluetooth, but the cloud sign in fails: Eversense 365 cannot finish pairing and
 * the cloud upload does not work. EversensePlugin then shows an urgent notification that says so.
 */
val eversenseClientSecret: String = System.getenv("EVERSENSE_CLIENT_SECRET")
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("eversense.clientSecret")
    }
    ?: ""

android {
    namespace = "app.aaps.plugins.eversense"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val escaped = eversenseClientSecret.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "EVERSENSE_CLIENT_SECRET", "\"$escaped\"")
    }
}

dependencies {
    // No KSP and no Metro: this driver has no dependency injection. :plugins:source reaches it
    // through EversenseCGMPlugin.instance.
    api(libs.androidx.core)
    api(platform(libs.kotlinx.serialization.bom))
    api(libs.kotlinx.serialization.json)

    api(libs.org.slf4j.api)
    api(libs.com.github.tony19.logback.android)

    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
