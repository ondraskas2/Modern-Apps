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
    implementation(project(":watch:shared"))
    implementation(libs.androidx.health.services.client)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.concurrent.futures)
    implementRoom(libs)
}
