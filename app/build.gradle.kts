plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.regez.plauddrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.regez.plauddrive"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Optional: feste Signatur für CI-Builds, damit der SHA-1 in der Google Cloud Console stabil bleibt.
    val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksPath != null) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
