import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- App versioning (single source of truth: app/version.properties) ----
val versionPropsFile = layout.projectDirectory.file("version.properties").asFile
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}

fun currentVersionCode(): Int = (versionProps.getProperty("versionCode") ?: "1").toInt()
fun currentVersionName(): String = versionProps.getProperty("versionName") ?: "0.1.0"

// Auto-bump version on any requested release build
// (assembleRelease / bundleRelease / installRelease — task name contains "Release").
val releaseRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(":").contains("Release", ignoreCase = true)
}
if (releaseRequested) {
    val newCode = currentVersionCode() + 1
    val nameMatch = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(currentVersionName())
        ?: throw GradleException("versionName must be major.minor.patch")
    val newName = "${nameMatch.groupValues[1]}.${nameMatch.groupValues[2]}.${nameMatch.groupValues[3].toInt() + 1}"
    versionProps.setProperty("versionCode", newCode.toString())
    versionProps.setProperty("versionName", newName)
    versionPropsFile.outputStream().use { versionProps.store(it, null) }
    println("Release build: bumped versionName=$newName versionCode=$newCode")
}

android {
    namespace = "com.dji360.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dji360.watch"
        // Watch 4 Pro ships HarmonyOS >= 4; its Android-compat layer reports an SDK_INT
        // that will be measured by this very app (docs/watch-compatibility.md).
        // minSdk 28 covers realistic compat layers while keeping modern APIs usable.
        minSdk = 28
        targetSdk = 34
        versionCode = currentVersionCode()
        versionName = currentVersionName()
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "android"
            keyAlias = "dji-remote"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            // Stable permanent key so sideloaded updates install cleanly over previous builds
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // STEP 3+: coroutines for BLE/protocol flow (pure Kotlin artifact, no androidx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Protocol unit tests against official DJI frame vectors
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
