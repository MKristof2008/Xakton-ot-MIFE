# Чеклист сдачи

Состав пакета по требованиям хакатона. Статус отмечается честно; незакрытое — TODO.

| # | Артефакт | Статус |
|---|---|---|
| 1 | Release APK, устанавливается и работает в режиме полёта | Собран: `app-release-unsigned.apk` (10 МБ); проверка на устройстве и подпись — TODO |
| 2 | Исходный код в репозитории | Готово: ветки `feat/integration` (ML-ядро) и `feature/frontend` (UI/экспорт), PR объединяет |
| 3 | Инструкция по установке и использованию | Готово: docs/USER_GUIDE.md |
| 4 | Описание архитектуры и применяемых моделей | Готово: docs/ARCHITECTURE.md + docs/ML_MODELS.md (цифры — измеренные) |
| 5 | Ограничения решения | Готово: docs/LIMITATIONS.md |
| 6 | Результаты тестов (WER, RTF, таблица по записям) | Частично: docs/TEST_RESULTS.md (юнит-тесты ядра); замеры на устройстве — TODO после сборки APK |
| 7 | Демо-видео 2–3 минуты в режиме полёта | TODO — сценарий готов: docs/DEMO_SCENARIO.md |
| 8 | Ссылка на репозиторий | Готово: github.com/MKristof2008/Xakton-ot-MIFE |

## Технические проверки перед сдачей

- [x] В merged manifest release-сборки **нет** `android.permission.INTERNET`
      (проверено `aapt2 dump permissions` на `app-release-unsigned.apk`: только
      RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS).
- [x] В release-коде нет сетевых библиотек и аналитики/краш-SDK (проверено содержимое
      APK: okhttp/retrofit/ktor/volley/firebase отсутствуют).
- [x] Заглушки движков (`Stubs`) не входят в release-сборку (в APK классов `*Stub*` нет).
- [ ] Экспортный JSON проходит автопроверку ТЗ: схема, порядок по `start_ms`,
      `end_ms >= start_ms`, целые числа, UTF-8 без escape-кириллицы, лишних полей нет
      (покрыто unit-тестом `ExportTest`).
- [ ] Модели лежат на устройстве к моменту проверки; приложение работает без сети
      с первого запуска.
- [ ] Обработка переживает сворачивание (foreground service) и поворот экрана
      (состояние в ViewModel).
- [ ] Три тестовые записи экспертов прогнаны, таблица WER/RTF обновлена реальными цифрами.
