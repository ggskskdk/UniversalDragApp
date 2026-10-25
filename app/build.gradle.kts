plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tools.universaldrag"
    compileSdk = 35

    val autoVersionCode = (System.currentTimeMillis() / 1000L).toInt()

    defaultConfig {
        applicationId = "com.tools.universaldrag"
        minSdk = 30
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = "8.1.$autoVersionCode"
    }

    signingConfigs {
        create("fixedConfig") {
            val ksFile = file("universal_drag.p12")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "universal1234"
                keyAlias = "universal_key"
                keyPassword = "universal1234"
            }
        }
    }

    buildTypes {
        debug {
            val ksFile = file("universal_drag.p12")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("fixedConfig")
            }
        }
        release {
            isMinifyEnabled = false
            val ksFile = file("universal_drag.p12")
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("fixedConfig")
            } else {
                signingConfigs.getByName("debug")
            }
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
