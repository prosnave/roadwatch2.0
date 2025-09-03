import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs.kotlin")
    id("com.google.dagger.hilt.android")
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
        applicationId = "com.roadwatch.app"
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
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
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.48")
    
    ksp("com.google.dagger:hilt-compiler:2.48")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt Compose
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coroutines (compatible with Kotlin 1.9.24)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // TTS
    implementation("androidx.media:media:1.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Compose Testing
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
