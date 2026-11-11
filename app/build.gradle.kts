import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val appVersionCode = 1

android {
    namespace = "rocks.urzahil.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "rocks.urzahil.iptv"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.1.0"

        // One APK, ARM only: libVLC and the FFmpeg decoder are the bulk of the size.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
        val releaseKeystore = file(keystorePath)
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "upload"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Never fall back to the debug key: without a release keystore the APK stays unsigned.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Screenshot tests are opt-in: ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest'
val screenshotsEnabled = providers.gradleProperty("screenshots").isPresent
tasks.withType<Test>().configureEach {
    if (screenshotsEnabled) {
        systemProperty("iptv.screenshots", "true")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("iptv.screenshots.dir", rootProject.file("screenshots").absolutePath)
        outputs.upToDateWhen { false }
    } else {
        exclude("**/screenshots/**")
    }
}

// The FFmpeg decoder is built against media3 1.9.x: keep every media3 module on the same line.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3" && requested.version?.startsWith("1.9.") == true) {
            useVersion(libs.versions.media3.get())
        }
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.jellyfin.media3.ffmpeg)
    implementation(libs.libvlc.all)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Opt-in screenshot test (see ScreenshotTest): renders the real Compose UI on the JVM.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
