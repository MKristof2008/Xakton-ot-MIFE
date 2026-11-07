package ru.mifi.stenograf.history

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.mifi.stenograf.output.HexJson
import ru.mifi.stenograf.output.Replica
import java.io.File

/**
 * История расшифровок.
 *
 * Зачем хранить копию аудио рядом с результатом: пользователь должен иметь
 * возможность прослушать запись, к которой относится расшифровка, спустя дни.
 * Исходный URI (content://) может «протухнуть» после перезагрузки, а файл
 * во внутреннем каталоге приложения живёт, пока живёт приложение.
 *
 * Формат: каталог history/<id>/ с файлами audio.* (копия записи),
 * meta.json (метрики и список реплик) и index.json на всё сразу.
 */
class HistoryStore(private val context: Context) {

    companion object {
        private const val TAG = "History"
        private const val INDEX = "index.json"
        const val MAX_ITEMS = 30
    }

    /** Одна запись истории. */
    data class Item(
        val id: String,
        val title: String,
        val createdAt: Long,
        val durationMs: Long,
        val processingMs: Long,
        val speakerCount: Int,
        val replicaCount: Int,
        val audioPath: String?,
        val replicas: List<Replica>,
    ) {
        val rtf: Float get() = if (durationMs > 0) processingMs.toFloat() / durationMs else 0f
    }

    private fun root() = File(context.filesDir, "history").apply { mkdirs() }
    private fun dirOf(id: String) = File(root(), id).apply { mkdirs() }

    /** Список, новые сверху. */
    suspend fun list(): List<Item> = withContext(Dispatchers.IO) {
        val index = readIndex()
        index.mapNotNull { id -> read(id) }
    }

    suspend fun get(id: String): Item? = withContext(Dispatchers.IO) { read(id) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dirOf(id).deleteRecursively()
        val ids = readIndex().filterNot { it == id }
        writeIndex(ids)
        Unit
    }

    /**
     * Сохраняет новую расшифровку. Копирует аудио, чтобы его можно было
     * прослушать из истории, даже если исходный файл удалён.
     */
    suspend fun save(
        title: String,
        audioUri: Uri?,
        durationMs: Long,
        processingMs: Long,
        speakerCount: Int,
        replicas: List<Replica>,
    ): Item? = withContext(Dispatchers.IO) {
        try {
            val id = "h${System.currentTimeMillis()}"
            val dir = dirOf(id)

            // Копия аудио. Размер ограничен: расшифровка 10 минут = ~19 МБ WAV,
            // для истории это приемлемо, но старые записи вытесняются.
            val audioPath = audioUri?.let { uri -> copyAudio(dir, uri) }

            val json = JSONObject()
                .put("id", id)
                .put("title", title.ifBlank { "Запись" })
                .put("createdAt", System.currentTimeMillis())
                .put("durationMs", durationMs)
                .put("processingMs", processingMs)
                .put("speakerCount", speakerCount)
                .put("audioPath", audioPath)
                .put("replicas", HexJson.toJsonArray(replicas))

            File(dir, "meta.json").writeText(json.toString(), Charsets.UTF_8)

            val ids = (listOf(id) + readIndex()).distinct()
            // Держим не больше MAX_ITEMS: самое старое удаляем вместе с аудио.
            val keep = ids.take(MAX_ITEMS)
            (ids - keep.toSet()).forEach { dirOf(it).deleteRecursively() }
            writeIndex(keep)

            read(id)
        } catch (e: Exception) {
            Log.w(TAG, "не удалось сохранить расшифровку в историю", e)
            null
        }
    }

    private fun copyAudio(dir: File, uri: Uri): String? = runCatching {
        val target = File(dir, "audio.wav")
        val input = when (uri.scheme) {
            "file" -> java.io.FileInputStream(uri.path!!)
            else -> context.contentResolver.openInputStream(uri) ?: return@runCatching null
        }
        input.use { src -> target.outputStream().use { out -> src.copyTo(out, 1 shl 20) } }
        target.name
    }.getOrNull()

    private fun read(id: String): Item? = runCatching {
        val meta = File(dirOf(id), "meta.json")
        if (!meta.exists()) return@runCatching null
        val o = JSONObject(meta.readText(Charsets.UTF_8))
        val arr = o.optJSONArray("replicas") ?: JSONArray()
        val reps = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Replica(
                speakerId = r.getString("speaker_id"),
                startMs = r.getInt("start_ms"),
                endMs = r.getInt("end_ms"),
                text = r.getString("text"),
            )
        }
        val audioName = o.optString("audioPath", "").takeIf { it.isNotBlank() }
        Item(
            id = id,
            title = o.optString("title", "Запись"),
            createdAt = o.optLong("createdAt", 0L),
            durationMs = o.optLong("durationMs", 0L),
            processingMs = o.optLong("processingMs", 0L),
            speakerCount = o.optInt("speakerCount", 0),
            replicaCount = reps.size,
            audioPath = audioName?.let { File(dirOf(id), it).absolutePath },
            replicas = reps,
        )
    }.getOrElse { e ->
        Log.w(TAG, "битая запись истории $id", e)
        null
    }

    private fun readIndex(): List<String> = runCatching {
        val f = File(root(), INDEX)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<String>) {
        runCatching {
            File(root(), INDEX).writeText(JSONArray(ids).toString(), Charsets.UTF_8)
        }
    }
}