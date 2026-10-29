package local.zve10viewer.monitor

import local.zve10viewer.monitor.MonitorAssist.LutMode
import java.io.File
import java.util.EnumMap
import java.util.EnumSet

/**
 * Свойства главной камеры, по которым «Авто» выбирает LUT: профиль изображения `0xD23F`, гамма профиля `0xD0E1` и его цвет
 * `0xD0E9`. null — камеры нет или такого свойства у неё нет (у ZV-E10 гаммы и цвета нет, и «Авто» судит по номеру профиля).
 */
data class CameraProfile(val pictureProfile: Long? = null, val gamma: Long? = null, val colorMode: Long? = null) {
    companion object {
        /** Камеры нет: «Авто» не по чему выбирать. */
        val none = CameraProfile()
    }
}

/**
 * Кубы LUT, которые лежат на картинке: по одному экземпляру на LUT. Экземпляр важен — [PictureTransform] узнаёт куб по
 * ссылке и строит свою таблицу один раз на экземпляр; другой экземпляр с теми же числами стоил бы таблицы на каждый кадр.
 *
 * - **Встроенные** — 33³ из точных формул, один на вид на всю программу, делается при первом обращении ([builtin]) или заранее
 *   в фоне ([warm]): «строится при первом выборе».
 * - **Свои** — `.cube` из папки программы (`filesDir/luts/имя.cube`), ключ — имя, дата и длина файла. Файл читается и
 *   разбирается в фоне ([file]): куб 65³ — десятки миллисекунд на JVM и больше на планшете, а кадровый цикл ждать не может.
 *   Пока куба нет, [file] отдаёт null — и картинка идёт без LUT, как на Mac. Файл поменяли — он читается заново, пока
 *   новый не разобран, старый куб не отдаётся. Испорченный файл пробуют один раз на каждое изменение. В памяти не больше
 *   трёх разобранных кубов (65³ — несколько мегабайт); вытесняется тот, которым давно не пользовались.
 *
 * К файлу ведёт только имя из одной части: `../чужой`, `a/b` и скрытые файлы не открываются. Вызывать можно из любого потока.
 *
 * @param folder папка своих LUT; её может не быть
 * @param background где выполнять чтение и сборку: не в потоке вызова
 * @param journal что произошло с файлом: разобран или почему нет (текст — как в окне «Добавить LUT…»)
 * @param maxFileBytes файл тяжелее не читается: куб 65³ — около десяти мегабайт, всё, что больше, — не LUT
 * @param make как делается встроенный куб; тесты подменяют, чтобы проверить гонки
 */
