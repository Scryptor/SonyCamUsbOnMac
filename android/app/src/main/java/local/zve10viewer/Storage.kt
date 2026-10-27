package local.zve10viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.monitor.Waveform
import local.zve10viewer.view.FloatingPlacement

/** Настройки программы. Хранятся в SharedPreferences; имена и значения — как у программы для Mac. */
class Prefs(context: Context) {
    enum class Destination(val title: String, val cameraValue: Long) {
        macAndCard("На планшет и на карту камеры", 0x11),
        macOnly("Только на планшет", 0x01),
        cardOnly("Только на карту", 0x10);

        val savesToDevice: Boolean get() = this != cardOnly
    }

    enum class ReviewSource(val title: String) {
        downloaded("Загруженный снимок"),
        camera("Кадр с камеры"),
    }

    private val prefs = context.getSharedPreferences("zv", Context.MODE_PRIVATE)

    var destination: Destination
        get() = runCatching { Destination.valueOf(prefs.getString("photoDestination", "")!!) }.getOrDefault(Destination.macAndCard)
        set(value) = prefs.edit().putString("photoDestination", value.name).apply()

    var reviewEnabled: Boolean
        get() = prefs.getBoolean("reviewEnabled", true)
        set(value) = prefs.edit().putBoolean("reviewEnabled", value).apply()

    /** 1…10 секунд. */
    var reviewSeconds: Int
        get() = prefs.getInt("reviewSeconds", 3).coerceIn(1, 10)
        set(value) = prefs.edit().putInt("reviewSeconds", value.coerceIn(1, 10)).apply()

    var reviewSource: ReviewSource
        get() = runCatching { ReviewSource.valueOf(prefs.getString("reviewSource", "")!!) }.getOrDefault(ReviewSource.downloaded)
        set(value) = prefs.edit().putString("reviewSource", value.name).apply()

    var autoHide: Boolean
        get() = prefs.getBoolean("autoHideControls", true)
        set(value) = prefs.edit().putBoolean("autoHideControls", value).apply()

    /** Через сколько секунд без касаний прятать элементы (1…30). */
    var autoHideSeconds: Int
        get() = prefs.getInt("autoHideSeconds", 3).coerceIn(1, 30)
        set(value) = prefs.edit().putInt("autoHideSeconds", value.coerceIn(1, 30)).apply()

    /** Инструменты монитора (зебра, false color, пикинг, маркеры) — JSON, как у программы для Mac. */
    var monitorAssist: String?
        get() = prefs.getString("monitorAssist", null)
        set(value) = prefs.edit().putString("monitorAssist", value).apply()

    var waveformOn: Boolean
        get() = prefs.getBoolean("waveformOn", false)
        set(value) = prefs.edit().putBoolean("waveformOn", value).apply()

    var waveformMode: Waveform.Mode
        get() = runCatching { Waveform.Mode.valueOf(prefs.getString("waveformMode", "")!!) }.getOrDefault(Waveform.Mode.luma)
        set(value) = prefs.edit().putString("waveformMode", value.name).apply()

    /** Где волновая форма — как у гистограммы; null — на месте по умолчанию, справа внизу. */
    var waveformPlacement: FloatingPlacement?
        get() = if (prefs.contains("waveformX") && prefs.contains("waveformY")) {
            FloatingPlacement.restored(prefs.getFloat("waveformX", 0f), prefs.getFloat("waveformY", 0f))
        } else {
            null
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("waveformX").remove("waveformY")
            else edit.putFloat("waveformX", value.x.toFloat()).putFloat("waveformY", value.y.toFloat())
            edit.apply()
        }

    /** Откуда эталон для наложения: «кадр 12:04:31» или имя снимка; сам эталон — файл в данных программы. */
    var referenceSource: String?
        get() = prefs.getString("referenceSource", null)
        set(value) = prefs.edit().putString("referenceSource", value).apply()

    var histogramOn: Boolean
        get() = prefs.getBoolean("histogramOn", false)
        set(value) = prefs.edit().putBoolean("histogramOn", value).apply()

    /** Строка состояния справа вверху закреплена: остаётся на экране и когда элементы скрыты. Вместе с ними видна всегда. */
    var statusBarPinned: Boolean
        get() = prefs.getBoolean("statusBarPinned", true)
        set(value) = prefs.edit().putBoolean("statusBarPinned", value).apply()

    /**
     * Где гистограмма: доли свободного места по ширине и высоте (переживают поворот и смену размера экрана);
     * null — на месте по умолчанию, слева внизу над лентой снимков.
     */
    var histogramPlacement: FloatingPlacement?
        get() = if (prefs.contains("histogramX") && prefs.contains("histogramY")) {
            FloatingPlacement.restored(prefs.getFloat("histogramX", 0f), prefs.getFloat("histogramY", 0f))
        } else {
            null
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("histogramX").remove("histogramY")
            else edit.putFloat("histogramX", value.x.toFloat()).putFloat("histogramY", value.y.toFloat())
            edit.apply()
        }

    /** Вибрация при съёмке — если у планшета есть мотор. */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("hapticsEnabled", true)
        set(value) = prefs.edit().putBoolean("hapticsEnabled", value).apply()

    /** Красная рамка во время записи: включена, толщина, яркость, мигание. */
    var tallyStyle: TallyStyle
        get() = TallyStyle(
            enabled = prefs.getBoolean("tallyEnabled", true),
            width = prefs.getFloat("tallyWidth", 3f).coerceIn(1f, 12f),
            opacity = prefs.getFloat("tallyOpacity", 0.7f).coerceIn(0.3f, 1f),
            blink = prefs.getBoolean("tallyBlink", false),
        )
        set(value) = prefs.edit().putBoolean("tallyEnabled", value.enabled).putFloat("tallyWidth", value.width)
            .putFloat("tallyOpacity", value.opacity).putBoolean("tallyBlink", value.blink).apply()

