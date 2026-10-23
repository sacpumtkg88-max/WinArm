plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.winarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.winarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