class LutCache(
    private val folder: File,
    private val background: (Runnable) -> Unit = { task -> Thread(task, "LutCache").apply { isDaemon = true }.start() },
    private val journal: (String) -> Unit = {},
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val make: (BuiltinLut) -> ColorLut = { kind -> kind.make(BUILTIN_SIZE) },
) {
    // встроенные

    /** Коротко: что готово и что греется. Сборка куба идёт не под ним — иначе [warm] с главного потока ждал бы её. */
    private val buildLock = Any()
    private val built = EnumMap<BuiltinLut, ColorLut>(BuiltinLut::class.java)
    private val warming = EnumSet.noneOf(BuiltinLut::class.java)

    /** По замку на вид: куб собирает один поток, остальные, кому он нужен, ждут его же, а не делают второй. */
    private val builders = EnumMap<BuiltinLut, Any>(BuiltinLut::class.java).also { map -> for (kind in BuiltinLut.entries) map[kind] = Any() }

    /**
     * Встроенный куб: один на вид. Первый раз делает его здесь (на JVM — несколько миллисекунд, на планшете больше) и пишет в
     * журнал, сколько это заняло. Тот, кто пришёл во время сборки, ждёт её и получает тот же куб.
     */
    fun builtin(kind: BuiltinLut): ColorLut {
        synchronized(buildLock) { built[kind] }?.let { return it }
        val millis: Long
        val cube: ColorLut
        synchronized(builders.getValue(kind)) {
            synchronized(buildLock) { built[kind] }?.let { return it }
            val started = System.nanoTime()
            cube = make(kind)
            millis = (System.nanoTime() - started) / 1_000_000
            synchronized(buildLock) { built[kind] = cube }
        }
        journal("LUT ${kind.label}: куб $BUILTIN_SIZE³ готов за $millis мс")
        return cube
    }

    /** Заранее сделать встроенный куб в фоне, чтобы кадровый цикл не ждал его на первом кадре с LUT. Готовый не трогает. */
    fun warm(kind: BuiltinLut) {
        synchronized(buildLock) {
            if (kind in built || !warming.add(kind)) return
        }
        background(Runnable {
            try {
                builtin(kind)
            } finally {
                synchronized(buildLock) { warming.remove(kind) }
            }
        })
    }

    // выбор

    /** Какой встроенный LUT на экране (по плитке и профилю главной камеры); null — встроенного нет. */
    fun resolved(assist: MonitorAssist, camera: CameraProfile): BuiltinLut? =
        LutChoice.resolve(assist.lut, camera.pictureProfile, camera.gamma, camera.colorMode)

    /**
     * Куб, который лежит на картинке сейчас; null — LUT нет: «Выкл», «Авто» у камеры без профиля лога, «Свой» без файла или
     * пока файл не разобран. Решает он, а не настройка `lut`: по нему видно, нужен ли проход картинки вообще.
     */
    fun cube(assist: MonitorAssist, camera: CameraProfile): ColorLut? {
        // на каждый кадр: без лямбд и ссылок на функции, чтобы выбор не стоил выделений
        if (assist.lut == LutMode.file) {
            val name = assist.lutFile ?: return null
            return file(name)
        }
        val kind = resolved(assist, camera) ?: return null
        return builtin(kind)
    }

    // свои

    private val fileLock = Any()

    /** Последние обращения — в конце: выселяется начало. */
    private val slots = LinkedHashMap<String, Slot>(8, 0.75f, true)

    private class Slot(val file: File) {
        /** Дата и длина файла, для которых готов [lut]. */
        var modified = 0L
        var length = 0L

        /** Файл с такими датой и длиной прочитан; null в [lut] — не разобрался. */
        var done = false
        var lut: ColorLut? = null

        /** Чтение уже заказано. */
        var loading = false
    }

    /**
     * Куб своего LUT из памяти. Файл не читает и не разбирает: если куба нет (в первый раз, файл поменяли) — заказывает
     * чтение в фоне и отдаёт null; со следующих кадров — куб. Файла нет, имя не годится или файл не разобрался — null, и
     * заново он не читается, пока файл не изменится. Дёшево на каждый кадр: дата и длина файла и поиск по имени.
     */
    fun file(name: String): ColorLut? {
        if (!isPlainName(name)) return null
        val slot: Slot
        synchronized(fileLock) {
            val known = slots[name]
            val file = known?.file ?: File(folder, "$name.cube")
            val modified = file.lastModified()
            val length = file.length()
            // нет файла — нет и места под него: хороший куб из-за несуществующего имени не вытесняется
            if (modified == 0L && length == 0L) {
                if (known != null) slots.remove(name)
                return null
            }
            slot = known ?: Slot(file).also { slots[name] = it }
            if (slot.done && slot.modified == modified && slot.length == length) return slot.lut
            if (slot.loading) return null
            slot.loading = true
        }
        background(Runnable { load(name, slot) })
        return null
    }

    /** Забыть куб: файл заменили или удалили — следующий [file] прочитает его заново. */
    fun forget(name: String) {
        synchronized(fileLock) { slots.remove(name) }
    }

    private fun load(name: String, slot: Slot) {
        val started = System.nanoTime()
        // дата и длина — до чтения: если файл меняют прямо сейчас, следующий вызов увидит другие числа и прочитает заново
        val modified = slot.file.lastModified()
        val length = slot.file.length()
        var lut: ColorLut? = null
        var problem: String? = null
        try {
            // тяжёлый — не .cube: отказ как у чужого файла
            if (length > maxFileBytes) throw CubeFile.Failure.NotThreeD
            // не бросающее чтение UTF-8: Latin-1 в TITLE (или в комментарии) не должен ронять файл — такие байты заменяются
            lut = CubeFile.parse(String(slot.file.readBytes(), Charsets.UTF_8))
        } catch (failure: CubeFile.Failure) {
            problem = CubeFile.message(failure)
        } catch (e: Exception) {
            problem = "Не удалось прочитать файл"
        } finally {
            synchronized(fileLock) {
                slot.modified = modified
                slot.length = length
                slot.lut = lut
                slot.done = true
                slot.loading = false
                trim()
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        journal(if (lut != null) "LUT «$name»: куб ${lut.size}³ готов за $millis мс" else "LUT «$name»: $problem")
    }

    /** Больше [CUBE_LIMIT] разобранных кубов не держим: вытесняется тот, что давно не нужен. Вызывать под [fileLock]. */
    private fun trim() {
        var held = slots.values.count { it.lut != null }
        if (held <= CUBE_LIMIT) return
        val iterator = slots.values.iterator()
        while (held > CUBE_LIMIT && iterator.hasNext()) {
            if (iterator.next().lut != null) {
                iterator.remove()
                held -= 1
            }
        }
    }

    /** Имя одного файла в папке: без разделителей пути и не скрытое. */
    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name[0] != '.' && name.none { it == '/' || it == '\\' || it == '\u0000' }

    companion object {
        /** Сторона куба встроенных LUT: 33³. */
        const val BUILTIN_SIZE = 33

        /** Сколько разобранных своих кубов держится в памяти. */
        const val CUBE_LIMIT = 3

        /** Файл тяжелее не читается. */
        const val MAX_FILE_BYTES = 32L shl 20
    }
}
