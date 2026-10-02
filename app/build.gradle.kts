import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---- Version from git, nothing to edit by hand (D-059). The model of Nerdbank.GitVersioning, without .NET:
//   version.json   {"version": "MAJOR.MINOR", "versionCodeOffset": N}  (change MAJOR.MINOR to release a new line)
//   versionName    MAJOR.MINOR.<height>+<short sha>[.dirty]   height = commits since version.json last changed
//   versionCode    versionCodeOffset + number of commits on HEAD (grows with every commit, so installs upgrade)
// It needs the full history: a shallow clone gives wrong numbers, so CI fetches everything and a shallow clone fails there.
fun git(vararg args: String): String? {
    val out = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim() else null
}

val versionConfig = JsonSlurper().parse(rootProject.file("version.json")) as Map<*, *>
val versionBase = versionConfig["version"] as String
val versionCodeOffset = (versionConfig["versionCodeOffset"] as Number).toInt()
val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull()
if (gitCommitCount != null && git("rev-parse", "--is-shallow-repository") == "true" && System.getenv("CI") != null) {
    throw GradleException("Shallow git clone: the version is computed from the commit count. Fetch the full history (actions/checkout fetch-depth: 0).")
}
val gitVersionCode = versionCodeOffset + (gitCommitCount ?: 0)
val gitVersionName = if (gitCommitCount == null) "$versionBase.0+nogit" else {
    val versionFileCommit = git("log", "-1", "--format=%H", "--", "version.json")?.takeIf { it.isNotEmpty() }
    val height = versionFileCommit?.let { git("rev-list", "--count", "$it..HEAD")?.toIntOrNull() } ?: 0
    val sha = git("rev-parse", "--short=7", "HEAD").orEmpty()
    val dirty = if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) "" else ".dirty"
    "$versionBase.$height+$sha$dirty"
}

android {
    namespace = "gpes.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "gpes.patron"
        minSdk = 29
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
    }

    signingConfigs {
        // CI (and anyone who wants stable signatures) points GPES_DEBUG_KEYSTORE at a shared debug
        // keystore, so APKs from different machines install over each other.
        getByName("debug") {
            System.getenv("GPES_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // The upload key for Google Play (Play App Signing re-signs what we upload). It is never in the repository: CI restores it
    // from secrets, a developer points these variables at their own keystore (docs/play/release-checklist.md). Without them
    // the release build is left unsigned on purpose, never signed with the debug key.
    val uploadKeystore = System.getenv("GPES_UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (uploadKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(uploadKeystore)
            storePassword = System.getenv("GPES_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("GPES_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("GPES_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    lint {
        // Mock location permission is intentional for this research app.
        disable += setOf("MockLocation", "ProtectedPermissions")
    }
}

dependencies {
    implementation(project(":recording"))
    implementation(libs.sqldelight.android)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
    implementation(libs.play.location)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
}

// For scripts and CI: ./gradlew -q :app:printVersion  →  "0.1.3+a1b2c3d (237)"
tasks.register("printVersion") {
    val text = "$gitVersionName ($gitVersionCode)"
    doLast { println(text) }
}
