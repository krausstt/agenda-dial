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
    }
}

rootProject.name = "AgendaDial"

// Geteilter Renderer + Geometrie. Zeichnet sowohl in das Complication-Bitmap
// als auch in die Organizer-App — eine Codebasis, zwei Oberflächen.
include(":core")

// Kotlin-APK: Complication-Provider (liest Kalender, rendert Agenda-Layer)
// plus Organizer-App fuer die Detailansicht.
include(":wear")

// Watch Face Format APK. Enthaelt per Definition keinen Code.
include(":watchface")
