package local.zve10viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog

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

    var histogramOn: Boolean
        get() = prefs.getBoolean("histogramOn", false)
        set(value) = prefs.edit().putBoolean("histogramOn", value).apply()

    /** Подключаться к камере по Wi-Fi, когда её нет на кабеле. */
    var wifiEnabled: Boolean
        get() = prefs.getBoolean("wifiEnabled", true)
        set(value) = prefs.edit().putBoolean("wifiEnabled", value).apply()

    /** Последний адрес сопряжённой камеры в сети: с него начинаем поиск. */
    var wifiHost: String?
        get() = prefs.getString("wifiHost", null)
        set(value) = prefs.edit().putString("wifiHost", value).apply()

    var wifiCameraName: String?
        get() = prefs.getString("wifiCameraName", null)
        set(value) = prefs.edit().putString("wifiCameraName", value).apply()

    var presets: List<Preset>
        get() = PresetCatalog.fromJson(prefs.getString("presets", "[]")!!)
        set(value) = prefs.edit().putString("presets", PresetCatalog.toJson(value)).apply()
}

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
    }
}
