plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tube.dpi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tube.dpi"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись debug-ключом общего тулчейна, чтобы release-сборку можно было сразу поставить на телефон.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // libbyedpi.so — исполняемый файл: он должен лежать распакованным в nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Скриншоты (Robolectric) — только по запросу: gradlew testDebugUnitTest -Pscreenshots
            if (project.hasProperty("screenshots")) {
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.filter.includeTestsMatching("com.tube.dpi.screenshots.*")
            } else {
                test.filter.excludeTestsMatching("com.tube.dpi.screenshots.*")
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "dev.chrisbanes.haze.ExperimentalHazeApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(project(":core-byedpi"))
    implementation(project(":core-strategy"))
    implementation(project(":core-tunnel"))
    implementation(project(":core-ui"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
