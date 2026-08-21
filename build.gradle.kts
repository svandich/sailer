plugins {
    // AGP 9+ has built-in Kotlin support (https://kotl.in/gradle/agp-built-in-kotlin);
    // a separate org.jetbrains.kotlin.android plugin is neither needed nor allowed.
    // AGP 9.3.1 pins Kotlin Gradle Plugin 2.2.10 by default.
    id("com.android.application") version "9.3.1" apply false
}
