plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vivo.islandlyric"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vivo.islandlyric"
        minSdk = 31
        targetSdk = 35
        versionCode = 60
        versionName = "1.4.6.3"
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
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
        buildConfig = true
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.2")

    // Modern libxposed API 102 (compileOnly) + Service (Remote SharedPreferences)
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")

    // Jetpack Compose + AndroidLiquidGlass (Backdrop) — strictly scoped to SettingsActivity UI
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.runtime:runtime:1.9.2")
    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.ui:ui-graphics:1.9.2")
    implementation("androidx.compose.ui:ui-util:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.9.2")
    implementation("io.github.kyant0:backdrop:2.0.0") {
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
    }
}
