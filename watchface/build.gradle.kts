plugins { alias(libs.plugins.android.application) }

/**
 * Watch Face Format APK. Enthält per Definition keinen Code —
 * android:hasCode="false" im Manifest ist Pflicht.
 *
 * res/raw/watchface.xml wird von tools/genwff.mjs generiert,
 * res/drawable-nodpi/hand_*.png von tools/genassets.mjs.
 * Beide lesen design/geometry.json.
 */
android {
    namespace  = "de.agendadial.watchface"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.agendadial.watchface"
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

    // Ohne Code gibt es nichts zu kompilieren; aapt2 packt nur Ressourcen.
    androidResources { noCompress += "xml" }
}
