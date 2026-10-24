plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tools.universaldrag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tools.universaldrag"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "4.0.0-OriginalMedia"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.draganddrop:draganddrop:1.0.0")
}
