plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.watch.phone"
        minSdk = 34
    }
}

dependencies {
    implementation(project(":watch:shared"))
    implementation(libs.androidx.connect.client)
    implementation(libs.androidx.work.runtime.ktx)
    implementRoom(libs)
}
