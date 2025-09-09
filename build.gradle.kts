plugins {
    // Android Gradle Plugin compatible with Gradle 9.0
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false

    // Kotlin + KSP aligned versions
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false

    // Safe Args plugin (compatible across AGP 8.x)
    id("androidx.navigation.safeargs.kotlin") version "2.7.7" apply false

    // Hilt plugin
    id("com.google.dagger.hilt.android") version "2.48" apply false
}

tasks.register("clean") {
    delete(rootProject.buildDir)
}
