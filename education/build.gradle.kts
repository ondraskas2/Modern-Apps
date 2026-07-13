plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.education"
    }
}

dependencies {
    implementRoom(libs)

    implementation(libs.coil.compose)
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
}
