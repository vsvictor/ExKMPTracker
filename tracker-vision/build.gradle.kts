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
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.koin.core)
        }
        androidMain.dependencies {
            api(libs.litert)
            api(libs.litert.gpu)
            api(libs.litert.gpu.api)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
    }
}
