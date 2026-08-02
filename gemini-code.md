# СТРОГОЕ ТЗ: Разработка кастомного Android TV плеера (Media3 / ExoPlayer) для Lampa

## 1. Концепция и ограничения
Требуется написать минималистичное Android-приложение (Kotlin) для Android TV. Это исключительно внешний плеер, вызываемый из каталогизатора Lampa через `Intent`. 

**Абсолютные запреты:**
* ЗАПРЕЩЕНО использовать C++, NDK и собирать кастомные библиотеки FFmpeg.
* ЗАПРЕЩЕНО добавлять сетевые протоколы (SMB, DLNA, NFS) и любые интерфейсы выбора файлов.
* Целевой вес APK: до 10-15 МБ. Максимальная производительность на слабом железе (бюджетные чипы Realtek).

## 2. Главные технические задачи
1. **Авторизация потока:** Проброс HTTP-заголовков (`User-Agent`, `Referer`, `Bearer`), получаемых от Lampa, для обхода блокировок балансеров (Alloha).
2. **Фикс черного экрана (Hardware Fallback):** Аппаратный декодер ТВ зависает на профилях Dolby Vision в 4K. Требуется программно игнорировать метаданные DV и отдавать приоритет базовому H.265 (HEVC) / H.264 через `TrackSelectionParameters`.
3. **Аппаратный Night Mode (Audio):** ВАЖНО! Звук выводится на внешние колонки через аналоговый 3.5mm jack. Из-за этого телевизор отключает системную компрессию и обработку Dolby. Требуется принудительная реализация программного аудиокомпрессора (Limiter) для выравнивания громкости (чтобы тихие диалоги были слышны, а взрывы не перегружали выход).

## 3. Стек
* Актуальный `AndroidX Media3` (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui`).
* `androidx.leanback:leanback` (для навигации пультом).

## 4. Требования к реализации

### Шаг 1. Манифест
* `intent-filter` с `action android.intent.action.VIEW`.
* Mime-типы: `video/*`, `application/vnd.apple.mpegurl` (HLS/m3u8).

### Шаг 2. Перехват Intent и HTTP-заголовков (DataSource)
* Извлечь URL из `intent.data`.
* Извлечь словарь заголовков из `intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")`.
* Реализовать `DefaultHttpDataSource.Factory()`, передав заголовки через `setDefaultRequestProperties()`.

### Шаг 3. Инициализация ExoPlayer и фикс видео
* В `DefaultRenderersFactory` включить `setEnableDecoderFallback(true)`.
* Настроить `DefaultTrackSelector`. Исключить выбор треков с Dolby Vision. Заставить плеер выбирать стандартные видеопотоки, поддерживаемые аппаратным декодером (MediaCodec).

### Шаг 4. Компрессор звука (DynamicsProcessing для 3.5mm jack)
* Получить `AudioSessionId` плеера.
* Создать и привязать `DynamicsProcessing`.
* Настроить `Limiter` (`DynamicsProcessing.Config.Builder`) на 0-й и 1-й аудиоканалы.
* Параметры компрессии: жесткое подавление пиков и усиление тихих звуков. Порог срабатывания (`threshold`) примерно -12f, усиление (`postGain`) +6f.

### Шаг 5. UI и Управление
* Максимально чистый `PlayerView` (Immersive mode, без системных баров).
* Управление D-Pad: Влево/Вправо (перемотка 10 сек), Ок (Play/Pause).