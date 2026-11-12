plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tube.update"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
