plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.hexteam.stenograph"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.hexteam.stenograph"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Нативная сборка (whisper.cpp, llama.cpp) включается только когда
    // исходники на месте. Иначе чистый клон без bootstrap.sh падал бы на
    // конфигурации проекта, и юнит-тесты нельзя было бы запустить вовсе.
    // Подготовка: android/scripts/bootstrap.sh
    val nativeSourcesReady = file("src/main/cpp/whisper.cpp/include/whisper.h").exists()

    if (nativeSourcesReady) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        logger.lifecycle(
            "whisper.cpp не найден: нативная сборка отключена. " +
                "Запустите android/scripts/bootstrap.sh, чтобы собрать APK с моделями."
        )
    }

    buildTypes {
        debug {
            // Отладочная сборка: те же движки, что в release, но с отладочной
            // информацией. Оптимизация нативного кода включена и здесь —
            // иначе замеры RTF в debug не отражают реальную скорость.
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                }
            }
        }
        release {
            // Минификация выключена намеренно: APK на 99% состоит из моделей,
            // а R8 — лишний риск сломать JNI и sherpa-onnx ради единиц
            // процентов размера. Заглушек в APK нет по построению: они
            // вынесены в тестовый набор (app/src/test).
            isMinifyEnabled = false
            // Debug-ключом, чтобы APK ставился без отдельной подписи (как в main)
            signingConfig = signingConfigs.getByName("debug")
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }

    // Инструментальные тесты — на debug. Тестовый APK под R8 требует
    // отдельного набора исключений для androidx.test и kotlin stdlib,
    // а проверяет он не то, что нужно: release проверяется установкой
    // и запуском на устройстве (R8 вырезает Stubs, JNI должен остаться).
    testBuildType = "debug"

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // sherpa-onnx: нативные библиотеки распаковываются при установке,
        // иначе на части прошивок JNI не находит их из APK.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // Модели не сжимаем: они уже квантизованы, а распаковка при каждом
        // чтении съедает память и время старта.
        // Исключение — GGUF локальной LLM: он сжимается на ~24% без потерь
        // для качества (это просто упаковка APK), а распаковывается один раз
        // при установке.
        noCompress += listOf("onnx", "bin", "txt")
    }
}

dependencies {
    // sherpa-onnx: диаризация (pyannote-3.0 + 3D-Speaker) и ASR на устройстве.
    // AAR в git не лежит (47 МБ): android/scripts/fetch_libs.sh.
    if (file("libs/sherpa-onnx.aar").exists()) {
        implementation(files("libs/sherpa-onnx.aar"))
    } else {
        logger.lifecycle(
            "sherpa-onnx.aar не найден: диаризация будет недоступна. " +
                "Запустите android/scripts/fetch_libs.sh."
        )
    }

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Замеры WER/DER/RTF на реальном устройстве (tools/bench). Не входят в APK.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

}
