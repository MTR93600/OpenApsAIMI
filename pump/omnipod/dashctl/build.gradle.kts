plugins {
    alias(libs.plugins.android.library)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.omnipod.dashctl"
    sourceSets {
        getByName("main") {
            kotlin.directories.add("src/commonMain/kotlin")
            kotlin.directories.add("src/androidMain/kotlin")
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.directories.add("src/jvmTest/kotlin")
        }
    }
}

dependencies {

    api(platform(libs.kotlinx.coroutines.bom))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.com.google.truth)
    testImplementation(libs.commons.codec)

    testImplementation(libs.io.kotlintest.runner.junit5)
    testRuntimeOnly(libs.org.junit.jupiter.engine)
}
