import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs.kotlin")
    // id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.roadwatch.app"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    // Load API key from local.properties
    val localProperties = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { inputStream ->
                load(inputStream)
            }
        }
    }
    val mapsApiKey = localProperties.getProperty("MAPS_API_KEY", "")

    defaultConfig {
        applicationId = "com.roadwatch"
        minSdk = 26
        targetSdk = 33  // Temporarily lowered to ensure compatibility
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Restore MAPS_API_KEY placeholder
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        // Deterministic base name for all APKs
        setProperty("archivesBaseName", "RoadWatch")
    }

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Enable Compose
    buildFeatures {
        buildConfig = true
        compose = false
    }

    // Pin a Compose compiler compatible with Kotlin 1.9.10.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    flavorDimensions.add("version")
    productFlavors {
        create("public") {
            dimension = "version"
            applicationId = "com.roadwatch.app"
            buildConfigField("Boolean", "IS_ADMIN", "false")
            resValue("string", "app_name", "RoadWatch")
        }
        create("admin") {
            dimension = "version"
            applicationId = "com.roadwatch.app.admin"
            buildConfigField("Boolean", "IS_ADMIN", "true")
            resValue("string", "app_name", "RoadWatch Admin")
        }
    }


}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    // Avoid adding new network-resolved deps in this environment
}

// Note: Rely on default AGP output naming using the base name set above.
