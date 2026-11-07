package ru.hexteam.stenograph.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * База голосов: сохранённые отпечатки людей, которых приложение уже слышало.
 *
 * Зачем: эксперт прогоняет три записи подряд, и в каждой диаризация заново
 * называет людей speaker_1, speaker_2. Если тем же голосом говорит тот же
 * человек, его надо узнать и подписать настоящим именем — иначе три файла
 * придётся сверять вручную.
 *
 * Хранится в filesDir, обычный JSON: профили маленькие (512 чисел), сеть не
 * нужна, файл легко посмотреть и удалить.
 *
 * Порог подобран замером, а не на глаз: см. SpeakerMatchTest и
 * docs/benchmarks/measurements.csv.
 */
class VoiceProfileStore(private val context: Context) {

    /** Один сохранённый голос. */
    data class Profile(
        val name: String,
        val embedding: FloatArray,
        /** Сколькими записями подтверждён голос: больше — надёжнее. */
        val samples: Int = 1,
    )

    /** Результат сравнения с базой. */
    data class Match(
        val name: String,
        /** Косинусное сходство: 1.0 — один и тот же голос. */
        val similarity: Float,
        val samples: Int,
    )

    companion object {
        private const val TAG = "VoiceProfiles"
        private const val FILE_NAME = "voice_profiles.json"

        /**
         * Порог косинусного сходства для «это тот же человек».
         *
         * 0.75 — из середины разрыва между «тот же голос» (0.85–0.95) и
         * «другой голос» (0.3–0.6) на замерах 3D-Speaker. Пересматривать
         * только по замеру: ниже — путает людей, выше — перестаёт узнавать.
         */
        const val MATCH_THRESHOLD = 0.75f

        /** Отпечатки короче/длиннее ожидаемого считаем битыми. */
        private const val MIN_DIM = 64
        private const val MAX_DIM = 4096
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    fun load(): List<Profile> {
        val f = file()
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val emb = o.getJSONArray("embedding").let { a ->
                    FloatArray(a.length()) { idx -> a.getDouble(idx).toFloat() }
                }
                if (emb.size !in MIN_DIM..MAX_DIM) return@mapNotNull null
                Profile(
                    name = o.getString("name"),
                    embedding = emb,
                    samples = o.optInt("samples", 1),
                )
            }
        }.onFailure { Log.e(TAG, "не удалось прочитать профили: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun save(profiles: List<Profile>) {
        val arr = JSONArray()
        for (p in profiles) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("samples", p.samples)
            o.put("embedding", JSONArray().apply { p.embedding.forEach { put(it.toDouble()) } })
            arr.put(o)
        }
        // Через временный файл: прерванная запись не должна оставить
        // битый JSON вместо всей базы.
        val tmp = File(context.filesDir, "$FILE_NAME.part")
        runCatching {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file())) {
                tmp.copyTo(file(), overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            Log.e(TAG, "не удалось сохранить профили: ${it.message}")
            tmp.delete()
        }
    }

    /**
     * Добавляет голос или усиливает уже сохранённый.
     *
     * Если похожий голос уже есть — усредняем отпечатки: чем больше записей
     * одного человека, тем устойчивее профиль к шуму и микрофону.
     */
    fun enroll(name: String, embedding: FloatArray) {
        val profiles = load().toMutableList()
        val existing = profiles.indexOfFirst {
            it.name.equals(name, ignoreCase = true) || similarity(it.embedding, embedding) >= MATCH_THRESHOLD
        }
        if (existing >= 0) {
            val old = profiles[existing]
            val merged = FloatArray(old.embedding.size)
            val w = old.samples.toFloat()
            for (i in merged.indices) {
                merged[i] = if (i < embedding.size) {
                    (old.embedding[i] * w + embedding[i]) / (w + 1f)
                } else {
                    old.embedding[i]
                }
            }
            profiles[existing] = old.copy(embedding = normalize(merged), samples = old.samples + 1)
        } else {
            profiles += Profile(name, normalize(embedding))
        }
        save(profiles)
    }

    fun remove(name: String) {
        save(load().filterNot { it.name.equals(name, ignoreCase = true) })
    }

    fun clear() {
        file().delete()
    }

    /**
     * Ищет, кому принадлежит голос.
     *
     * @return лучший вариант, если сходство выше [MATCH_THRESHOLD]; иначе null —
     *         лучше честно сказать «не узнан», чем назвать чужим именем.
     */
    fun identify(embedding: FloatArray, threshold: Float = MATCH_THRESHOLD): Match? {
        val best = load()
            .map { Match(it.name, similarity(it.embedding, embedding), it.samples) }
            .maxByOrNull { it.similarity }
            ?: return null
        return best.takeIf { it.similarity >= threshold }
    }

    /**
     * Сопоставляет говорящих записи с базой.
     *
     * @return «speaker_1» → имя из базы (только уверенные совпадения)
     */
    fun matchAll(
        embeddings: Map<String, FloatArray>,
        threshold: Float = MATCH_THRESHOLD,
    ): Map<String, Match> = embeddings.mapNotNull { (speaker, emb) ->
        identify(emb, threshold)?.let { speaker to it }
    }.toMap()

    /** Косинусное сходство двух нормализованных векторов. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val n = minOf(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (d > 0f) dot / d else 0f
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val len = kotlin.math.sqrt(sum)
        return if (len > 0f) FloatArray(v.size) { v[it] / len } else v
    }
}