import java.net.URI

plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as the :core modules and the other converted plugins.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // Metro, so this module can wire its own Android entry points.
    alias(libs.plugins.metro)
}

// Same generator as the other converted plugins, pointed at this module's strings.
val generateApsStrings = tasks.register<GenerateKeyStringsTask>("generateApsStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.plugins.aps")
    owner.set("aps")
    objectName.set("ApsStrings")
    idsObjectName.set("ApsStringIds")
    reportFile.set(layout.buildDirectory.file("reports/apsStrings/translations.txt"))
    // Set explicitly: addGeneratedSourceDirectory derives its convention from the task name, so both
    // properties would land on one directory and the second file written would delete the first.
    commonOutputDir.set(layout.buildDirectory.dir("generated/apsStrings/common"))
    androidOutputDir.set(layout.buildDirectory.dir("generated/apsStrings/android"))
}

kotlin {
    android {
        namespace = "app.aaps.plugins.aps"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        // isIncludeAndroidResources is what makes Robolectric work - see :core:ui for the detail.
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (Windows/macOS/Linux). Compose Multiplatform resolves its `desktop` variant from a
    // plain jvm() target, so no special target name is needed.
    jvm()

    // Explicit: the manual dependsOn below would otherwise switch the automatic hierarchy off
    // and silently unwire iosMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Hold wiring lives here so the Android variant of :plugins:aps does not depend on
        // :plugins:aimi-engine (that module has no Android target, and the Android tick is unchanged).
        val appleJvmMain = create("appleJvmMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":plugins:aimi-contracts"))
                implementation(project(":plugins:aimi-engine"))
                implementation(project(":database:impl"))
                implementation(project(":database:persistence"))
            }
        }
        jvmMain.get().dependsOn(appleJvmMain)
        iosMain.get().dependsOn(appleJvmMain)

        val appleJvmTest = create("appleJvmTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":plugins:aimi-testkit"))
            }
        }
        jvmTest.get().dependsOn(appleJvmTest)
        jvmTest {
            kotlin.srcDir("src/tfliteParity/kotlin")
            kotlin.srcDir("src/tfliteJvm/kotlin")
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        iosTest.get().dependsOn(appleJvmTest)
        iosTest.get().kotlin.srcDir("src/tfliteParity/kotlin")

        commonMain {
            kotlin.srcDir(generateApsStrings.flatMap { it.commonOutputDir })
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:nssdk"))
                implementation(project(":core:objects"))
                implementation(project(":core:utils"))
                implementation(project(":core:ui"))

                implementation(libs.androidx.collection)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.cmp.runtime)
                api(kotlin("reflect"))
            }
        }

        androidMain {
            // Android only: the string name to R.string id map.
            kotlin.srcDir(generateApsStrings.flatMap { it.androidOutputDir })
            dependencies {
                implementation(project(":core:graph"))
                // TensorFlow Lite for AimiModelHandler's UAM inference. Dropped when upstream rewrote
                // this module for KMP. The model stays: modelUAM.tflite has its own architecture, so
                // re-expressing it would change behaviour on an SMB path. Android only.
                //
                // No tensorflow-lite-gpu: nothing in AimiModelHandler ever constructs a GpuDelegate -
                // grepped, zero hits - and this 2.4.0 (2020) release of it shares AGP's newly enforced
                // unique-namespace check with tensorflow-lite itself (both declare
                // org.tensorflow.lite), which fails :app's manifest merge. The fork's build predates
                // that AGP check. Dropping the unused artifact is the narrow fix; a real GPU delegate,
                // if ever added, would need a newer TFLite release with distinct namespaces.
                implementation("org.tensorflow:tensorflow-lite:2.4.0")
                implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
                implementation("org.tensorflow:tensorflow-lite-metadata:0.1.0")
                // ONNX Runtime for OrefOnnxScorer's hypo/hyper/bg-change classifiers (assets/oref/*.onnx).
                // Same story as TensorFlow Lite above: dropped when upstream rewrote this module for KMP,
                // restored here because the exported models stay. Android only.
                implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
                // Health Connect for AIMIPhysioDataRepositoryMTR's HRV/sleep/temperature/steps read path.
                // Same story again: dropped in the KMP rewrite, restored for the same reason.
                implementation("androidx.health.connect:connect-client:1.1.0")
                implementation(libs.androidx.compose.ui.tooling.preview)
                implementation(libs.androidx.work.runtime)
                implementation(libs.org.slf4j.api)
                // APS (it should be androidTestImplementation but it doesn't work)
                runtimeOnly(libs.org.mozilla.rhino)
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used here. Same approach as :plugins:main.
        // Tests of commonMain classes belong here, not in androidHostTest: that source set runs on the
        // JVM only, so code that ships to iOS would be verified on Android alone. Mockito is JVM
        // only, so anything moved here uses hand written fakes instead.
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("androidHostTest") {
            kotlin.srcDir("src/tfliteParity/kotlin")
            kotlin.srcDir("src/tfliteJvm/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":shared:tests"))
                implementation(project(":pump:virtual"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                // Compose UI tests (AutotuneScreenTest). Restated from compose-test-module-dependencies,
                // which applies com.android.library and so cannot be used here.
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.org.robolectric)
                // The real org.json: isReturnDefaultValues makes the platform stub answer null rather
                // than throwing, which NPEs the shared profile fixtures.
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.mozilla.rhino)
                runtimeOnly(libs.org.junit.vintage.engine)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }

    // TensorFlow Lite C for the two Apple targets. Headers are in the repo. The
    // xcframework is downloaded before the native link, which only runs on macOS.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        if (name != "iosArm64" && name != "iosSimulatorArm64") return@configureEach
        val linkDir = if (name == "iosArm64") "ios-arm64" else "ios-simulator-arm64"
        val include = layout.projectDirectory.dir("src/nativeInterop/cinterop").asFile.absolutePath
        // Kotlin 2.4 cinterop ignores -linker-option. The object has to be named
        // on the binary. iosArm64 links the 2.4.0 device slice (the version the
        // x86_64 runner executes). iosSimulatorArm64 stays on 2.10.0: 2.4.0 has
        // no arm64 simulator slice.
        val objectFile = layout.buildDirectory.file("tflite-c/link/$linkDir/TensorFlowLiteC.o").get().asFile.absolutePath
        compilations.getByName("main").cinterops.create("tflite") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/tflite.def"))
            extraOpts("-compiler-option", "-I$include")
        }
        binaries.configureEach {
            linkerOpts(objectFile, "-lc++")
        }
    }
}

