// JNI-обвязка над whisper.cpp для Android.
//
// Почему whisper.cpp, а не faster-whisper из Python-пайплайна:
// у ctranslate2 (ядро faster-whisper) нет Android-колёс — только manylinux
// и win. Проверено по индексу PyPI. Поэтому на устройстве используется
// нативный whisper.cpp, собираемый под arm64.

#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <string>
#include <thread>
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
    // Спецтокены (таймкоды, [_BEG_] и пр.) пропускаем: id >= eot — служебные.
    const whisper_token eot = whisper_token_eot(ctx);
    const int n = whisper_full_n_tokens(ctx, i);
    for (int k = 0; k < n; ++k) {
        if (whisper_full_get_token_id(ctx, i, k) >= eot) continue;
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

// Читает WAV 16 кГц / моно / 16 бит в float-буфер для одного окна whisper_full.
// Окно читается напрямую из файла: весь длинный WAV больше не держится в RAM.
bool read_wav_pcm_range(const char *path, int startMs, int durationMs, std::vector<float> &pcm) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    const long dataBytes = size - 44;
    const long startByte = std::max<long>(0, static_cast<long>(startMs * 32.0));
    const long requested = static_cast<long>(durationMs * 32.0);
    if (dataBytes <= startByte) { fclose(f); return false; }
    const long bytes = std::min(requested, dataBytes - startByte);
    if (bytes <= 0) { fclose(f); return false; }
    fseek(f, 44 + startByte, SEEK_SET);
    std::vector<short> raw(static_cast<size_t>(bytes / 2));
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
    cparams.use_gpu = false;      // на телефоне CPU надёжнее
    cparams.flash_attn = true;    // flash attention: быстрее и меньше памяти на arm64

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
    // whisper.cpp лучше всего масштабируется до ~4 потоков; на 8-ядерных
    // телефонах большие ядра обычно 4–6. Берём половину ядер, но не меньше 4.
    const unsigned hw = std::thread::hardware_concurrency();
    int nThreads = static_cast<int>(hw / 2);
    if (nThreads < 4) nThreads = 4;
    if (nThreads > 8) nThreads = 8;
    params.n_threads = nThreads;
    LOGI("потоков инференса: %d (ядер: %u)", nThreads, hw);
    params.print_progress = false;
    params.print_realtime = false;
    params.translate = false;
    params.no_timestamps = false;
    params.single_segment = false;
    // Отключаем перенос контекста: меньше зацикливания и галлюцинаций
    params.no_context = true;

    // Длинные записи обрабатываем окнами. Весь WAV не попадает в RAM:
    // на каждом шаге живёт максимум 30 секунд PCM, после чего vector очищается.
    const int audioMs = wav_duration_ms(path);
    constexpr int kWindowMs = 30'000;
    constexpr int kOverlapMs = 1'000;
    std::vector<std::string> lines;
    double totalInferMs = 0.0;
    for (int windowStart = 0; windowStart < audioMs; windowStart += kWindowMs - kOverlapMs) {
        const int windowMs = std::min(kWindowMs, audioMs - windowStart);
        std::vector<float> pcm;
        if (!read_wav_pcm_range(path, windowStart, windowMs, pcm)) {
            LOGE("не удалось прочитать окно %d-%d мс", windowStart, windowStart + windowMs);
            continue;
        }

        const auto tStart = std::chrono::steady_clock::now();
        const int rc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
        const auto tEnd = std::chrono::steady_clock::now();
        const double inferMs = std::chrono::duration<double, std::milli>(tEnd - tStart).count();
        totalInferMs += inferMs;
        pcm.clear();
        pcm.shrink_to_fit();
        if (rc != 0) {
            LOGE("whisper_full вернул %d на окне %d-%d", rc, windowStart, windowStart + windowMs);
            continue;
        }

        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const auto localT0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const auto localT1 = whisper_full_get_segment_t1(ctx, i) * 10;
            const auto absoluteT0 = windowStart + localT0;
            const auto absoluteT1 = windowStart + localT1;
            if (audioMs > 0 && absoluteT1 > audioMs + kEndToleranceMs) continue;
            if (absoluteT1 <= absoluteT0) continue;

            std::string text = segment_text(ctx, i);
            if (text.empty()) continue;
            lines.push_back(std::to_string(absoluteT0) + "\t" +
                            std::to_string(absoluteT1) + "\t" + text);
        }
    }

    env->ReleaseStringUTFChars(wavPath, path);
    env->ReleaseStringUTFChars(language, lang);

    const double rtf = audioMs > 0 ? totalInferMs / audioMs : 0.0;
    LOGI("инференс окнами: %.0f мс на %d мс аудио, RTF=%.2f", totalInferMs, audioMs, rtf);

    // Полный текст расшифровки в logcat: после каждой записи текст можно
    // забрать с телефона (adb logcat -s WhisperJNI) для разбора и улучшений.
    LOGI("=== TRANSCRIPT BEGIN (%d мс аудио) ===", audioMs);
    for (const auto &line : lines) {
        LOGI("SEG\t%s", line.c_str());
    }
    LOGI("=== TRANSCRIPT END: %zu реплик ===", lines.size());

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