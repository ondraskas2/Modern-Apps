plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementation(libs.geckoview)
    implementation(libs.jsoup)
    implementRoom(libs)
}
