import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    android {
        namespace = "com.exkmptracker.http"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        withHostTest {}
    }
    val framework = XCFramework("ExKMPTrackerHttp")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ExKMPTrackerHttp"
            isStatic = true
            framework.add(this)
            export(project(":tracker"))
            export("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            export("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":tracker"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("io.ktor:ktor-client-core:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.1")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.1") }
    }
}
