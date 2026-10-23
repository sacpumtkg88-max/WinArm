plugins {
id("com.android.application") version "8.5.2"
id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
namespace = "com.winarm"
compileSdk = 34

defaultConfig {
    applicationId = "com.winarm"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
}

}