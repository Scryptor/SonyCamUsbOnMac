package local.zve10viewer

import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.CubeFile
import local.zve10viewer.monitor.LutCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Свои LUT на планшете: файлы `.cube`, которые пользователь добавил программе, в `filesDir/luts/`. Правила и тексты — как у
 * `LUTLibrary` на Mac; кэш кубов — тот самый [LutCache], что кладёт куб на картинку, второго нет: добавленный файл виден
 * кадровому циклу сразу, а замена и удаление не оставляют в нём старого куба.
 *
 * - **Добавление** ([importFile]): файл читается (не больше [maxFileBytes]), разбирается `CubeFile` (плохой не копируется) и
 *   кладётся в папку под своим именем без расширения; то же имя заменяет старый файл. Файлов не больше [LIMIT]. Файл читают из
 *   потока, который открывает вызывающий (на планшете — выбор файла через систему), так что здесь нет ни `Uri`, ни Android.
 * - **Имена** ([names]) — имена файлов без `.cube`, по алфавиту без учёта регистра, числа по значению (`look2` раньше `look10`).
 *   В списке только `.cube` с маленькими буквами в расширении — такие файлы кладёт сама программа, и только их читает кэш.
 * - **Замена не теряет прежнее, пока новое не на диске.** Новое сперва пишется во временный скрытый файл рядом (его не видно в
 *   списке), потом одним переименованием ложится на место, и только потом правится написание имени. «Look» и «look» — одно
 *   имя, как на Mac: на диске планшета это два файла, и прежний убирается; на диске без различия регистра (Mac, где идут тесты)
 *   это один и тот же файл, и он переименовывается.
 *
 * К файлу ведёт только имя из списка: «../чужой» или «a/b» не совпадают ни с одним файлом папки, так что удалить за её
 * пределами нельзя. Вызывать можно из любого потока: чтение и разбор файла идут сами по себе, а запись в папку и удаление — по
 * очереди (лимит и замена не расходятся при двух добавлениях сразу, и «Удалить» не ждёт долгого чтения).
 *
 * @param maxFileBytes файл тяжелее не читается до конца и считается слишком большим
 * @param write как новое содержимое пишется во временный файл; тесты подменяют, чтобы проверить, что не записалось — прежнее на
 *   месте
 */
