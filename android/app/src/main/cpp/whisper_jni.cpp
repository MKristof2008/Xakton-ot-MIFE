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

/**
 * Известные галлюцинации whisper на тишине и музыке.
 *
 * На паузах модель «вспоминает» титры обучающих наборов: «Редактор субтитров»,
 * «Продолжение следует», «Спасибо за просмотр». Это не речь, и в расшифровке
 * они портят WER сильнее, чем любой пропуск.
 */
const char *kHallucinationMarkers[] = {
    "редактор субтитров", "корректор", "субтитры", "продолжение следует",
    "спасибо за просмотр", "подписывайтесь", "ставьте лайк",
    "субтитры сделал", "dimatorzok", "продолжение в следующей",
    // Титры и музыкальные вставки: whisper выдаёт их на тишине, музыке и
    // на стыке дорожек — это не речь, в расшифровке им не место.
    "музыка", "аплодисменты", "смех", "звукорежиссёр", "звукорежиссер",
};

/**
 * Приведение UTF-8 к нижнему регистру для кириллицы.
 *
 * `std::tolower` умеет только ASCII: на байтах кириллицы он ничего не меняет,
 * поэтому «Редактор субтитров» не совпадал с маркером «редактор субтитров»
 * и галлюцинации проходили в результат. Кириллица в UTF-8 — две байтовые
 * пары; А-Я (D0 90..AF) и Ё (D0 81) переводятся в а-я (D0 B0..BF) и ё (D1 91).
 */
std::string to_lower_utf8(const std::string &src) {
    std::string out;
    out.reserve(src.size());
    for (size_t i = 0; i < src.size();) {
        const unsigned char c = static_cast<unsigned char>(src[i]);
        if (c == 0xD0 && i + 1 < src.size()) {
            const unsigned char n = static_cast<unsigned char>(src[i + 1]);
            if (n >= 0x90 && n <= 0x9F) {          // А..П
                out.push_back(static_cast<char>(0xD0));
                out.push_back(static_cast<char>(n + 0x20));
                i += 2;
                continue;
            }
            if (n >= 0xA0 && n <= 0xAF) {          // Р..Я
                out.push_back(static_cast<char>(0xD1));
                out.push_back(static_cast<char>(n - 0x20));
                i += 2;
                continue;
            }
            if (n == 0x81) {                        // Ё
                out.push_back(static_cast<char>(0xD1));
                out.push_back(static_cast<char>(0x91));
                i += 2;
                continue;
            }
            out.push_back(src[i]);
            out.push_back(src[i + 1]);
            i += 2;
            continue;
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(std::tolower(c)));
            i += 1;
            continue;
        }
        out.push_back(src[i]);
        ++i;
    }
    return out;
}

bool looks_like_hallucination(const std::string &text) {
    const std::string lower = to_lower_utf8(text);
    for (const char *marker : kHallucinationMarkers) {
        if (lower.find(marker) != std::string::npos) return true;
    }
    return false;
}

