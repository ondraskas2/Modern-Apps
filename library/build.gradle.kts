plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    // navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.adaptive.navigation3)

    //reorderable
    implementation(libs.reorderable)

    // room
    implementRoom(libs)
    api("net.zetetic:sqlcipher-android:4.14.1")
    implementation("androidx.sqlite:sqlite:2.6.2")

    // datastore
    implementation(libs.androidx.datastore.preferences)

    api(libs.material)
}