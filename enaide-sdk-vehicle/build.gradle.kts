plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.enaide.sdk.vehicle"
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
    // Usa TransportProfile, RouteOptions, VehicleDimensions del core.
    api(project(":enaide-sdk"))

    testImplementation(libs.junit)
}
