import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Play-ready signing hook: set these on the build machine only, never in Git.
val releaseKeystorePath = System.getenv("LUBELINK_RELEASE_KEYSTORE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = System.getenv("LUBELINK_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("LUBELINK_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("LUBELINK_RELEASE_KEY_PASSWORD")
val releaseSigningInputs = listOf(releaseKeystorePath, releaseStorePassword,
    releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningInputs.all { !it.isNullOrBlank() }
if (releaseSigningInputs.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    error("Incomplete release signing environment: all four LUBELINK_RELEASE_* variables are required")
}

// The Android SDK jar Robolectric runs every JVM test on (API 36, robolectric.properties).
val robolectricSdk36: Configuration by configurations.creating { isTransitive = false }
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdk")
val robolectricSdkJars = tasks.register<Sync>("robolectricSdkJars") {
    from(robolectricSdk36)
    into(robolectricSdkDir)
}

android {
    namespace = "com.kramttocs.lubelink"
    compileSdk = 36
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Screenshots from the walkthrough test are written through the test storage service and copied by
        // connectedDebugAndroidTest into build/outputs/connected_android_test_additional_output/.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        applicationId = "com.kramttocs.lubelink"
        minSdk = 30 // Android 11 and later (OD-81)
        targetSdk = 36
        versionCode = 46
        versionName = "1.0.2"
    }
    // No signing passwords or private keys are stored in this repository.
    // Release signing is opt-in through the developer's local environment.
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        // Debug builds have their own application ID, so they install beside the release app.
        getByName("debug") {
            applicationIdSuffix = ".review"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Compose screen tests live in src/test/ui and run twice: on the JVM under Robolectric in every unit-test run,
    // and on the emulator in full CI runs.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's Android 36 sandbox on JDK 21 (test runtime only; no check loosened).
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            it.maxHeapSize = "2g"
            // The JVM screen tour runs only when asked for: -Plubelink.screenTour=true
            it.systemProperty("lubelink.screenTour", providers.gradleProperty("lubelink.screenTour").getOrElse("false"))
            // Robolectric reads its Android SDK jar from the folder Gradle fills, never from the network during a
            // run: once tests have installed Android's security provider in the JVM, a download fails its
            // certificate check.
            it.dependsOn(robolectricSdkJars)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
        }
    }
    sourceSets {
        // kotlin.srcDir, not java.srcDir: an extra java.srcDir is ignored for Kotlin sources.
        getByName("test").kotlin.srcDir("src/test/ui/java")
        getByName("androidTest").kotlin.srcDir("src/test/ui/java")
        getByName("test").resources.srcDir("src/test/ui/resources")
        getByName("androidTest").resources.srcDir("src/test/ui/resources")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    robolectricSdk36("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Stable OpenGL-backed native MapLibre; hosted tiles require no user API key.
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Runs the real reminder worker in tests (TestListenableWorkerBuilder), on the JVM and the emulator.
    testImplementation("androidx.work:work-testing:2.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("androidx.test.services:storage:1.6.0")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}

// Normal assembleDebug keeps its default output for Android Studio and adb.
// This explicit task also creates a versioned filename for human distribution.
tasks.register<Copy>("packageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.buildDirectory.dir("distributions"))
    rename { "LubeLink-v${android.defaultConfig.versionName}-debug.apk" }
}

// For Play internal testing, build an Android App Bundle with locally supplied
// release signing variables. Keep the upload key outside source control.
tasks.register<Copy>("exportReleaseBundle") {
    dependsOn("bundleRelease")
    onlyIf { releaseSigningConfigured }
    from(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
    into(rootProject.layout.buildDirectory.dir("distributions"))
    rename { "LubeLink-v${android.defaultConfig.versionName}-release.aab" }
}


// SAFETY GATE: connected Android tests install/uninstall packages and use
// synthetic data. The .review suffix keeps them off the working app, but they still
// belong on a disposable emulator. Refuse test execution if ADB sees a real device
// (even when it is unauthorized/offline), multiple emulators, or no emulator at all.
val verifyEmulatorOnly by tasks.registering {
    group = "verification"
    description = "Fail closed unless ADB sees exactly one running Android emulator"
    doLast {
        val localProperties = rootProject.file("local.properties")
        val sdkFromLocal = if (localProperties.isFile) {
            val props = Properties()
            localProperties.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")
        } else null
        val sdkRoots = listOfNotNull(sdkFromLocal, System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT")).distinct()
        val adbName = if (System.getProperty("os.name").contains("Windows", true)) "adb.exe" else "adb"
        val adb = sdkRoots.map { file("$it/platform-tools/$adbName") }.firstOrNull { it.isFile }
            ?.absolutePath ?: adbName
        val process = try { ProcessBuilder(adb, "devices").redirectErrorStream(true).start() }
            catch (e: Exception) { throw GradleException("Cannot verify ADB device isolation; instrumented tests refused. Check Android SDK/platform-tools.", e) }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw GradleException("ADB device check timed out; instrumented tests refused")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "ADB device check failed; instrumented tests refused: $output" }
        val devices = output.lineSequence().dropWhile { !it.startsWith("List of devices attached") }
            .drop(1).map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("*") }.toList()
        check(devices.size == 1 && Regex("emulator-[0-9]+\\s+device").matches(devices.single())) {
            "SAFETY STOP: connected Android tests must run on one disposable emulator. " +
                "Disconnect the Pixel (USB AND wireless debugging), start ONE disposable emulator, " +
                "and verify adb devices lists only that emulator. Observed: $devices"
        }
        println("SAFE TO TEST: exactly one emulator connected; no physical phones in ADB")
    }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(verifyEmulatorOnly)
}
