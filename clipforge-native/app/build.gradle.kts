plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clipforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clipforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-native"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Media3 — Google's official video editing stack (hardware encoding).
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("androidx.core:core-ktx:1.13.1")

    // Jetpack Compose (CapCut-style black & white UI).
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    androidTestImplementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Video thumbnails.
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
