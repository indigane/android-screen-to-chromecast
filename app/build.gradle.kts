plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.castmirror"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.castmirror"
        minSdk = 26
        // 35 rather than 36: the app is verified against the Android 14/15 foreground-service and
        // MediaProjection rules, which is what the manifest declares for. Raising it further is a
        // behavioural change that needs a device to validate.
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.0"
    }

    // Strips the Google dependency-metadata signing block, which F-Droid rejects. A de-Googled
    // target deserves an APK with nothing Google-specific in it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            if (System.getenv("KEYSTORE_PATH") != null) {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                storeType = "PKCS12"
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // src/main/kotlin needs no declaration: the Kotlin Android plugin adds it to every source set
    // alongside src/main/java.

    // Nothing in the app reads BuildConfig; the version name comes from PackageManager so the
    // same code works in the Gradle-free sandbox build, which generates no BuildConfig at all.
    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The Android layer deliberately depends on nothing but the framework, the Kotlin standard
    // library and the protocol core. No AndroidX, no Material, no Compose: those are AAR
    // artifacts that make the app impossible to build without a full Maven mirror, and none of
    // them earns its place in a screen-capture utility with one screen.
    implementation(project(":core"))
}
