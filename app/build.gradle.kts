import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
// 10.0.2.2 is the Android emulator's alias for "localhost of the machine
// running the emulator" - so this default reaches a server started with
// `npm run dev` on the same computer as Android Studio, no config needed.
// A real device or a deployed server needs a real address here, set in
// local.properties (which is gitignored).
val appServerUrl = localProperties.getProperty("APP_SERVER_URL") ?: "http://10.0.2.2:3000/"
// same emulator-alias convention, pointing at inference-server/ (Python +
// Ultralytics + FastAPI) instead of Roboflow's paid serverless API.
val detectionServerUrl = localProperties.getProperty("DETECTION_SERVER_URL")
    ?: "http://10.0.2.2:8001/detect"

android {
    namespace = "com.example.rummikubsolver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.rummikubsolver"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


        buildConfigField("String", "APP_SERVER_URL", "\"$appServerUrl\"")
        buildConfigField("String", "DETECTION_SERVER_URL", "\"$detectionServerUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
val sdkDir: File = android.sdkDirectory
val adbExecutableName = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
val adbPath: String = File(sdkDir, "platform-tools/$adbExecutableName").absolutePath
val adbSerial: String? = findProperty("adbSerial") as String?

val adbDevicesOutput = ByteArrayOutputStream().also { out ->
    exec {
        commandLine(adbPath, "devices")
        standardOutput = out
        isIgnoreExitValue = true
    }
}.toString("UTF-8")

val connectedDevices = adbDevicesOutput.lineSequence()
    .filter { it.endsWith("\tdevice") }
    .map { it.substringBefore('\t') }
    .toList()

tasks.register<Exec>("adbReverse") {
    isIgnoreExitValue = true

    onlyIf {
        when {
            connectedDevices.isEmpty() -> {
                println("adbReverse: no connected device/emulator. Skipping.")
                false
            }
            adbSerial == null && connectedDevices.size > 1 -> {
                println("adbReverse: multiple devices found ${connectedDevices}. " +
                        "Pass -PadbSerial=<serial> to target a specific one. Skipping.")
                false
            }
            else -> true
        }
    }

    val target = adbSerial ?: connectedDevices.firstOrNull()
    if (target != null) {
        println("adbReverse: using adb at $adbPath, target serial: $target")
        if (adbSerial != null) {
            commandLine(adbPath, "-s", target, "reverse", "tcp:3000", "tcp:3000")
        } else {
            commandLine(adbPath, "reverse", "tcp:3000", "tcp:3000")
        }
    } else {
        commandLine(adbPath, "devices") // no-op placeholder — onlyIf already skips this case
    }

    // Exec only runs one commandLine, so the second forwarded port (the
    // detection server, tcp:8001) is a second exec call here in doLast -
    // same detected target/serial as above, same ignore-failure behavior.
    // Node server stays on 3000, detection server moved off 8000 (already
    // bound by an unrelated Docker container on this machine) to 8001.
    doLast {
        if (target != null) {
            exec {
                isIgnoreExitValue = true
                if (adbSerial != null) {
                    commandLine(adbPath, "-s", target, "reverse", "tcp:8001", "tcp:8001")
                } else {
                    commandLine(adbPath, "reverse", "tcp:8001", "tcp:8001")
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("adbReverse")
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}