package ru.hexteam.stenograph.engine

import android.content.Context
import java.io.File

/**
 * Однократное декодирование аудио в WAV 16 кГц / моно.
 *
 * Зачем: ASR и диаризация работают с одним и тем же файлом. Декодировать его
 * дважды (MediaCodec — не бесплатный) означало бы платить за это в RTF, а RTF
 * входит в оценку. Здесь результат кладётся в кэш и переиспользуется.
 *
 * Кэш адресуется по исходному файлу (имя + размер + время изменения), поэтому
 * подмена файла с тем же именем не даёт устаревший WAV.
 */
object AudioPrep {

    private data class Entry(val source: String, val size: Long, val mtime: Long, val wav: File)

    private var last: Entry? = null

    /**
     * @return WAV 16 кГц / моно для исходного файла; null — декодировать не удалось.
     * @param keep если true, файл остаётся в кэше после использования (нужен второму этапу)
     */
    fun prepare(context: Context, source: File, keep: Boolean = true): File? {
        val cached = last
        if (cached != null &&
            cached.source == source.absolutePath &&
            cached.size == source.length() &&
            cached.mtime == source.lastModified() &&
            cached.wav.isFile && cached.wav.length() > 44
        ) {
            return cached.wav
        }

        val wav = AudioConverter.toWav16kMono(context, source) ?: return null
        last = Entry(source.absolutePath, source.length(), source.lastModified(), wav)
        if (!keep) wav.delete()
        return wav
    }

    /** Убирает за собой: вызывается по завершении обработки. */
    fun clear() {
        last?.wav?.delete()
        last = null
    }
}
