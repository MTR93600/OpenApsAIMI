
import org.gradle.kotlin.dsl.debugImplementation
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

// Fixes errors in KSP task dependency
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    id("com.android.application")
    // kotlin("android")
    // kotlin("kapt")
    //alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("android-app-dependencies")
    id("test-app-dependencies")
    id("jacoco-app-dependencies")
}

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")
}

fun DependencyHandler.`kapt`(dependencyNotation: Any): Dependency? =
    add("kapt", dependencyNotation)

// -----------------------------------------------------------------------------
// Fonctions personnalisées
// -----------------------------------------------------------------------------
fun generateGitBuild(): String {
    try {
        val processBuilder = ProcessBuilder("git", "describe", "--always")
        val output = File.createTempFile("git-build", "")
        processBuilder.redirectOutput(output)
        val process = processBuilder.start()
        process.waitFor()
        return output.readText().trim()
    } catch (_: Exception) {
        return "NoGitSystemAvailable"
    }
}

fun generateGitRemote(): String {
    try {
        val processBuilder = ProcessBuilder("git", "remote", "get-url", "origin")
        val output = File.createTempFile("git-remote", "")
        processBuilder.redirectOutput(output)
        val process = processBuilder.start()
        process.waitFor()
        return output.readText().trim()
    } catch (_: Exception) {
        return "NoGitSystemAvailable"
    }
}

fun generateDate(): String {
    val stringBuilder: StringBuilder = StringBuilder()
    // showing only date prevents app to rebuild everytime
    stringBuilder.append(SimpleDateFormat("yyyy.MM.dd").format(Date()))
    return stringBuilder.toString()
}

fun isMaster(): Boolean = !Versions.appVersion.contains("-")

fun gitAvailable(): Boolean {
    try {
        val processBuilder = ProcessBuilder("git", "--version")
        val output = File.createTempFile("git-version", "")
        processBuilder.redirectOutput(output)
        val process = processBuilder.start()
        process.waitFor()
        return output.readText().isNotEmpty()
    } catch (_: Exception) {
        return false
    }
}

fun allCommitted(): Boolean {
    try {
        val processBuilder = ProcessBuilder("git", "status", "-s")
        val output = File.createTempFile("git-comited", "")
        processBuilder.redirectOutput(output)
        val process = processBuilder.start()
        process.waitFor()
        return output.readText().replace(Regex("""(?m)^\s*(M|A|D|\?\?)\s*.*?\.idea\/codeStyles\/.*?\s*$"""), "")
            // ignore all files added to project dir but not staged/known to GIT
            .replace(Regex("""(?m)^\s*(\?\?)\s*.*?\s*$"""), "").trim().isEmpty()
    } catch (_: Exception) {
        return false
    }
}

// -----------------------------------------------------------------------------
// Configuration Android
// -----------------------------------------------------------------------------
android {
    compileSdk = 36
    // Si tu n'as pas de variable pour compileSdk, mets-le en dur, ex. 34
    // compileSdk = Versions.compileSdk

    namespace = "app.aaps"

    defaultConfig {
        // Remplace par des valeurs fixes si besoin (ex. 21, 34, etc.)
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk

        buildConfigField("String", "VERSION", "\"$version\"")
        buildConfigField("String", "BUILDVERSION", "\"${generateGitBuild()}-${generateDate()}\"")
        buildConfigField("String", "REMOTE", "\"${generateGitRemote()}\"")
        buildConfigField("String", "HEAD", "\"${generateGitBuild()}\"")
        buildConfigField("String", "COMMITTED", "\"${allCommitted()}\"")

        // For Dagger injected instrumentation tests in app module
        testInstrumentationRunner = "app.aaps.runners.InjectedTestRunner"
    }

    flavorDimensions.add("standard")
    productFlavors {
        create("full") {
            isDefault = true
            applicationId = "info.nightscout.androidaps"
            dimension = "standard"
            resValue("string", "app_name", "AAPS")
            versionName = Versions.appVersion
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_round"
        }
        create("pumpcontrol") {
            applicationId = "info.nightscout.aapspumpcontrol"
            dimension = "standard"
            resValue("string", "app_name", "Pumpcontrol")
            versionName = Versions.appVersion + "-pumpcontrol"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_pumpcontrol"
            manifestPlaceholders["appIconRound"] = "@null"
        }
        create("aapsclient") {
            applicationId = "info.nightscout.aapsclient"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_yellowowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_yellowowl"
        }
        create("aapsclient2") {
            applicationId = "info.nightscout.aapsclient2"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient2")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_blueowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_blueowl"
        }
        create("aapsclient3") {
            applicationId = "info.nightscout.aapsclient3"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient3")
            versionName = Versions.appVersion + "-aapsclient3"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_greenowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_greenowl"
        }
    }

    // -------------------------------------------------------------------------
    // Configuration de signature (release)
    // -------------------------------------------------------------------------
    signingConfigs {
        // On peut l'appeler "release" ou un autre nom
        create("release") {
            storeFile file(System.getenv("SIGNING_STORE_FILE") ?: 'dummy.jks')
        storePassword System.getenv("SIGNING_STORE_PASSWORD") ?: ''
        keyAlias System.getenv("SIGNING_KEY_ALIAS") ?: ''
        keyPassword System.getenv("SIGNING_KEY_PASSWORD") ?: ''
        }
    }

    // -------------------------------------------------------------------------
    // Build Types
    // -------------------------------------------------------------------------
    buildTypes {
        getByName("release") {
            // Active ou non le minify
            // minifyEnabled true
            // shrinkResources true

            // Associe la config "release"
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            // config debug
        }
    }

    useLibrary("org.apache.http.legacy")

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    sourceSets {
        getByName("full") { kotlin.directories.add("src/withPumps/kotlin") }
        getByName("pumpcontrol") { kotlin.directories.add("src/withPumps/kotlin") }
        getByName("aapsclient2") { kotlin.directories.add("src/aapsclient/kotlin") }
        getByName("aapsclient3") { kotlin.directories.add("src/aapsclient/kotlin") }
    }
}

