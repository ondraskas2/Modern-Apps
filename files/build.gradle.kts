plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
