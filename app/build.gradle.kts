plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "home.screen_to_chromecast" // Matches the package name in AndroidManifest.xml
    compileSdk = 34 // Target latest stable SDK

    defaultConfig {
        applicationId = "home.screen_to_chromecast"
        minSdk = 26 // Android 8.0 (Oreo) - Good baseline for MediaProjection & LibVLC
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Keep false for initial development/debugging
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true // Enable ViewBinding for easier UI interaction
    }
}

dependencies {
    // Core AndroidX libraries
    implementation("androidx.core:core-ktx:1.13.1") // Kotlin extensions
    implementation("androidx.appcompat:appcompat:1.6.1") // AppCompat support library
    implementation("com.google.android.material:material:1.12.0") // Material Design components
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // ConstraintLayout

    // LibVLC for Android
    // Trying an older stable version to troubleshoot CI resolution.
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0") // For lifecycleScope

    // Activity KTX & Fragment KTX
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")

    // NanoHTTPD for HLS streaming
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Testing libraries
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