    /** Подключаться к камере по Wi-Fi, когда её нет на кабеле. */
    var wifiEnabled: Boolean
        get() = prefs.getBoolean("wifiEnabled", true)
        set(value) = prefs.edit().putBoolean("wifiEnabled", value).apply()

    /** Последний адрес сопряжённой камеры в сети: с него начинаем поиск. */
    var wifiHost: String?
        get() = prefs.getString("wifiHost", null)
        set(value) = prefs.edit().putString("wifiHost", value).apply()

    /** Имя камеры, с которой сопряжены, — память прежних версий на одну камеру; пишется и теперь, для них. */
    var wifiCameraName: String?
        get() = prefs.getString("wifiCameraName", null)
        set(value) = prefs.edit().putString("wifiCameraName", value).apply()

    /** Камеры, которые программа знает (JSON, см. CameraBook); null — ключа нет: память ещё прежняя, на одну камеру. */
    var cameras: String?
        get() = prefs.getString("cameras", null)
        set(value) = prefs.edit().putString("cameras", value).apply()

    /** id камеры, к которой подключались последней. */
    var preferredCamera: String?
        get() = prefs.getString("preferredCamera", null)
        set(value) = prefs.edit().putString("preferredCamera", value).apply()

    /** Снижать качество превью по Wi-Fi (1024×576 → 640×360), только когда сигнала на полное не хватает. */
    var adaptiveLiveViewQuality: Boolean
        get() = prefs.getBoolean("adaptiveLiveViewQuality", true)
        set(value) = prefs.edit().putBoolean("adaptiveLiveViewQuality", value).apply()

    var presets: List<Preset>
        get() = PresetCatalog.fromJson(prefs.getString("presets", "[]")!!)
        set(value) = prefs.edit().putString("presets", PresetCatalog.toJson(value)).apply()

    /** В каком режиме камера была в последний раз: без связи показываем пресеты этого режима. До первой камеры — фото. */
    var presetMode: CaptureMode
        get() = CaptureMode.entries.firstOrNull { it.name == prefs.getString("presetMode", null) } ?: CaptureMode.photo
        set(value) = prefs.edit().putString("presetMode", value.name).apply()
}

/**
 * Красная рамка во время записи. width — толщина, dp (1…12); opacity — яркость, с которой рамка горит после вспышки на
 * старте (0,3…1); blink — медленно мигать между этой яркостью и её четвертью, раз в секунду.
 */
data class TallyStyle(val enabled: Boolean = true, val width: Float = 3f, val opacity: Float = 0.7f, val blink: Boolean = false)

/** Сохранённый файл: где лежит, как называется, какого типа. */
data class SavedFile(val uri: Uri, val name: String, val mime: String)

/** Снимки и стоп-кадры — в «Загрузки/ZV-E10» через MediaStore: разрешения на память не нужны, галерея их видит. */
class MediaSaver(private val context: Context) {
    val folderTitle = "Загрузки/$FOLDER"

    fun save(name: String, data: ByteArray): SavedFile {
        val resolver = context.contentResolver
        val unique = FileNaming.unique(name) { exists(it) }
        val mime = mimeOf(unique)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("не удалось создать файл $unique")
        resolver.openOutputStream(uri)?.use { it.write(data) } ?: throw IllegalStateException("не удалось записать $unique")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return SavedFile(uri, unique, mime)
    }

    /**
     * Снимок из файла — не больше maxSide точек по длинной стороне (для галереи: под экран). hardware — картинку только
     * рисуют, и ей место в памяти видеокарты, а не программы; для работы с точками — false.
     */
    fun decode(uri: Uri, maxSide: Int, hardware: Boolean = true): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = maxSide.toDouble() / maxOf(width, height)
            if (scale < 1) decoder.setTargetSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
            decoder.allocator = if (hardware) ImageDecoder.ALLOCATOR_DEFAULT else ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /** Участки снимка в полном разрешении — при сильном увеличении в галерее. Закрывать — recycle(). */
    fun regionDecoder(uri: Uri): BitmapRegionDecoder? = runCatching {
        // файл отображается в память: дескриптор после создания можно закрыть
        context.contentResolver.openFileDescriptor(uri, "r")?.use { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file)
            } else {
                @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.fileDescriptor, false)
            }
        }
    }.getOrNull()

    private fun exists(name: String): Boolean = context.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"),
        null,
    )?.use { it.count > 0 } ?: false

    companion object {
        const val FOLDER = "ZV-E10"

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "arw" -> "image/x-sony-arw"
            "hif", "heif" -> "image/heif"
            else -> "application/octet-stream"
        }

        /** Картинка не больше maxSide точек: для просмотра и миниатюр не нужен полный размер. */
        fun decode(jpeg: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** Размер JPEG в точках — по заголовку, без декодирования. */
        fun pixelSize(jpeg: ByteArray): IntSize? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            return if (bounds.outWidth > 0 && bounds.outHeight > 0) IntSize(bounds.outWidth, bounds.outHeight) else null
        }

        /** Уменьшенная копия для ленты — не больше maxSide точек по длинной стороне. */
        fun thumbnail(source: Bitmap, maxSide: Int): Bitmap {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
            return Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).roundToInt()),
                maxOf(1, (source.height * scale).roundToInt()), true)
        }
    }
}
