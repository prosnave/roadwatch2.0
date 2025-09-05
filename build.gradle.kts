plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.13" apply false
    // FIX: Add the Safe Args plugin
    id("androidx.navigation.safeargs.kotlin") version "2.7.7" apply false
    // Hilt plugin
    id("com.google.dagger.hilt.android") version "2.48" apply false
}

tasks.register("clean") {
    delete(rootProject.buildDir)
}
