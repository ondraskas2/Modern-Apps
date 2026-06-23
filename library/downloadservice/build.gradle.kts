plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:network"))
}
