plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.office"
    }
}

dependencies {
    implementation(project(":library:ui"))
}
