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

        // For LibVLC, if you encounter issues with native libraries,
        // you might need to specify ABI filters.
        // For now, let's assume LibVLC handles this or we can add it later if needed.
        // ndk {
        //     abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        // }
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
            // applicationVariants.all { variant ->
            //     variant.outputs.all { output ->
            //         outputFileName = "ScreenToChromecast-${variant.name}-${variant.versionName}.apk"
            //     }
            // }
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
        // dataBinding = true // Enable if you prefer DataBinding
    }

    // If LibVLC requires packaging of native libs (.so files) and they are
    // not automatically picked up, you might need to configure sourceSets for jniLibs.
    // sourceSets {
    //     main {
    //         jniLibs.srcDirs = ['libs'] // If you place .so files in a 'libs' folder
    //     }
    // }
}

dependencies {
    // Core AndroidX libraries
    implementation("androidx.core:core-ktx:1.13.1") // Kotlin extensions
    implementation("androidx.appcompat:appcompat:1.6.1") // AppCompat support library
    implementation("com.google.android.material:material:1.12.0") // Material Design components
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // ConstraintLayout

    // LibVLC for Android
    // The official LibVLC Android artifact. Adjust version as needed.
    // Check https://code.videolan.org/videolan/libvlc-android/-/packages
    // or Maven Central for the latest version.
    implementation("org.videolan.android:libvlc-all:3.5.4") // Example version, replace with latest stable

    // Lifecycle components (ViewModel, LiveData) - useful for managing UI-related data
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0") // For lifecycleScope

    // Activity KTX for by viewModels()
    implementation("androidx.activity:activity-ktx:1.9.0")
    // Fragment KTX for by viewModels() etc. (if we use Fragments)
    implementation("androidx.fragment:fragment-ktx:1.7.1")


    // Testing libraries
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
