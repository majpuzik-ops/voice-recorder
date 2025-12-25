plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.majpuzik.voicerecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.majpuzik.voicerecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.9.1")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.8.8")

    // Room for local database
    implementation("androidx.room:room-runtime:2.3.0")
    implementation("androidx.room:room-ktx:2.3.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.2.1")

    // Jetpack WindowManager for Samsung Fold support (1.3.0 for Rear Display API)
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-core:1.3.0")
}