/** Слова в нижнем регистре, без пунктуации — для сравнения на повтор. */
std::vector<std::string> words_lower(const std::string &src) {
    std::vector<std::string> out;
    std::string cur;
    for (unsigned char c : src) {
        if (std::isalnum(c) || c >= 128) {
            cur.push_back(static_cast<char>(std::tolower(c)));
        } else if (!cur.empty()) {
            out.push_back(cur);
            cur.clear();
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

/** Совпадают ли два слова с учётом разных форм: «вглядывая» ~ «вглядывался». */
bool words_match(const std::string &a, const std::string &b) {
    if (a == b) return true;
    // Одно — начало другого: «вглядыва» / «вглядывался», «тягиваемся» / «притягиваемся».
    const size_t minLen = std::min(a.size(), b.size());
    if (minLen < 4) return false;
    return a.compare(0, minLen, b, 0, minLen) == 0;
}

/** Длина общего префикса двух списков слов (с нечётким совпадением слов). */
size_t common_prefix(const std::vector<std::string> &a, const std::vector<std::string> &b) {
    size_t n = 0;
    while (n < a.size() && n < b.size() && words_match(a[n], b[n])) ++n;
    return n;
}

/**
 * Убирает повтор, возникший из-за перекрытия окон и склейки фраз whisper.
 *
 * На реальных замерах встречались пары:
 *   «...погибших» / «миров лось жадно вглядыва» / «Жадно вглядывался в эту сеть»
 *   «Притягиваемся.» / «тягиваемся, падаем.»
 *
 * Сравнение идёт по СЛОВАМ, а не по байтам: кириллица в UTF-8 занимает два
 * байта, и побайтовое сравнение давало ложные совпадения и срезало лишнее.
 */
std::string strip_repeat(const std::string &previous, const std::string &current) {
    if (previous.empty() || current.empty()) return current;

    const auto prev = words_lower(previous);
    const auto cur = words_lower(current);
    if (prev.empty() || cur.empty()) return current;

    // Ищем самый длинный суффикс предыдущей фразы, которым начинается текущая.
    size_t overlap = 0;
    const size_t maxLen = std::min(prev.size(), cur.size());
    for (size_t len = maxLen; len >= 1; --len) {
        if (common_prefix(
                std::vector<std::string>(prev.end() - len, prev.end()), cur) == len) {
            overlap = len;
            break;
        }
    }
    // Одно слово может совпасть случайно («и», «в»). Два и больше — это дубль.
    if (overlap < 1) return current;
    // Одно совпавшее слово считаем дублем только если оно длинное:
    // «тягиваемся» после «Притягиваемся» — да, «и» после «и» — нет.
    if (overlap == 1) {
        const std::string &w = cur[0];
        if (w.size() < 5) return current;
    }

    // Отрезаем `overlap` слов от начала исходной строки, сохраняя остальное.
    size_t seen = 0;
    size_t pos = 0;
    bool inWord = false;
    for (; pos < current.size(); ++pos) {
        const unsigned char c = static_cast<unsigned char>(current[pos]);
        const bool isWordChar = std::isalnum(c) || c >= 128;
        if (isWordChar && !inWord) {
            if (seen == overlap) break;  // дошли до первого лишнего слова
            ++seen;
            inWord = true;
        } else if (!isWordChar) {
            inWord = false;
        }
    }
    std::string rest = current.substr(std::min(pos, current.size()));
    const auto first = rest.find_first_not_of(" \t,.;:!?—–-…");
    if (first == std::string::npos) return "";
    rest = rest.substr(first);
    return words_lower(rest).empty() ? "" : rest;
}

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
    //
    // Перекрытия НЕТ намеренно. С перекрытием 1 с whisper повторял конец
    // одного окна в начале следующего, и убрать этот дубль надёжно не
    // получалось: повтор оказывался разорван словами и в другой форме
    // («вглядывая» / «Жадно вглядывался»). Граница ровно по 30 с даёт
    // чистый стык; редкий обрывок слова на границе дешевле дубля фразы.
    const int audioMs = wav_duration_ms(path);
    constexpr int kWindowMs = 30'000;
    std::vector<std::string> lines;
    double totalInferMs = 0.0;
    for (int windowStart = 0; windowStart < audioMs; windowStart += kWindowMs) {
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
            if (looks_like_hallucination(text)) {
                LOGI("отброшена галлюцинация: %s", text.c_str());
                continue;
            }
            // Дубль внутри окна и на стыке окон: хвост прошлой реплики
            // повторяется в начале текущей. У whisper это частая ошибка при
            // перекрытии фраз — «...погибших» / «миров лось жадно вглядывался»
            // / «Жадно вглядывался в эту сеть».
            if (!lines.empty()) {
                const auto prevTab = lines.back().rfind('\t');
                const std::string prevText = lines.back().substr(prevTab + 1);
                text = strip_repeat(prevText, text);
                if (text.empty()) continue;
            }
            // Совсем короткий остаток («тягиваемся, падаем.» после
            // «Притягиваемся.») — это обрывок уже сказанного.
            if (text.size() < 4) continue;
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