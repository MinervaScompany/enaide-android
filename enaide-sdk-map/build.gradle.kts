plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.enaide.sdk.map"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("../enaide-sdk/consumer-rules.pro")
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

// NB: niente explicitApi() qui — le @Composable e i token UI hanno tipi inferiti
// che mal si sposano con la modalita' strict, e questo e' un modulo UI.

dependencies {
    api(project(":enaide-sdk"))

    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)

    // MapLibre: API esposta (api) cosi' chi usa il modulo puo' estendere la mappa.
    api(libs.maplibre.android.sdk)
}
