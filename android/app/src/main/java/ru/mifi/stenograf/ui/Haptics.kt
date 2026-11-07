package ru.mifi.stenograf.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Тактильная отдача. На демо это половина «вау»: когда кнопка отвечает
 * вибрацией, приложение ощущается как железо, а не как веб-страница.
 * Все вызовы безопасны: если вибратора нет или пользователь их отключил,
 * просто ничего не происходит.
 */
object Haptics {

    /**
     * Переключатель из настроек. Раньше тумблер «Вибрация при нажатиях»
     * сохранялся, но ни на что не влиял: Haptics его не читал и всегда
     * вибрировал. Теперь настройка проверяется здесь — одна точка,
     * все вызовы [tap]/[confirm]/[success]/[warn] её уважают.
     */
    private fun enabled(context: Context): Boolean =
        runCatching { ru.mifi.stenograf.settings.SettingsStore(context).haptics }
            .getOrDefault(true)

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** Короткий «клик» — нажатия, переключения. */
    fun tap(context: Context) = oneShot(context, 12, 60)

    /** Мягкий «толчок» — старт обработки, важное действие. */
    fun confirm(context: Context) = oneShot(context, 28, 110)

    /** Двойной «успех» — когда расшифровка готова. */
    fun success(context: Context) = pattern(context, longArrayOf(0, 18, 60, 26), intArrayOf(0, 90, 0, 140))

    /** Предупреждение — ошибка или отказ. */
    fun warn(context: Context) = pattern(context, longArrayOf(0, 14, 50, 14), intArrayOf(0, 160, 0, 160))

    private fun oneShot(context: Context, ms: Long, amplitude: Int) {
        if (!enabled(context)) return
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    private fun pattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        if (!enabled(context)) return
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        }
    }
}