import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    android {
        namespace = "com.exkmptracker.vision"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    val framework = XCFramework("ExKMPTrackerVision")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ExKMPTrackerVision"
            isStatic = true
            binaryOption("bundleId", "com.exkmptracker.vision")
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
        }
        androidMain.dependencies {
            api("com.google.ai.edge.litert:litert:1.4.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
