import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val quasiReleaseNativeDir = rootProject.file("app/src/quasiRelease/jniLibs")
val quasiReleaseExportDir = rootProject.file("quasi-releases")

// ── Release signing ───────────────────────────────────────────────────────────
// Credentials are read from local.properties (never committed to source control).
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}


// ── Auto version counter ──────────────────────────────────────────────────────
// Reads version.properties from the project root.
// VERSION_MAJOR.VERSION_MINOR.VERSION_CODE forms the human-readable version
// name, while Android versionCode is derived so that 0.3.x is always newer
// than 0.2.x regardless of the build counter.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().also { props ->
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { props.load(it) }
}
val verMajor = versionProps.getProperty("VERSION_MAJOR", "0").trim().toIntOrNull() ?: 0
val verMinor = versionProps.getProperty("VERSION_MINOR", "1").trim().toIntOrNull() ?: 1
val verCode  = versionProps.getProperty("VERSION_CODE", "1").trim().toIntOrNull() ?: 1

check(verMajor in 0..999) { "VERSION_MAJOR must be between 0 and 999" }
check(verMinor in 0..999) { "VERSION_MINOR must be between 0 and 999" }
check(verCode in 0..999) { "VERSION_CODE must be between 0 and 999" }

val androidVersionCode = ((verMajor * 1000) + verMinor) * 1000 + verCode
val verName = "$verMajor.$verMinor.$verCode"

// Increment VERSION_CODE and persist only when explicitly requested.
val autoIncrementVersion = providers.gradleProperty("ada.autoIncrementVersion")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

gradle.taskGraph.whenReady {
    if (autoIncrementVersion && allTasks.any { it.name.startsWith("assemble") }) {
        versionProps["VERSION_CODE"] = (verCode + 1).toString()
        versionPropsFile.outputStream().use { versionProps.store(it, "ADA build version counter") }
        logger.lifecycle(
            "ADA version: $verName  (android versionCode=$androidVersionCode, next build will be $verMajor.$verMinor.${verCode + 1})"
        )
    } else {
        logger.lifecycle("ADA version: $verName  (android versionCode=$androidVersionCode)")
    }
}

android {
    namespace = "com.ada.messenger"
    compileSdk = 34
    ndkVersion = "28.0.13004108"

    signingConfigs {
        create("release") {
            val storeFilePath = localProps.getProperty("signing.storeFile")
            storeFile = if (storeFilePath != null) rootProject.file(storeFilePath) else null
            storePassword = localProps.getProperty("signing.storePassword")
            keyAlias      = localProps.getProperty("signing.keyAlias")
            keyPassword   = localProps.getProperty("signing.keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.ada.messenger"
        minSdk = 26          // Android 8.0 — required for modern crypto APIs
        targetSdk = 34
        versionCode = androidVersionCode
        versionName = verName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ABI targets with a packaged libada_core.so. Do not include an ABI
            // unless the Rust JNI library is built for it, or loadLibrary() will
            // crash on devices that select that ABI.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        create("quasiRelease") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += listOf("debug", "release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    // ── Native library ───────────────────────────────────────────────────
    // The Rust build output is placed in jniLibs/ by the cargo-ndk script.
    // Run: cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }

        maybeCreate("quasiRelease").apply {
            jniLibs.srcDirs(quasiReleaseNativeDir)
        }
    }
}

// Pass -Pada.skipNativeBuild=true to reuse the .so already in main/jniLibs
// without re-running cargo-ndk (which requires OpenSSL/MSYS2 on Windows).
val skipNativeBuild = project.findProperty("ada.skipNativeBuild")
    ?.toString()?.equals("true", ignoreCase = true) == true

val prepareQuasiReleaseNativeLibs by tasks.registering(Exec::class) {
    onlyIf {
        !skipNativeBuild &&
        !quasiReleaseNativeDir.resolve("arm64-v8a/libada_core.so").exists()
    }
    group = "build"
    description = "Builds release Rust JNI libs for the debuggable quasiRelease variant."
    workingDir = rootProject.projectDir
    commandLine(
        "powershell",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("build-android.ps1").absolutePath,
        "-Release",
        "-Features",
        "jni-bindings,proto,ffi,bundled-sqlite",
        "-JniLibsDir",
        quasiReleaseNativeDir.absolutePath,
    )
}

tasks.matching { it.name == "mergeQuasiReleaseJniLibFolders" }.configureEach {
    dependsOn(prepareQuasiReleaseNativeLibs)
}

tasks.register<Copy>("exportQuasiReleaseApk") {
    group = "distribution"
    description = "Builds the debuggable, release-sized APK and copies it into the quasi release folder."
    dependsOn("assembleQuasiRelease")
    from(layout.buildDirectory.file("outputs/apk/quasiRelease/app-quasiRelease.apk"))
    into(quasiReleaseExportDir)
    rename { "ADA-$verName-quasiRelease.apk" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.video)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.security.crypto)
    implementation(libs.webrtc.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.ui.text.google.fonts)

    debugImplementation(libs.androidx.ui.tooling)

    // ── Unit tests ────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.test.manifest)
}
