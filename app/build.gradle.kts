plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.musicapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.personal.musicapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val syncDebugApk = tasks.register("syncDebugApk") {
    val src = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val ver = android.defaultConfig.versionName ?: "debug"
    val dstVersioned = rootProject.layout.projectDirectory.file("music-app-v$ver.apk")
    val dstLatest = rootProject.layout.projectDirectory.file("music-app.apk")
    inputs.file(src)
    outputs.files(dstVersioned, dstLatest)
    doLast {
        val srcFile = src.get().asFile
        if (srcFile.exists()) {
            srcFile.copyTo(dstVersioned.asFile, overwrite = true)
            srcFile.copyTo(dstLatest.asFile, overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(syncDebugApk) }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 (ExoPlayer & MediaSession)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Coil for Compose image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // OkHttp for networking and stream fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
