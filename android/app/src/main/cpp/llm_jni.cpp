// JNI-мост к llama.cpp: локальная LLM на устройстве.
//
// Задача узкая — расставить пунктуацию и заглавные буквы. Модель не должна
// переписывать текст, поэтому генерация идёт с низкой температурой и
// ограничением длины; проверка «не испортила ли» — на стороне Kotlin
// (LocalLlmEngine.acceptable), здесь только инференс.
//
// Сети здесь нет и быть не может: llama.cpp собран без CURL и без серверных
// частей (см. scripts/build_llama_android.sh).

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LocalLlmJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** Состояние одного загруженного движка: модель + контекст. */
struct LlmState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
};

std::string jstring_to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_hexteam_stenograph_engine_LocalLlmEngine_nativeInit(
    JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint threads) {

    const std::string path = jstring_to_string(env, modelPath);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    // На телефоне GPU-оффлоад недоступен без отдельного бэкенда: считаем на CPU.
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("не удалось загрузить модель: %s", path.c_str());
        return 0;
    }

    // Контекст на 2048 токенов: реплика диалога + промпт укладываются с запасом,
    // а память KV-кэша растёт линейно — больше на телефоне не нужно.
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("не удалось создать контекст");
        llama_model_free(model);
        return 0;
    }

    auto *state = new LlmState{model, ctx};
    LOGI("модель загружена: %s (потоков %d)", path.c_str(), cparams.n_threads);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_hexteam_stenograph_engine_LocalLlmEngine_nativeGenerate(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt, jint maxTokens) {

    auto *state = reinterpret_cast<LlmState *>(handle);
    if (state == nullptr || state->ctx == nullptr) return nullptr;

    const std::string text = jstring_to_string(env, prompt);
    const llama_vocab *vocab = llama_model_get_vocab(state->model);

    const int n_prompt = -llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("не удалось посчитать токены промпта");
        return nullptr;
    }

    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                       tokens.data(), n_prompt, true, true) < 0) {
        return nullptr;
    }

    // Каждая генерация — с чистого листа: иначе предыдущая реплика
    // подмешивается в контекст и модель начинает «продолжать» её.
    llama_memory_clear(llama_get_memory(state->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    if (llama_decode(state->ctx, batch) != 0) {
        LOGE("llama_decode упал на промпте");
        return nullptr;
    }

    // Жадное декодирование: пунктуация — детерминированная задача,
    // сэмплирование здесь только добавляет случайность и галлюцинации.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    const int limit = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < limit; ++i) {
        const llama_token id = llama_sampler_sample(sampler, state->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char buf[256];
        const int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        // Модель закончила ответ — дальше продолжать незачем.
        if (out.find("<|im_end|>") != std::string::npos) break;

        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(state->ctx, next) != 0) break;
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ru_hexteam_stenograph_engine_LocalLlmEngine_nativeRelease(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {

    auto *state = reinterpret_cast<LlmState *>(handle);
    if (state == nullptr) return;
    if (state->ctx) llama_free(state->ctx);
    if (state->model) llama_model_free(state->model);
    delete state;
    llama_backend_free();
}
