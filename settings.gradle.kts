pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MapLibre Android è ospitato sul repository Maven di MapLibre
        maven { url = uri("https://maven.maplibre.org/releases") }
    }
}

rootProject.name = "enaide"

// Core: routing + state machine + modello di dominio. Sempre richiesto.
include(":enaide-sdk")

// Moduli opzionali, escludibili da una fornitura: dipendono dal core ma il core
// non dipende da loro. Una build proprietaria puo' includere solo cio' che serve.
include(":enaide-sdk-geocoding")
include(":enaide-sdk-simulation")
include(":enaide-sdk-map")
include(":enaide-sdk-tts")
include(":enaide-sdk-vehicle")

include(":demo-app")
