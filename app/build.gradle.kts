plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "gpes.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "gpes.patron"
        minSdk = 29
        targetSdk = 36
        // CI passes -PversionCode=<run number> so installed builds always upgrade.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "0.1.0" + (providers.gradleProperty("versionCode").orNull?.let { "-ci$it" } ?: "")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
