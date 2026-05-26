plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.fogmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.fogmirror"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
}
