plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.enaide.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enaide.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    // Core sempre presente; moduli opzionali inclusi perche' la demo li mostra tutti.
    implementation(project(":enaide-sdk"))
    implementation(project(":enaide-sdk-geocoding"))
    implementation(project(":enaide-sdk-simulation"))
    implementation(project(":enaide-sdk-map"))
    implementation(project(":enaide-sdk-tts"))
    implementation(project(":enaide-sdk-vehicle"))
    implementation(project(":enaide-sdk-poi"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
    // MapLibre arriva transitivo dal modulo :enaide-sdk-map (esposto via api()).
}
