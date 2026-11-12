plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tube.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tube.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись debug-ключом общего тулчейна — как у Tube.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // libbyedpi.so — исполняемый файл: он должен лежать распакованным в nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    compileOptions {
        // NewPipeExtractor пользуется API Java 11 (java.time, List.of, String.isBlank…).
        isCoreLibraryDesugaringEnabled = true
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
            test.maxHeapSize = "2g"
            if (project.hasProperty("network")) test.systemProperty("network", "true")
            test.testLogging { showStandardStreams = project.hasProperty("network") }
            // Скриншоты (Robolectric) — только по запросу: gradlew :client:testDebugUnitTest -Pscreenshots
            if (project.hasProperty("screenshots")) {
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.filter.includeTestsMatching("com.tube.player.screenshots.*")
            } else {
                test.filter.excludeTestsMatching("com.tube.player.screenshots.*")
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
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    implementation(project(":core-byedpi"))
    implementation(project(":core-strategy"))
    implementation(project(":core-update"))
    implementation(project(":core-ui"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.newpipe.extractor)
    implementation(libs.nanojson)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.webkit)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
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
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
