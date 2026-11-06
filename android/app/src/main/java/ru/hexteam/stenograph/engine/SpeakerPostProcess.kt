package ru.hexteam.stenograph.engine

import ru.hexteam.stenograph.core.SpeakerSegment

/**
 * Постобработка разметки диаризации.
 *
 * Зачем: детектор речи отдаёт только «чистые» участки, а на реальных записях
 * между фразами одного человека есть паузы и вдохи. Из-за этого один и тот же
 * говорящий распадается на десяток коротких интервалов, а паузы внутри его
 * реплики никому не принадлежат — на замерах это давало ~35 % пропусков речи
 * (см. docs/benchmarks/measurements.csv).
 *
 * Правила простые и проверяемые:
 *  1. Интервалы одного говорящего, между которыми пауза короче [MAX_BRIDGE_MS],
 *     склеиваются: пауза внутри фразы принадлежит тому же говорящему.
 *  2. Совсем короткие интервалы ([MIN_SEGMENT_MS]) отбрасываются: это шум
 *     сегментатора, а не реплика.
 *  3. Пересечения устраняются: побеждает интервал, начавшийся раньше.
 *  4. Паузы между репликами разных говорящих делятся пополам: смена
 *     говорящего происходит где-то в паузе, и половина — наименее смещённая
 *     оценка. Без этого вся пауза остаётся «ничьей» и считается пропуском
 *     речи (на замере — 30 % DER только за счёт этого).
 *
 * Чего здесь намеренно нет: подгонки под число говорящих. Если детектор
 * нашёл трёх человек вместо двух, постобработка не имеет права это «исправить»
 * догадкой — это была бы подмена результата модели.
 */
object SpeakerPostProcess {

    /** Пауза внутри реплики, которую склеиваем. Больше — это уже смена реплики. */
    const val MAX_BRIDGE_MS = 1200L

    /** Короче этого интервал не считаем репликой: обычно это вдох или щелчок. */
    const val MIN_SEGMENT_MS = 200L

    /** Максимальная пауза, которую делим между соседними говорящими. */
    const val MAX_FILL_GAP_MS = 4000L

    fun apply(
        segments: List<SpeakerSegment>,
        maxBridgeMs: Long = MAX_BRIDGE_MS,
        minSegmentMs: Long = MIN_SEGMENT_MS,
    ): List<SpeakerSegment> {
        if (segments.isEmpty()) return emptyList()

        val cleaned = segments
            .filter { it.endMs - it.startMs >= minSegmentMs }
            .sortedBy { it.startMs }

        // 1. Склейка пауз внутри одного говорящего.
        val merged = ArrayList<SpeakerSegment>(cleaned.size)
        for (seg in cleaned) {
            val prev = merged.lastOrNull()
            if (prev != null &&
                prev.speaker == seg.speaker &&
                seg.startMs - prev.endMs <= maxBridgeMs
            ) {
                merged[merged.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, seg.endMs))
            } else {
                merged += seg
            }
        }

        // 2. Устранение пересечений: порядок по началу, конец не заходит
        //    в следующий интервал другого говорящего.
        val trimmed = ArrayList<SpeakerSegment>(merged.size)
        for ((i, seg) in merged.withIndex()) {
            val next = merged.getOrNull(i + 1)
            val end = if (next != null && next.speaker != seg.speaker && next.startMs < seg.endMs) {
                next.startMs
            } else {
                seg.endMs
            }
            if (end > seg.startMs) trimmed += seg.copy(endMs = end)
        }

        // 3. Паузы между репликами разных говорящих делим пополам.
        val filled = ArrayList<SpeakerSegment>(trimmed.size)
        for ((i, seg) in trimmed.withIndex()) {
            val next = trimmed.getOrNull(i + 1)
            if (next == null || next.speaker == seg.speaker) {
                filled += seg
                continue
            }
            val gap = next.startMs - seg.endMs
            if (gap <= 0 || gap > maxBridgeMs * 4) {
                filled += seg
                continue
            }
            val half = seg.endMs + gap / 2
            filled += seg.copy(endMs = half)
            trimmed[i + 1] = next.copy(startMs = half)
        }
        return filled
    }
}
