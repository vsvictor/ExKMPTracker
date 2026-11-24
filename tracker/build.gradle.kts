import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/vsvictor/ExKMPTracker")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

val buildSqlCipher by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/nativeInterop/sqlcipher"))
    outputs.files(layout.buildDirectory.file("sqlcipher-native/iphoneos/libtrackercipher.a"),
        layout.buildDirectory.file("sqlcipher-native/iphonesimulator/libtrackercipher.a"),
        layout.buildDirectory.file("sqlcipher-native/iphoneos/sqlite3.h"),
        layout.buildDirectory.file("sqlcipher-native/iphonesimulator/sqlite3.h"))
    commandLine("bash", "-c", "src/nativeInterop/sqlcipher/build.sh iphoneos && src/nativeInterop/sqlcipher/build.sh iphonesimulator")
    // The native SQLCipher build requires Xcode toolchains; skip it on Linux/Windows hosts.
    val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    onlyIf("SQLCipher iOS build requires a macOS host") { isMacHost }
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
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.19.1")
            implementation("com.google.android.gms:play-services-location:21.3.0")
            implementation("net.zetetic:sqlcipher-android:4.20.0")
            implementation("androidx.sqlite:sqlite:2.6.2")
        }
    }
}
