import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

val buildSqlCipher by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/nativeInterop/sqlcipher"))
    outputs.files(layout.buildDirectory.file("sqlcipher-native/iphoneos/libtrackercipher.a"),
        layout.buildDirectory.file("sqlcipher-native/iphonesimulator/libtrackercipher.a"),
        layout.buildDirectory.file("sqlcipher-native/iphoneos/sqlite3.h"),
        layout.buildDirectory.file("sqlcipher-native/iphonesimulator/sqlite3.h"))
    commandLine("bash", "-c", "src/nativeInterop/sqlcipher/build.sh iphoneos && src/nativeInterop/sqlcipher/build.sh iphonesimulator")
}

kotlin {
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    android {
        namespace = "com.exkmptracker"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        androidResources { enable = true }
        withHostTest {}
    }
    val framework = XCFramework("ExKMPTracker")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val sdk = if (target.name == "iosArm64") "iphoneos" else "iphonesimulator"
        target.compilations.getByName("main").cinterops.create("sqlcipher") {
            defFile(project.file("src/nativeInterop/cinterop/sqlcipher.def"))
            compilerOpts("-I${project.file("src/nativeInterop/sqlcipher")}", "-I${layout.buildDirectory.get().asFile}/sqlcipher-native/$sdk")
            extraOpts("-libraryPath", "${layout.buildDirectory.get().asFile}/sqlcipher-native/$sdk", "-staticLibrary", "libtrackercipher.a")
            tasks.named(interopProcessingTaskName).configure {
                dependsOn(buildSqlCipher)
                inputs.files(file("src/nativeInterop/sqlcipher/sqlcipher_bridge.h"), layout.buildDirectory.file("sqlcipher-native/$sdk/libtrackercipher.a"))
            }
        }
        target.binaries.framework {
            baseName = "ExKMPTracker"
            isStatic = true
            binaryOption("bundleId", "com.exkmptracker")
            framework.add(this)
            export("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            export("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.play.services.location)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
            implementation(libs.koin.android)
        }
    }
}
