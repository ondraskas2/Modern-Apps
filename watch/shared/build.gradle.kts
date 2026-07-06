plugins {
    // Applied directly (not common-conventions-library) to keep this module free
    // of Compose: it is the phone-safe contract layer, kotlinx.serialization only.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vayunmathur.watch.shared"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 30
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
