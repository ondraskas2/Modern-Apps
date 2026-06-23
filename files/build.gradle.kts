plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:ui"))
    implementation(project(":library:downloadservice"))
    implementation(libs.androidx.work.runtime.ktx)
}
