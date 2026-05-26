plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.enaide.sdk.simulation"
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

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { explicitApi() }

dependencies {
    // Usa GeoUtils (internal del core) e i modelli: dipende dal core.
    api(project(":enaide-sdk"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
