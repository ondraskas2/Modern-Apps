plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.chess"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
