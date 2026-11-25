plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.1" apply false
}

allprojects {
    group = "com.exkmptracker"
    version = "1.1.15"
}
