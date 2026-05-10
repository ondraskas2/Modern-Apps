plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    api(libs.androidx.glance)
    api(libs.androidx.glance.appwidget)
    api(libs.androidx.glance.material3)
    api(libs.androidx.work.runtime.ktx)
}
