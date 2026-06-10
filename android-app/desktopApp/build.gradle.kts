import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

val repoRootDir = rootProject.projectDir.parentFile
val adaCoreDir = repoRootDir.resolve("ada-core")
val adaCoreManifest = adaCoreDir.resolve("Cargo.toml")
val currentOsName = System.getProperty("os.name", "").lowercase()
val currentOsArch = System.getProperty("os.arch", "").lowercase()
val requestedTaskNames = gradle.startParameter.taskNames.map { it.lowercase() }
val isDesktopDistributionBuild = requestedTaskNames.any {
    it.contains("package") || it.contains("distributable") || it.contains("msi") || it.contains("exe")
}
val adaCoreFeatures = providers.gradleProperty("adaCoreFeatures").orNull
    ?: if (isDesktopDistributionBuild) "mobile" else "mobile-dev"
val adaCoreReleaseProfile = providers.gradleProperty("adaCoreProfile").orNull
    ?.equals("release", ignoreCase = true)
    ?: isDesktopDistributionBuild
val allowDevStorageInDistribution = providers.gradleProperty("adaCoreAllowDevStorage").orNull
    ?.equals("true", ignoreCase = true) == true

if (isDesktopDistributionBuild && adaCoreFeatures.split(',', ' ', ';').any { it == "mobile-dev" } && !allowDevStorageInDistribution) {
    error("Desktop distribution builds must not use ada-core mobile-dev/bundled-sqlite storage. Use -PadaCoreFeatures=mobile or explicitly set -PadaCoreAllowDevStorage=true for a non-production build.")
}

val adaCoreTargetDir = adaCoreDir.resolve("target").resolve(if (adaCoreReleaseProfile) "release" else "debug")
val adaCoreLibraryFile = when {
    currentOsName.contains("win") -> adaCoreTargetDir.resolve("ada_core.dll")
    currentOsName.contains("mac") -> adaCoreTargetDir.resolve("libada_core.dylib")
    else -> adaCoreTargetDir.resolve("libada_core.so")
}
val webrtcDesktopClassifier = when {
    currentOsName.contains("win") && (currentOsArch.contains("amd64") || currentOsArch.contains("x86_64")) -> "windows-x86_64"
    currentOsName.contains("mac") && (currentOsArch.contains("aarch64") || currentOsArch.contains("arm64")) -> "macos-aarch64"
    currentOsName.contains("mac") -> "macos-x86_64"
    currentOsArch.contains("aarch64") || currentOsArch.contains("arm64") -> "linux-aarch64"
    currentOsArch.contains("aarch32") || currentOsArch.contains("arm") -> "linux-aarch32"
    else -> "linux-x86_64"
}
val webrtcDesktopVersion = "0.14.0"

val buildDesktopAdaCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust JNI library used by Compose Desktop."
    workingDir = repoRootDir
    val cargoArgs = mutableListOf(
        "cargo",
        "build",
        "--manifest-path",
        adaCoreManifest.absolutePath,
        "--no-default-features",
        "--features",
        adaCoreFeatures,
    )
    if (adaCoreReleaseProfile) cargoArgs += "--release"
    commandLine(cargoArgs)
    inputs.files(
        fileTree(adaCoreDir) {
            include("Cargo.toml")
            include("Cargo.lock")
            include("build.rs")
            include("src/**")
        },
    )
    outputs.file(adaCoreLibraryFile)
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.desktop)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.zxing.core)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.json)
                implementation(libs.webrtc.desktop)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

dependencies {
    add(
        "desktopMainRuntimeOnly",
        "dev.onvoid.webrtc:webrtc-java:$webrtcDesktopVersion",
    ) {
        artifact {
            classifier = webrtcDesktopClassifier
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn(buildDesktopAdaCore)
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(buildDesktopAdaCore)
    systemProperty("ada.core.lib", adaCoreLibraryFile.absolutePath)
}

// Read version from the same version.properties as the Android app build.
val desktopVersionProps = Properties().also { props ->
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
val desktopVersion = buildString {
    append(desktopVersionProps.getProperty("VERSION_MAJOR", "0").trim())
    append('.')
    append(desktopVersionProps.getProperty("VERSION_MINOR", "1").trim())
    append('.')
    append(desktopVersionProps.getProperty("VERSION_CODE", "1").trim())
}

compose.desktop {
    application {
        mainClass = "com.ada.messenger.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "ADA Messenger"
            packageVersion = desktopVersion
            description = "ADA Messenger — secure messenger with censorship resistance"
            vendor = "ADA"
            windows {
                iconFile.set(project.file("src/desktopMain/resources/ada_icon.ico"))
                dirChooser = true
                menuGroup = "ADA Messenger"
                upgradeUuid = "6F4A2B1C-8D3E-4F5A-9B6C-7E8D9F0A1B2C"
            }
        }
    }
}
