plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.pdf"
    }
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.viewer.fragment)
    implementation(libs.androidx.pdf.ink)
    implementation(libs.androidx.pdf.document.service)

    // fragment support for AppCompatActivity
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.material)
}
