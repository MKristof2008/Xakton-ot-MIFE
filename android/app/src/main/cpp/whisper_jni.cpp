// JNI-обвязка над whisper.cpp для Android.
//
// Почему whisper.cpp, а не faster-whisper из Python-пайплайна:
// у ctranslate2 (ядро faster-whisper) нет Android-колёс — только manylinux
// и win. Проверено по индексу PyPI. Поэтому на устройстве используется
// нативный whisper.cpp, собираемый под arm64.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Сегмент, который не должен попасть в результат:
// - выходит за конец записи (галлюцинация whisper на тишине);
// - подозрительная уверенность модели.
// Те же правила, что в asr.py::is_hallucination.
namespace {

constexpr float kNoSpeechLimit = 0.60f;
constexpr int kEndToleranceMs = 250;

bool is_hallucination(whisper_context *ctx, int i, int audioMs) {
    const auto t0 = whisper_full_get_segment_t0(ctx, i) * 10;  // 10 мс на единицу
    const auto t1 = whisper_full_get_segment_t1(ctx, i) * 10;

    if (audioMs > 0 && t1 > audioMs + kEndToleranceMs) return true;
    if (t1 <= t0) return true;

    // no_speech_prob whisper.cpp напрямую не отдаёт, поэтому опираемся
    // на границы и на пустой текст (проверяется ниже).
    return false;
}

std::string segment_text(whisper_context *ctx, int i) {
    std::string out;
    const int n = whisper_full_n_tokens(ctx, i);
    for (int k = 0; k < n; ++k) {
        const char *txt = whisper_full_get_token_text(ctx, i, k);
        if (txt) out += txt;
    }
    // убираем ведущие пробелы
    const auto pos = out.find_first_not_of(" \t\n\r");
    if (pos == std::string::npos) return "";
    return out.substr(pos);
}

int wav_duration_ms(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fclose(f);
    if (size < 44) return 0;
    // 16 кГц, моно, 16 бит => 32000 байт/с
    const long dataBytes = size - 44;
    return static_cast<int>(dataBytes / 32000.0 * 1000.0);
}

// Читает WAV 16 кГц / моно / 16 бит в float-буфер для whisper_full.
// Заголовок фиксированный (44 байта) — такой пишет наш AudioConverter.
bool read_wav_pcm(const char *path, std::vector<float> &pcm) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 44, SEEK_SET);
    const long dataBytes = size - 44;
    if (dataBytes <= 0) { fclose(f); return false; }

    std::vector<short> raw(dataBytes / 2);
    const size_t got = fread(raw.data(), 2, raw.size(), f);
    fclose(f);
    if (got != raw.size()) return false;

    pcm.resize(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) pcm[i] = raw[i] / 32768.0f;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_hexteam_stenograph_engine_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // на телефоне CPU надёжнее

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("не удалось загрузить модель");
        return 0;
    }
    LOGI("модель загружена");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ru_hexteam_stenograph_engine_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jstring wavPath, jstring language) {

    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return nullptr;

    const char *path = env->GetStringUTFChars(wavPath, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang;
    params.n_threads = 4;
    params.print_progress = false;
    params.print_realtime = false;
    params.translate = false;
    params.no_timestamps = false;
    params.single_segment = false;
    // Отключаем перенос контекста: меньше зацикливания и галлюцинаций
    params.no_context = true;

    const int audioMs = wav_duration_ms(path);

    std::vector<float> pcm;
    if (!read_wav_pcm(path, pcm)) {
        LOGE("не удалось прочитать WAV");
        env->ReleaseStringUTFChars(wavPath, path);
        env->ReleaseStringUTFChars(language, lang);
        return nullptr;
    }
    const int rc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));

    env->ReleaseStringUTFChars(wavPath, path);
    env->ReleaseStringUTFChars(language, lang);

    if (rc != 0) {
        LOGE("whisper_full вернул %d", rc);
        return nullptr;
    }

    std::vector<std::string> lines;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        if (is_hallucination(ctx, i, audioMs)) continue;

        std::string text = segment_text(ctx, i);
        if (text.empty()) continue;

        const auto t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const auto t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        lines.push_back(std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text);
    }

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(lines.size()), strClass, nullptr);
    for (size_t i = 0; i < lines.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i),
                                   env->NewStringUTF(lines[i].c_str()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_hexteam_stenograph_engine_WhisperBridge_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) {
        whisper_free(ctx);
        LOGI("контекст освобождён");
    }
}