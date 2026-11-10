import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.meuxo.formcheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meuxo.formcheck"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
    }

    androidResources {
        // MediaPipe memory-maps model files; keep them uncompressed in the APK.
        noCompress += listOf("tflite", "task")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        // Room schema JSON is read by the migration test.
        getByName("test").resources.srcDir("$projectDir/schemas")
        // Validation clips (see /validation/README.md) for the instrumented accuracy test.
        getByName("androidTest").assets.srcDir("$rootDir/validation")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// On-device models. Downloaded from Google's official MediaPipe model bucket at
// build time instead of being committed (the repo is public and the files are
// large). Sources and licenses are documented in docs/MODELS.md.
// ---------------------------------------------------------------------------
val modelDir = layout.projectDirectory.dir("src/main/assets/models")
val models = mapOf(
    "pose_landmarker_heavy.task" to
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task",
    "efficientdet_lite0.tflite" to
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
)
val downloadModels by tasks.registering {
    description = "Downloads the MediaPipe models into src/main/assets/models if missing."
    outputs.dir(modelDir)
    doLast {
        val dir = modelDir.asFile
        dir.mkdirs()
        models.forEach { (name, url) ->
            val target = File(dir, name)
            if (!target.exists() || target.length() == 0L) {
                logger.lifecycle("Downloading $name")
                val tmp = File(dir, "$name.part")
                URI(url).toURL().openStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.renameTo(target)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    // MediaPipe Tasks (pose landmarker + object detector), fully on-device
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mlkit.pose.detection.accurate) // v1 pipeline, removed in phase 3

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Background work + settings
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Coil
    implementation(libs.coil.compose)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
