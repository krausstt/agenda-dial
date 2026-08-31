plugins { alias(libs.plugins.android.application) }

/**
 * Das Kotlin-APK. Zwei Verantwortungen:
 *   1. AgendaComplicationService  — liest den Kalender, rendert den Agenda-Layer
 *                                   und liefert ihn als PHOTO_IMAGE ans Watchface
 *   2. OrganizerActivity          — die Detailansicht, geoeffnet per Tap aufs
 *                                   Zifferblatt oder ueber den Launcher
 */
android {
    namespace  = "de.agendadial.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.agendadial.wear"
        minSdk        = libs.versions.minSdk.get().toInt()
        targetSdk     = libs.versions.targetSdk.get().toInt()
        versionCode   = 1
        versionName   = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // Sideload-Builds
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.complications.ds)
    implementation(libs.coroutines.android)
}