// -----------------------------------------------------------------------------
// allprojects / repositories
// -----------------------------------------------------------------------------
allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

// -----------------------------------------------------------------------------
// Dependencies
// -----------------------------------------------------------------------------
dependencies {
    // in order to use internet"s versions you"d need to enable Jetifier again
    // https://github.com/nightscout/iconify.git
    implementation(project(":shared:impl"))
    implementation(project(":core:data"))
    implementation(project(":core:objects"))
    implementation(project(":core:graph"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":ui"))
    implementation(project(":plugins:aps"))
    implementation(project(":plugins:automation"))
    implementation(project(":plugins:configuration"))
    implementation(project(":plugins:constraints"))
    implementation(project(":plugins:main"))
    implementation(project(":plugins:sensitivity"))
    implementation(project(":plugins:smoothing"))
    implementation(project(":plugins:source"))
    implementation(project(":plugins:sync"))
    implementation(project(":implementation"))
    implementation(project(":database:impl"))
    implementation(project(":database:persistence"))
    implementation(project(":pump:virtual"))
    implementation(project(":workflow"))

    // Pump drivers — only for full + pumpcontrol flavors
    val pumpDependencies = listOf(
        ":pump:combov2",
        ":pump:dana",
        ":pump:danars",
        ":pump:danars-emulator",
        ":pump:danar",
        ":pump:danar-emulator",
        ":pump:diaconn",
        ":pump:eopatch",
        ":pump:medtrum",
        ":pump:equil",
        ":pump:equil-emulator",
        ":pump:insight",
        ":pump:medtronic",
        ":pump:common",
        ":pump:omnipod:common",
        ":pump:omnipod:eros",
        ":pump:omnipod:dash",
        ":pump:rileylink"
    )
    pumpDependencies.forEach {
        "fullImplementation"(project(it))
        "pumpcontrolImplementation"(project(it))
    }

    implementation(libs.androidx.lifecycle.process)

    testImplementation(project(":shared:tests"))
    androidTestImplementation(project(":shared:tests"))
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.org.skyscreamer.jsonassert)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Rhino is needed by the openAPS adapter test fixtures under app/src/androidTest
    // (these files reference org.mozilla.javascript.* classes directly).
    androidTestImplementation(libs.org.mozilla.rhino)

    debugImplementation(libs.com.squareup.leakcanary.android)

    /* Dagger2 - We are going to use dagger.android which includes
     * support for Activity and fragment injection so we need to include
     * the following dependencies */
    ksp(libs.com.google.dagger.android.processor)
    kspAndroidTest(libs.com.google.dagger.android.processor)
    ksp(libs.com.google.dagger.compiler)
    implementation(libs.com.google.dagger.hilt.android)
    ksp(libs.com.google.dagger.hilt.compiler)

    // MainApp
    api(libs.com.uber.rxdogtag2.rxdogtag)
    // MPAndroidChart for comparator
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    // Remote config
    api(libs.com.google.firebase.config)
    // Navigation Compose
    api(libs.androidx.compose.navigation)
}

// -----------------------------------------------------------------------------
// Dernières lignes (messages console)
// -----------------------------------------------------------------------------
println("-------------------")
println("isMaster: ${isMaster()}")
println("gitAvailable: ${gitAvailable()}")
println("allCommitted: ${allCommitted()}")
println("-------------------")
if (!gitAvailable()) {
    throw GradleException("GIT system is not available. On Windows try to run Android Studio as an Administrator. Check if GIT is installed and Studio have permissions to use it")
}

/*if (isMaster() && !allCommitted()) {
    throw GradleException("There are uncommitted changes.")
}*/
