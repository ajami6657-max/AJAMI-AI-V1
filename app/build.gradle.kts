plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ajami.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ajami.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true }
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
    }
    signingConfigs {
        create("release") {
            val signingStore = System.getenv("AJAMI_KEYSTORE_PATH")
            if (!signingStore.isNullOrBlank()) {
                storeFile = file(signingStore)
                storePassword = System.getenv("AJAMI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AJAMI_KEY_ALIAS")
                keyPassword = System.getenv("AJAMI_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