class LutStore(
    private val cache: LutCache,
    private val maxFileBytes: Long = LutCache.MAX_FILE_BYTES,
    private val write: (ByteArray, File) -> Unit = { data, file -> file.writeBytes(data) },
) {
    /** Почему файл не добавился. Текст для строки состояния — [message]. */
    sealed class Failure(val message: String) {
        /** Файл не открылся или оборвался на чтении. */
        data object Unreadable : Failure("Не удалось прочитать файл")

        /** Файл больше предела (куб 65³ — до 12 МБ) или не поместился в память. */
        data object TooBig : Failure(LutCache.TOO_BIG)

        /** Это не 3D `.cube` или он испорчен: `CubeFile` говорит, чем. */
        data class Cube(val reason: CubeFile.Failure) : Failure(CubeFile.message(reason))

        /** Своих LUT уже [LIMIT]. */
        data object Limit : Failure("Своих LUT не больше $LIMIT — удалите ненужный")

        /** Копия в папку программы не записалась. */
        data object Unsaved : Failure("Не удалось сохранить LUT в папке программы")
    }

    /** Чем кончилось добавление: файл лёг в папку под этим именем или отказ. */
    sealed class Outcome {
        /** [name] — имя, как оно легло на диск. */
        data class Added(val name: String) : Outcome()

        data class Refused(val failure: Failure) : Outcome()
    }

    /** Папка своих LUT; её может не быть. */
    val folder: File get() = cache.folder

    /** Имена своих LUT по алфавиту; папки нет или она пуста — пусто. */
    val names: List<String> get() = entries()

    /**
     * Скопировать файл в папку и вернуть имя — имя файла без расширения, как оно легло на диск (замена под другим написанием,
     * которую не удалось переименовать, оставляет прежнее). Порядок отказов: файл не читается, потом сам файл (разбор), потом
     * лимит: полная папка и плохой файл — это «плохой файл», место ему не поможет. Не записалось — в папке ничего не изменилось.
     *
     * @param fileName имя файла, каким его назвал выбор файла (например `Тёплый закат.cube`); из него берётся имя LUT
     * @param open открывает поток с содержимым; null или исключение — файл не читается
     */
    fun importFile(fileName: String, open: () -> InputStream?): Outcome {
        // чтение и разбор — вне замка: файл из облака качается долго, и «Удалить» на главном потоке не должно ждать его
        val data = when (val read = read(open)) {
            is Read.Bytes -> read.data
            is Read.Refused -> return Outcome.Refused(read.failure)
        }
        // не бросающее чтение UTF-8: Latin-1 в TITLE (или в комментарии) не должен ронять файл — такие байты заменяются
        val lut = try {
            CubeFile.parse(String(data, Charsets.UTF_8))
        } catch (failure: CubeFile.Failure) {
            return Outcome.Refused(Failure.Cube(failure))
        } catch (e: OutOfMemoryError) {
            return Outcome.Refused(Failure.TooBig)
        } catch (e: Exception) {
            return Outcome.Refused(Failure.Unreadable)
        }
        return place(baseName(fileName), data, lut)
    }

    /** Положить прочитанный и разобранный файл в папку: лимит, запись, замена. Папка меняется по одному за раз. */
    @Synchronized
    private fun place(name: String, data: ByteArray, lut: ColorLut): Outcome {
        val existing = entries()
        val same = existing.filter { it.equals(name, ignoreCase = true) }
        if (same.isEmpty() && existing.size >= LIMIT) return Outcome.Refused(Failure.Limit)

        val folder = cache.folder
        val destination = File(folder, "$name.cube")
        // сначала новое — во временный скрытый файл: не записалось — прежнее не тронуто
        val temporary = File(folder, ".import-${UUID.randomUUID()}.tmp")
        try {
            if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw IOException("нет папки $folder")
            write(data, temporary)
            // на место — одним переименованием: прежний файл под этим именем заменяется целиком, а не наполовину
            if (!temporary.renameTo(destination)) throw IOException("не легло на место: $destination")
        } catch (e: Exception) {
            temporary.delete()
            return Outcome.Refused(Failure.Unsaved)
        }
        // Новое уже на диске, так что дальше — только написание имени. Диск без различия регистра: «Look.cube» остался с
        // прежним написанием — тот же файл переименовывается. Диск с регистром: «Look.cube» — другой файл, он уходит.
        val twins = entries().filter { it.equals(name, ignoreCase = true) }
        for (old in twins) {
            if (old == name) continue
            val file = File(folder, "$old.cube")
            if (twins.size > 1) file.delete() else file.renameTo(destination)
            cache.forget(old)
        }
        // имя — как оно легло на диск: если переименовать не вышло, это прежнее написание, и список с выбором не расходятся
        val stored = entries().firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        cache.adopt(stored, lut)
        return Outcome.Added(stored)
    }

    /** Убрать свой LUT из папки (исходный файл, откуда его брали, не тронут). Такого нет — ничего не происходит. */
    @Synchronized
    fun delete(name: String) {
        val entry = entries().firstOrNull { it == name } ?: return
        File(cache.folder, "$entry.cube").delete()
        cache.forget(name)
    }

    // папка

    /** Файлы `.cube` папки (обычные, не скрытые), по алфавиту. */
    private fun entries(): List<String> {
        val files = cache.folder.listFiles() ?: return emptyList()
        return files.filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(EXTENSION) }
            .map { it.name.removeSuffix(EXTENSION) }
            .sortedWith { a, b -> compareNames(a, b) }
    }

    private sealed interface Read {
        class Bytes(val data: ByteArray) : Read

        class Refused(val failure: Failure) : Read
    }

    /** Содержимое файла; тяжелее предела — не читается дальше предела, поток не открылся или оборвался — отказ. */
    private fun read(open: () -> InputStream?): Read {
        try {
            val input = open() ?: return Read.Refused(Failure.Unreadable)
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxFileBytes) return Read.Refused(Failure.TooBig)
                    out.write(buffer, 0, count)
                }
                return Read.Bytes(out.toByteArray())
            }
        } catch (e: OutOfMemoryError) {
            return Read.Refused(Failure.TooBig)
        } catch (e: Exception) {
            return Read.Refused(Failure.Unreadable)
        }
    }

    companion object {
        /** Больше своих LUT не бывает; тринадцатый отказывается. */
        const val LIMIT = 12

        private const val EXTENSION = ".cube"
        private const val BUFFER = 64 * 1024

        /**
         * Имя нового файла: последняя часть имени от выбора файла (с путём его не пускают за папку) без расширения, без ведущих
         * точек и пробелов (скрытый файл в списке не показался бы) и без пробелов в конце. Совсем пустое — «LUT». Расширение —
         * всё после последней точки, если точка не первая: «Look.v2.cube» → «Look.v2», «.cube» → «cube».
         */
        internal fun baseName(fileName: String): String {
            val last = fileName.substringAfterLast('/').substringAfterLast('\\').replace("\u0000", "")
            val dot = last.lastIndexOf('.')
            val stem = if (dot > 0) last.substring(0, dot) else last
            return stem.trimStart { it == '.' || it.isWhitespace() }.trimEnd().ifEmpty { "LUT" }
        }

        /**
         * Порядок имён: без учёта регистра, числа по значению (`look2` раньше `look10`, `x007` и `x7` — одно число); равные без
         * учёта регистра — по буквам, чтобы порядок был полным. Цифры — только 0–9.
         */
        internal fun compareNames(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val x = a[i]
                val y = b[j]
                if (x in '0'..'9' && y in '0'..'9') {
                    var endA = i
                    while (endA < a.length && a[endA] in '0'..'9') endA += 1
                    var endB = j
                    while (endB < b.length && b[endB] in '0'..'9') endB += 1
                    val numberA = a.substring(i, endA).trimStart('0')
                    val numberB = b.substring(j, endB).trimStart('0')
                    // длиннее без нулей спереди — больше; одной длины — по цифрам
                    if (numberA.length != numberB.length) return numberA.length.compareTo(numberB.length)
                    val byDigits = numberA.compareTo(numberB)
                    if (byDigits != 0) return byDigits
                    i = endA
                    j = endB
                } else {
                    val lowerA = x.lowercaseChar()
                    val lowerB = y.lowercaseChar()
                    if (lowerA != lowerB) return lowerA.compareTo(lowerB)
                    i += 1
                    j += 1
                }
            }
            val rest = (a.length - i).compareTo(b.length - j)
            return if (rest != 0) rest else a.compareTo(b)
        }
    }
}
