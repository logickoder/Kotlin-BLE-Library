plugins {
    alias(libs.plugins.nordic.library)
    alias(libs.plugins.nordic.kotlin)
    alias(libs.plugins.nordic.nexus)
}

group = "no.nordicsemi.android.kotlin.ble"

nordicNexusPublishing {
    POM_ARTIFACT_ID = "client"
    POM_NAME = "Nordic Kotlin library for BLE client side."

    POM_DESCRIPTION = "Nordic Android Kotlin BLE library"
    POM_URL = "https://github.com/NordicPlayground/Kotlin-BLE-Library"
    POM_SCM_URL = "https://github.com/NordicPlayground/Kotlin-BLE-Library"
    POM_SCM_CONNECTION = "scm:git@github.com:NordicPlayground/Kotlin-BLE-Library.git"
    POM_SCM_DEV_CONNECTION = "scm:git@github.com:NordicPlayground/Kotlin-BLE-Library.git"

    POM_DEVELOPER_ID = "syzi"
    POM_DEVELOPER_NAME = "Sylwester Zieliński"
    POM_DEVELOPER_EMAIL = "sylwester.zielinski@nordicsemi.no"
}

android {
    namespace = "no.nordicsemi.android.kotlin.ble.client.main"
}

dependencies {
    api(project(":core"))
    api(project(":logger"))

    implementation(project(":client-api"))
    implementation(project(":mock"))
    implementation(project(":client-android"))
    implementation(project(":client-mock"))

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.core)
}