val fetchTensorFlowLiteC = tasks.register("fetchTensorFlowLiteC") {
    val dest = layout.buildDirectory.dir("tflite-c")
    outputs.dir(dest)
    doLast {
        val root = dest.get().asFile
        val marker = root.resolve("TensorFlowLiteC.xcframework/Info.plist")
        val fat24 = root.resolve("TensorFlowLiteC-2.4.0.o")
        if (marker.isFile && fat24.isFile) return@doLast
        root.mkdirs()
        if (!marker.isFile) {
            val tar = root.resolve("TensorFlowLiteC-2.10.0.tar.gz")
            val url = "https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/18/20220909-095119/TensorFlowLiteC/2.10.0/9410f57778559cad/TensorFlowLiteC-2.10.0.tar.gz"
            URI.create(url).toURL().openStream().use { input ->
                tar.outputStream().use { output -> input.copyTo(output) }
            }
            val tarProcess = ProcessBuilder(
                "tar", "-xzf", tar.absolutePath,
                "-C", root.absolutePath,
                "--strip-components=2",
                "TensorFlowLiteC-2.10.0/Frameworks/TensorFlowLiteC.xcframework",
            ).inheritIO().start()
            val tarStatus = tarProcess.waitFor()
            if (tarStatus != 0) error("tar exited $tarStatus")
            tar.delete()
        }
        if (!fat24.isFile) {
            val tar = root.resolve("TensorFlowLiteC-2.4.0.tar.gz")
            val url = "https://dl.google.com/dl/cpdc/e8a95c1d411b795e/TensorFlowLiteC-2.4.0.tar.gz"
            URI.create(url).toURL().openStream().use { input ->
                tar.outputStream().use { output -> input.copyTo(output) }
            }
            val extracted = root.resolve("TensorFlowLiteC-2.4.0/Frameworks/TensorFlowLiteC.framework/TensorFlowLiteC")
            val tarProcess = ProcessBuilder(
                "tar", "-xzf", tar.absolutePath,
                "-C", root.absolutePath,
                "TensorFlowLiteC-2.4.0/Frameworks/TensorFlowLiteC.framework/TensorFlowLiteC",
            ).inheritIO().start()
            val tarStatus = tarProcess.waitFor()
            if (tarStatus != 0) error("tar 2.4.0 exited $tarStatus")
            if (!extracted.isFile) error("TensorFlow Lite C 2.4.0 binary missing after extract")
            extracted.copyTo(fat24, overwrite = true)
            tar.delete()
        }
    }
}

val thinTensorFlowLiteC = tasks.register("thinTensorFlowLiteC") {
    dependsOn(fetchTensorFlowLiteC)
    val dest = layout.buildDirectory.dir("tflite-c/link")
    outputs.dir(dest)
    doLast {
        val root = layout.buildDirectory.dir("tflite-c").get().asFile
        val xc = root.resolve("TensorFlowLiteC.xcframework")
        fun thinArm64(src: java.io.File, outName: String) {
            val outDir = root.resolve("link/$outName")
            outDir.mkdirs()
            val out = outDir.resolve("TensorFlowLiteC.o")
            val lipo = ProcessBuilder(
                "lipo", "-thin", "arm64", src.absolutePath, "-output", out.absolutePath,
            ).inheritIO().start()
            val status = lipo.waitFor()
            if (status != 0) error("lipo -thin arm64 exited $status for ${src.name} -> $outName")
        }
        // Same 2.4.0 framework as the x86_64 simulator runner. This slice is the
        // device binary. It is not executed by iosSimulatorArm64Test.
        thinArm64(root.resolve("TensorFlowLiteC-2.4.0.o"), "ios-arm64")
        thinArm64(
            xc.resolve("ios-arm64_x86_64-simulator/TensorFlowLiteC.framework/TensorFlowLiteC"),
            "ios-simulator-arm64",
        )
    }
}

tasks.configureEach {
    if (name.startsWith("link") && (name.contains("IosArm64") || name.contains("IosSimulatorArm64"))) {
        dependsOn(thinTensorFlowLiteC)
    }
}

