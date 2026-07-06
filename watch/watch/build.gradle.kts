plugins {
    id("common-conventions-wear")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.watch.watch"
    }
}

dependencies {
    implementation(libs.androidx.health.services.client)
    implementRoom(libs)
}
