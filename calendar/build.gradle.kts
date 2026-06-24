plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}