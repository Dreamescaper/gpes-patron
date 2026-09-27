import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

sqldelight {
    databases {
        create("DriveDatabase") {
            packageName.set("gpes.recording.db")
        }
    }
}

dependencies {
    api(project(":core"))
    testImplementation(libs.sqldelight.jvm)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
