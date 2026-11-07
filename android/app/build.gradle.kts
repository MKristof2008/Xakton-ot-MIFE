plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.mifi.stenograf"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.mifi.stenograf"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем debug-ключом — чтобы APK ставился без возни на защите.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // Модели кладём в assets без сжатия — иначе распаковка съедает память.
        noCompress += listOf("onnx", "bin", "txt", "task")
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
    implementation(files("libs/onnxruntime-genai.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // org.json в unit-тестах не замокан на JVM — подключаем реальную реализацию
    testImplementation("org.json:json:20240303")
}