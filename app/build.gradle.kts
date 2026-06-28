plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.syncflix.app"
    // compileSdk 37 requis par Compose 1.12.0-alpha03 (tiré par material3 1.5.0-alpha — M3 Expressive).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.syncflix.app"
        // minSdk 26 : adaptive icons + Material You sans bitmaps de secours, et Media3 confortable.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 : retrait du code mort + obfuscation, et élagage des ressources inutilisées.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Lecteur vidéo natif (streaming HTTP avec Range) + intégration vue.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Synchro temps réel (Laravel Reverb) — WebSocket OkHttp, branchée aux étapes 3-4.
    implementation(libs.okhttp)

    // Affiches TMDB (catalogue / watchlist) — chargement d'images async en Compose.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
