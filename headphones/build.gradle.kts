plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.headphones"
        minSdk = 34
    }
}

metadataScreenshots {
    permissions.add("android.permission.BLUETOOTH_CONNECT")
    permissions.add("android.permission.POST_NOTIFICATIONS")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
