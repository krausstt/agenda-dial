plugins { alias(libs.plugins.android.library) }

/**
 * Geteilter Zeichen- und Modellkern. Wird von zwei Stellen genutzt:
 *   - AgendaComplicationService rendert damit in ein Bitmap fuers Watchface
 *   - OrganizerActivity rendert damit direkt auf den Screen
 * Eine Geometrie, ein Renderer, zwei Oberflaechen.
 */
android {
    namespace  = "de.agendadial.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
}
