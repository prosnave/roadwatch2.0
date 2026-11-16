import java.text.SimpleDateFormat
import java.util.Date
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

    // Identifies exactly which build produced a trip log / bug report. Field builds pass -PrwVersionCode.
    fun git(vararg args: String): String = try {
        val p = ProcessBuilder("git", *args).directory(rootProject.projectDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }
    val gitSha = git("rev-parse", "--short", "HEAD").ifBlank { "nogit" }
    val dirty = if (git("status", "--porcelain", "--", "app/src").isNotBlank()) "+local" else ""
    val buildStamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())

    defaultConfig {
        applicationId = "com.roadwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("rwVersionCode") as String?)?.toInt() ?: 2
        versionName = "1.2.0"
        buildConfigField("String", "BUILD_ID", "\"$buildStamp-$gitSha$dirty\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Restore MAPS_API_KEY placeholder
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        // Deterministic base name for all APKs
        setProperty("archivesBaseName", "RoadWatch")
    }

    // Release builds are signed with the same debug certificate as the APKs already on the tester's
    // phone, so installs UPDATE in place and keep the field data. Replace with a real upload/release key
    // (kept outside the repo) before any Play Store or wider distribution; that forces a one-time reinstall.
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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

    // Enable Compose (currently disabled for MVP)
    buildFeatures {
        buildConfig = true
        compose = false
    }

    // Compose compiler version not used when compose=false; kept for future.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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

    testImplementation("junit:junit:4.13.2")
}

// Note: Rely on default AGP output naming using the base name set above.
