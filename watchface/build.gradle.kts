plugins { alias(libs.plugins.android.application) }

/**
 * Watch Face Format APK. Enthält per Definition keinen Code —
 * android:hasCode="false" im Manifest ist Pflicht.
 *
 * res/raw/watchface.xml wird von tools/genwff.mjs generiert,
 * res/drawable-nodpi/hand_*.png von tools/genassets.mjs,
 * res/drawable-nodpi/preview.png von tools/shoot.mjs.
 * Alle drei lesen design/geometry.json.
 *
 * Konfiguration folgt android/wear-os-samples, WatchFaceFormat/Complications:
 *   enableKotlin = false   AGP 9 wuerde sonst Kotlin einhaengen, das es hier nicht gibt
 *   isMinifyEnabled = true schrumpft Manifest und Metadaten des codefreien APK
 *   isShrinkResources = false  sonst raeumt der Shrinker WFF-Ressourcen weg,
 *                              die nur aus dem XML heraus referenziert werden
 */
android {
    enableKotlin = false

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
        debug {
            isMinifyEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            // Debug-Key: reicht fuers Sideload. Fuer Play Store spaeter ersetzen.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
