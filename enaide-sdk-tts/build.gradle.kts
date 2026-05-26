plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.enaide.sdk.tts"
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
}

kotlin { explicitApi() }

dependencies {
    // Indipendente dal core nella logica, ma lo teniamo per coerenza di versione
    // e per eventuale uso futuro dei modelli (SpokenInstruction).
    api(project(":enaide-sdk"))
}
