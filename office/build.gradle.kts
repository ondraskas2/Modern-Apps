plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.office"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation("junit:junit:4.13.2")
    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
