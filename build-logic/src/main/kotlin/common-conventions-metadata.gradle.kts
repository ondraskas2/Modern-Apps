import com.android.build.api.dsl.ApplicationExtension
import org.gradle.accessors.dm.LibrariesForLibs

// Wires up on-device app-store screenshot generation.
//
// Apply this ALONGSIDE `common-conventions-app` on any app module that has a
// screenshot generator under `src/androidTest`. It registers a `metadata` task:
//
//     ./gradlew :clock:metadata
//
// which installs the app + its instrumented screenshot generator on a connected
// emulator/device, runs it, and pulls the resulting PNGs into
// `metadata_data/photos/<module-key>/` (where release.sh picks them up).
//
// Modules without a `src/androidTest` screenshot generator simply won't produce
// any images, so applying this plugin is harmless.

val libs = the<LibrariesForLibs>()

configure<ApplicationExtension> {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    add("androidTestImplementation", libs.junit)
    add("androidTestImplementation", libs.androidx.test.runner)
    add("androidTestImplementation", libs.androidx.test.ext.junit)
    add("androidTestImplementation", platform(libs.androidx.compose.bom))
    add("androidTestImplementation", libs.androidx.compose.ui.test.junit4)
    add("debugImplementation", libs.androidx.compose.ui.test.manifest)
}

val moduleKey = path.removePrefix(":").replace(":", "-")
val screenshotsOut = File(rootDir, "metadata_data/photos/$moduleKey")

fun resolveAdb(): String {
    val localProps = File(rootDir, "local.properties")
    val sdkDir = if (localProps.exists()) {
        java.util.Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else null
    val sdk = sdkDir
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    return File(sdk, "platform-tools/adb").absolutePath
}

val metadataTask = tasks.register<Exec>("metadata") {
    group = "metadata"
    description = "Generate Play/F-Droid screenshots on a connected emulator and copy them into metadata_data/photos/$moduleKey"
    dependsOn("installDebug", "installDebugAndroidTest")
    // Placeholder; the real command is assembled in afterEvaluate below, once the
    // applicationId is known, so the task only holds plain serializable strings
    // (required for the configuration cache).
    commandLine("true")
}

// Everything is resolved at configuration time into plain Strings so the Exec
// task stays configuration-cache compatible (no project/extension access at
// execution time). To target a specific emulator without touching other
// connected devices (e.g. a physical phone), export ANDROID_SERIAL before
// running -- both AGP's install tasks and adb honour it.
afterEvaluate {
    val appId = the<ApplicationExtension>().defaultConfig.applicationId
        ?: error("applicationId is not set for $path; the metadata task needs it to locate the app on device")
    val adb = resolveAdb()
    val runner = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
    val deviceDir = "/sdcard/Android/data/$appId/files/metadata_screenshots"
    val out = screenshotsOut.absolutePath

    // Pre-grant the permissions/appops that MainActivity would otherwise bounce
    // the user to system settings for on first launch (so those settings screens
    // don't hijack the screenshots), and switch the device to night mode so the
    // app's DynamicTheme renders dark. The device is restored to light after.
    val script = """
        set -e
        "$adb" shell pm clear $appId || true
        "$adb" shell pm grant $appId android.permission.POST_NOTIFICATIONS || true
        "$adb" shell appops set $appId SCHEDULE_EXACT_ALARM allow || true
        "$adb" shell appops set $appId USE_FULL_SCREEN_INTENT allow || true
        "$adb" shell cmd uimode night yes || true
        "$adb" shell rm -rf "$deviceDir" || true
        "$adb" shell am instrument -w "$runner"
        rm -rf "$out"
        mkdir -p "$out"
        "$adb" pull "$deviceDir/." "$out"
        "$adb" shell cmd uimode night no || true
        echo "Metadata screenshots written to $out"
    """.trimIndent()

    metadataTask.configure {
        commandLine("bash", "-c", script)
    }
}
