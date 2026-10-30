package local.zve10viewer

import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.CubeFile
import local.zve10viewer.monitor.LutCache
import local.zve10viewer.monitor.PreviewLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Свои LUT на планшете: папка `filesDir/luts`, список, добавление (проверка разбором, копия, замена, не больше двенадцати),
 * удаление. Правила и тексты — как у `LUTLibrary` на Mac; кэш кубов — тот самый [LutCache], что кладёт куб на картинку.
 */
class LutStoreTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    @get:Rule val temp = TemporaryFolder()

    /** Фоновые задания кэша складываются сюда и выполняются, когда тест скажет. */
    private val queue = ArrayDeque<Runnable>()
    private val folder: File by lazy { File(temp.root, "luts") }
    private val notes = ArrayList<String>()

    private fun cache(read: (File) -> ByteArray = { it.readBytes() }) = LutCache(folder, queue::addLast, { notes += it }, read = read)

    private fun runBackground() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    /** Куб 2×2×2: тождественный или перевёрнутый (1 − значение) — два разных, но правильных файла. */
    private fun cube(inverted: Boolean = false, header: List<String> = listOf("LUT_3D_SIZE 2")): String {
        val rows = ArrayList<String>()
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            fun v(x: Int) = (if (inverted) 1 - x else x).toString()
            rows += "${v(r)} ${v(g)} ${v(b)}"
        }
        return (header + rows).joinToString("\n") + "\n"
    }

    /** Куб текста таким, каким он ложится на картинку: с доменом в значениях превью. */
    private fun onPicture(text: String): ColorLut = PreviewLevels.forPreview(CubeFile.parse(text))

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    /** Файл «с рабочего стола»: поток на байты. */
    private fun source(data: ByteArray): () -> InputStream? = { ByteArrayInputStream(data) }

    private fun added(outcome: LutStore.Outcome): String = (outcome as? LutStore.Outcome.Added)?.name
        ?: throw AssertionError("ждали «добавлен», вышло $outcome")

    private fun refusal(outcome: LutStore.Outcome): LutStore.Failure = (outcome as? LutStore.Outcome.Refused)?.failure
        ?: throw AssertionError("ждали отказ, вышло $outcome")

    private fun LutStore.add(fileName: String, text: String): String = added(importFile(fileName, source(bytes(text))))

    /** Что лежит в папке программы, по именам файлов. */
    private fun stored(): List<String> = (folder.list() ?: emptyArray()).sorted()

    // добавление

    @Test fun storeWithoutFolderIsEmptyAndDoesNotMakeIt() {
        val store = LutStore(cache())
        assertEquals(emptyList<String>(), store.names)
        // папки нет совсем — и не появляется от вопроса «что у вас есть»
        assertFalse(folder.exists())
    }

    @Test fun importCopiesTheFileUnderItsBaseName() {
        val cache = cache()
        val store = LutStore(cache)
        val text = cube(inverted = true, header = listOf("TITLE \"Закат\"", "LUT_3D_SIZE 2"))
        val name = store.add("Тёплый закат.cube", text)
        assertEquals("Тёплый закат", name)
        assertEquals(listOf("Тёплый закат"), store.names)
        // копия — те же байты
        assertEquals(listOf("Тёплый закат.cube"), stored())
        assertEquals(text, File(folder, "Тёплый закат.cube").readText())
        // куб уже в памяти того же кэша, который кладёт его на картинку: ждать фонового чтения не нужно
        assertEquals(onPicture(text), cache.file("Тёплый закат"))
        assertTrue(queue.isEmpty())
    }

    @Test fun theStoreAndThePictureShareOneCache() {
        val cache = cache()
        val store = LutStore(cache)
        store.add("grade.cube", cube())
        val first = cache.file("grade")
        assertNotNull(first)
        // заменили файл — кэш картинки получил новый куб, а не держит старый
        store.add("grade.cube", cube(inverted = true))
        val second = cache.file("grade")
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(onPicture(cube(inverted = true)), second)
        assertSame(second, cache.file("grade"))
    }

    @Test fun sameNameReplacesTheOldFile() {
        val store = LutStore(cache())
        store.add("grade.cube", cube(inverted = false))
        store.add("grade.cube", cube(inverted = true))
        assertEquals(listOf("grade"), store.names)
        assertEquals(cube(inverted = true), File(folder, "grade.cube").readText())
        assertEquals(listOf("grade.cube"), stored())
    }

    @Test fun sameNameInAnotherCaseReplacesToo() {
        // как на Mac, где «Look» и «look» — один файл: имена, отличающиеся только регистром, не множатся и на планшете
        val cache = cache()
        val store = LutStore(cache)
        store.add("Look.cube", cube(inverted = false))
        val old = cache.file("Look")
        assertNotNull(old)
        val name = store.add("look.cube", cube(inverted = true))
        assertEquals("look", name)
        assertEquals(listOf("look"), store.names)
        assertEquals(listOf("look.cube"), stored())
        assertEquals(cube(inverted = true), File(folder, "look.cube").readText())
        // куб под новым именем — новый (диск у разных телефонов и у Mac по-разному относится к регистру, поэтому старое
        // написание не проверяем: на диске с регистром его уже нет, на другом это тот же файл)
        assertNotSame(old, cache.file("look"))
        assertEquals(onPicture(cube(inverted = true)), cache.file("look"))
    }

    @Test fun anyExtensionIsAcceptedAndTheCopyIsStoredWithTheLowercaseOne() {
        // системный выбор файла не отфильтрован по типу (`*/*`): решает разбор, а не расширение
        val store = LutStore(cache())
        assertEquals("GRADE", store.add("GRADE.CUBE", cube()))
        assertEquals("notes", store.add("notes.txt", cube()))
        assertEquals("noextension", store.add("noextension", cube()))
        assertEquals("LUT.v2", store.add("LUT.v2.cube", cube()))
        assertEquals(listOf("GRADE.cube", "LUT.v2.cube", "noextension.cube", "notes.cube"), stored())
    }

    @Test fun namesAreSortedAlphabeticallyAndNumbersByValue() {
        val store = LutStore(cache())
        for (file in listOf("beta.cube", "Alpha.cube", "look10.cube", "look2.cube", "Гамма.cube", "alpha2.cube")) store.add(file, cube())
        assertEquals(listOf("Alpha", "alpha2", "beta", "look2", "look10", "Гамма"), store.names)
    }

    @Test fun nameOrderIsTotalAndStable() {
        val names = listOf("a1", "a01", "A1", "a2", "a10", "a", "A", "b", "B2", "b10", "б", "Б", "x007", "x7", "x08", "x8")
        val order = Comparator<String> { a, b -> LutStore.compareNames(a, b) }
        assertEquals(names.sortedWith(order), names.shuffled(java.util.Random(7)).sortedWith(order))
        // антисимметрия и транзитивность на всех тройках
        for (a in names) for (b in names) {
            assertEquals("$a $b", -Integer.signum(LutStore.compareNames(b, a)), Integer.signum(LutStore.compareNames(a, b)))
            for (c in names) {
                if (LutStore.compareNames(a, b) <= 0 && LutStore.compareNames(b, c) <= 0) {
                    assertTrue("$a $b $c", LutStore.compareNames(a, c) <= 0)
                }
            }
        }
        assertEquals(0, LutStore.compareNames("same", "same"))
    }

    @Test fun nameWithoutBaseGetsAUsableOne() {
        // «.cube» без имени — скрытый файл: в списке его не было бы, хотя добавление «удалось»
        val store = LutStore(cache())
        val name = store.add(".cube", cube())
        assertTrue(name, name.isNotEmpty() && !name.startsWith("."))
        assertEquals("cube", name)
        // совсем пустое имя — «LUT»
        assertEquals("LUT", store.add("   .   ", cube()))
        assertEquals(listOf("cube", "LUT"), store.names)
    }

    @Test fun nameIsCleanedOfPathPartsDotsAndSpaces() {
        val store = LutStore(cache())
        // имя из провайдера документов не должно уводить за папку
        assertEquals("grade", store.add("../../grade.cube", cube()))
        assertEquals("deep", store.add("a/b\\deep.cube", cube()))
        assertEquals("hidden", store.add("..hidden.cube", cube()))
        assertEquals("spaced", store.add("  spaced  .cube", cube()))
        assertEquals("nul", store.add("n\u0000ul.cube", cube()))
        assertEquals(listOf("deep.cube", "grade.cube", "hidden.cube", "nul.cube", "spaced.cube"), stored())
        assertFalse(File(temp.root, "grade.cube").exists())
    }

    @Test fun baseNameRules() {
        assertEquals("a", LutStore.baseName("a.cube"))
        assertEquals("a.b", LutStore.baseName("a.b.cube"))
        assertEquals("LUT", LutStore.baseName(""))
        assertEquals("LUT", LutStore.baseName("."))
        assertEquals("LUT", LutStore.baseName("..."))
        assertEquals("cube", LutStore.baseName(".cube"))
        assertEquals("cube", LutStore.baseName(".cube.cube"))
        assertEquals("a b", LutStore.baseName(" a b .cube"))
        assertEquals("Тёплый закат", LutStore.baseName("Тёплый закат.CUBE"))
    }

    // не больше двенадцати

    @Test fun thirteenthFileIsRefusedWithTheLimitText() {
        val store = LutStore(cache())
        for (i in 1..LutStore.LIMIT) store.add("lut$i.cube", cube())
        assertEquals(12, store.names.size)
        val failure = refusal(store.importFile("one-more.cube", source(bytes(cube()))))
        assertEquals(LutStore.Failure.Limit, failure)
        assertEquals("Своих LUT не больше 12 — удалите ненужный", failure.message)
        assertEquals(12, store.names.size)
        assertFalse("one-more" in store.names)
        assertEquals(12, stored().size)
    }

    @Test fun atTheLimitTheSameNameStillReplaces() {
        val cache = cache()
        val store = LutStore(cache)
        for (i in 1..12) store.add("lut$i.cube", cube())
        store.add("lut7.cube", cube(inverted = true))
        assertEquals(12, store.names.size)
        assertEquals(onPicture(cube(inverted = true)), cache.file("lut7"))
        // и то же имя другим регистром — тоже замена, а не тринадцатый
        store.add("LUT7.cube", cube())
        assertEquals(12, store.names.size)
        assertTrue("LUT7" in store.names && "lut7" !in store.names)
    }

    @Test fun deletingMakesRoomForAnotherFile() {
        val store = LutStore(cache())
        for (i in 1..12) store.add("lut$i.cube", cube())
        store.delete("lut3")
        assertEquals(11, store.names.size)
        assertFalse("lut3" in store.names)
        assertEquals("fresh", store.add("fresh.cube", cube()))
        assertEquals(12, store.names.size)
    }

    // плохие файлы

    @Test fun brokenFilesAreRefusedWithTheParserText() {
        val store = LutStore(cache())
        val oneD = refusal(store.importFile("one-d.cube", source(bytes("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n"))))
        assertEquals(LutStore.Failure.Cube(CubeFile.Failure.NotThreeD), oneD)
        assertEquals("Нужен 3D LUT в формате .cube", oneD.message)
        // не хватает строк: сообщение — то же, что у разбора
        val short = refusal(store.importFile("short.cube", source(bytes(cube().trimEnd().lines().dropLast(1).joinToString("\n")))))
        assertEquals(LutStore.Failure.Cube(CubeFile.Failure.Count(found = 7, expected = 8)), short)
        assertEquals(CubeFile.message(CubeFile.Failure.Count(found = 7, expected = 8)), short.message)
        assertEquals("В файле 7 строк данных, а нужно 8", short.message)
        // ничего не скопировано, и папка не создана ради отказа
        assertTrue(store.names.isEmpty())
        assertFalse(folder.exists())
    }

    @Test fun anEmptyOrForeignFileIsNotA3DLut() {
        val store = LutStore(cache())
        assertEquals(LutStore.Failure.Cube(CubeFile.Failure.NotThreeD), refusal(store.importFile("empty.cube", source(ByteArray(0)))))
        assertEquals(LutStore.Failure.Cube(CubeFile.Failure.NotThreeD),
            refusal(store.importFile("photo.jpg", source(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)))))
        assertTrue(store.names.isEmpty())
    }

    @Test fun anUnreadableFileIsRefusedWithItsOwnText() {
        val store = LutStore(cache())
        // провайдер документов не открыл поток
        assertEquals(LutStore.Failure.Unreadable, refusal(store.importFile("ghost.cube") { null }))
        // открыть не получилось с исключением
        assertEquals(LutStore.Failure.Unreadable, refusal(store.importFile("ghost.cube") { throw IOException("нет доступа") }))
        assertEquals(LutStore.Failure.Unreadable, refusal(store.importFile("ghost.cube") { throw SecurityException("нет прав") }))
        // оборвалось на середине чтения
        val broken = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 10) 'x'.code else throw IOException("связь с облаком оборвалась")
        }
        assertEquals(LutStore.Failure.Unreadable, refusal(store.importFile("cloud.cube") { broken }))
        assertEquals("Не удалось прочитать файл", LutStore.Failure.Unreadable.message)
        assertTrue(store.names.isEmpty())
        assertFalse(folder.exists())
    }

    @Test fun aHugeFileIsRefusedBeforeItIsReadToTheEnd() {
        val store = LutStore(cache(), maxFileBytes = 1000)
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                java.util.Arrays.fill(b, off, off + len, 0)
                return len
            }
        }
        val failure = refusal(store.importFile("big.cube") { endless })
        assertEquals(LutStore.Failure.TooBig, failure)
        assertEquals("Файл слишком большой для LUT", failure.message)
        // читать до конца бесконечный поток не пришлось: остановились сразу за пределом (с запасом на один буфер)
        assertTrue("прочитано $served", served in 1001..(1000L + 64 * 1024))
        assertTrue(store.names.isEmpty())
        // ровно предел — ещё файл (дальше разбор скажет, куб ли это)
        val exact = bytes(cube()) + ByteArray(1000 - bytes(cube()).size) { ' '.code.toByte() }
        assertEquals(1000, exact.size)
        assertEquals("exact", added(store.importFile("exact.cube", source(exact))))
    }

    /** Поток, который помнит, закрыли ли его: провайдер документов держит дескриптор, пока не закроют. */
    private class Tracked(private val inner: InputStream) : InputStream() {
        var closed = false
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {
            closed = true
            inner.close()
        }
    }

    @Test fun theStreamIsClosedWhateverHappens() {
        val store = LutStore(cache(), maxFileBytes = 1000)
        // прочитан до конца
        val whole = Tracked(ByteArrayInputStream(bytes(cube())))
        added(store.importFile("whole.cube") { whole })
        assertTrue(whole.closed)
        // слишком большой: бросили на пределе
        val big = Tracked(ByteArrayInputStream(ByteArray(5000)))
        assertEquals(LutStore.Failure.TooBig, refusal(store.importFile("big.cube") { big }))
        assertTrue(big.closed)
        // оборвался на чтении
        val broken = Tracked(object : InputStream() {
            override fun read(): Int = throw IOException("оборвалось")
        })
        assertEquals(LutStore.Failure.Unreadable, refusal(store.importFile("broken.cube") { broken }))
        assertTrue(broken.closed)
        // не 3D LUT: прочитан, разбор отказал
        val foreign = Tracked(ByteArrayInputStream(bytes("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n")))
        refusal(store.importFile("foreign.cube") { foreign })
        assertTrue(foreign.closed)
    }

    @Test fun anOutOfMemoryWhileReadingMeansTheFileIsTooBig() {
        val store = LutStore(cache())
        val failure = refusal(store.importFile("heavy.cube") { object : InputStream() { override fun read(): Int = throw OutOfMemoryError() } })
        assertEquals(LutStore.Failure.TooBig, failure)
    }

    @Test fun parseErrorComesBeforeTheLimit() {
        // полная папка и плохой файл: сказать надо про файл — удалённое место ему не поможет
        val store = LutStore(cache())
        for (i in 1..12) store.add("lut$i.cube", cube())
        val failure = refusal(store.importFile("bad.cube", source(bytes("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n"))))
        assertEquals(LutStore.Failure.Cube(CubeFile.Failure.NotThreeD), failure)
    }

    @Test fun latinOneTitleDoesNotBreakTheImport() {
        // TITLE в Latin-1 (байт 0xE9 — не UTF-8): название пропускается, остальное читается
        val cache = cache()
        val store = LutStore(cache)
        val data = "TITLE \"Caf".toByteArray() + byteArrayOf(0xE9.toByte()) + "\"\n".toByteArray() + bytes(cube())
        assertEquals("latin", added(store.importFile("latin.cube", source(data))))
        assertEquals(onPicture(cube()), cache.file("latin"))
        // копия — те же байты, а не пересохранённый текст
        assertTrue(data.contentEquals(File(folder, "latin.cube").readBytes()))
    }

    @Test fun windowsLineEndingsAndBomAreFine() {
        val cache = cache()
        val store = LutStore(cache)
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(cube().replace("\n", "\r\n"))
        assertEquals("crlf", added(store.importFile("crlf.cube", source(data))))
        assertEquals(onPicture(cube()), cache.file("crlf"))
    }

    // удаление и список

    @Test fun deleteRemovesTheFileAndForgetsIt() {
        val cache = cache()
        val store = LutStore(cache)
        store.add("a.cube", cube())
        store.add("b.cube", cube(inverted = true))
        assertNotNull(cache.file("a"))
        store.delete("a")
        assertEquals(listOf("b"), store.names)
        assertNull(cache.file("a"))
        assertEquals(listOf("b.cube"), stored())
        // нет такого — ничего не происходит
        store.delete("a")
        store.delete("")
        assertEquals(listOf("b"), store.names)
    }

    @Test fun nameCannotLeadOutOfTheFolder() {
        val store = LutStore(cache())
        store.add("inside.cube", cube())
        // файл рядом с папкой — до него не дотянуться ни удалением, ни добавлением
        val outside = File(temp.root, "outside.cube").also { it.writeText(cube()) }
        store.delete("../outside")
        assertTrue(outside.exists())
        store.delete("..")
        store.delete(".")
        store.delete("sub/inside")
        assertEquals(listOf("inside"), store.names)
        assertEquals(listOf("inside.cube"), stored())
    }

    @Test fun onlyCubeFilesAreListed() {
        val store = LutStore(cache())
        folder.mkdirs()
        File(folder, "good.cube").writeText(cube())
        File(folder, "notes.txt").writeText("x")
        File(folder, ".hidden.cube").writeText(cube())
        File(folder, ".import-1234.tmp").writeText(cube())
        File(folder, "folder.cube").mkdirs()
        File(folder, ".cube").writeText(cube())
        assertEquals(listOf("good"), store.names)
    }

    @Test fun aBrokenFileLeftInTheFolderIsListedButLoadsAsNothing() {
        // файл положили в папку руками и испортили: он в списке (выбрать можно), а куба из него нет — картинка не меняется
        val cache = cache()
        val store = LutStore(cache)
        folder.mkdirs()
        File(folder, "junk.cube").writeText("not a cube")
        assertEquals(listOf("junk"), store.names)
        assertNull(cache.file("junk"))
        runBackground()
        assertNull(cache.file("junk"))
        assertTrue(notes.toString(), notes.any { "junk" in it })
    }

    // временные файлы от оборванной записи

    @Test fun leftoversOfAKilledWriteAreSweptWhenTheStoreIsCreated() {
        // процесс убили между записью во временный файл и переименованием: `.import-*.tmp` остался навсегда
        folder.mkdirs()
        File(folder, ".import-6f1c2d3e-aaaa-bbbb-cccc-0123456789ab.tmp").writeBytes(ByteArray(5000))
        File(folder, ".import-halfwritten.tmp").writeText("LUT_3D_SIZE 2\n0 0 0\n")
        File(folder, "keep.cube").writeText(cube())
        val store = LutStore(cache())
        assertEquals(listOf("keep.cube"), stored())
        assertEquals(listOf("keep"), store.names)
    }

    @Test fun onlyImportLeftoversAreSwept() {
        // чужое не трогаем: скрытый .cube, другие .tmp, папка с таким именем, файл с другим суффиксом
        folder.mkdirs()
        File(folder, ".import-1.tmp").writeText("x")
        File(folder, ".hidden.cube").writeText(cube())
        File(folder, "notes.tmp").writeText("x")
        File(folder, ".import-2.txt").writeText("x")
        File(folder, ".other-3.tmp").writeText("x")
        File(folder, ".import-dir.tmp").mkdirs()
        File(folder, "look.cube").writeText(cube())
        LutStore(cache())
        assertEquals(listOf(".hidden.cube", ".import-2.txt", ".import-dir.tmp", ".other-3.tmp", "look.cube", "notes.tmp"), stored())
    }

    @Test fun creatingTheStoreDoesNotMakeTheFolder() {
        LutStore(cache())
        assertFalse(folder.exists())
    }

    @Test fun theStoreStillWorksRightAfterASweep() {
        folder.mkdirs()
        File(folder, ".import-old.tmp").writeText("half")
        val store = LutStore(cache())
        assertEquals("fresh", store.add("fresh.cube", cube()))
        assertEquals(listOf("fresh.cube"), stored())
    }

    // порядок записи: прежний файл цел, пока новый не лёг на место

    @Test fun failedWriteLeavesTheOldFileAlone() {
        // замена под другим написанием: новое не записалось — прежний файл остаётся как был, а не пропадает до записи
        LutStore(cache()).add("Look.cube", cube(inverted = false))
        val cache = cache()
        val failing = LutStore(cache, write = { _, _ -> throw IOException("нет места") })
        val failure = refusal(failing.importFile("look.cube", source(bytes(cube(inverted = true)))))
        assertEquals(LutStore.Failure.Unsaved, failure)
        assertEquals("Не удалось сохранить LUT в папке программы", failure.message)
        assertEquals(listOf("Look"), failing.names)
        assertEquals(listOf("Look.cube"), stored())
        assertEquals(cube(inverted = false), File(folder, "Look.cube").readText())
    }

    @Test fun failedWriteOfTheSameSpellingLeavesTheOldFileAlone() {
        LutStore(cache()).add("grade.cube", cube(inverted = false))
        val failing = LutStore(cache(), write = { _, _ -> throw IOException("нет места") })
        assertEquals(LutStore.Failure.Unsaved, refusal(failing.importFile("grade.cube", source(bytes(cube(inverted = true))))))
        assertEquals(cube(inverted = false), File(folder, "grade.cube").readText())
        assertEquals(listOf("grade.cube"), stored())
    }

    @Test fun failedWriteWithAPartialFileLeavesNothingBehind() {
        // запись оборвалась на середине: недописанный файл не остаётся ни в списке, ни под скрытым именем
        val half = LutStore(cache(), write = { data, file ->
            file.writeBytes(data.copyOf(10))
            throw IOException("нет места")
        })
        assertEquals(LutStore.Failure.Unsaved, refusal(half.importFile("fresh.cube", source(bytes(cube())))))
        assertTrue(half.names.isEmpty())
        assertEquals(emptyList<String>(), stored())
    }

    @Test fun anUnmakeableFolderMeansUnsaved() {
        // на месте папки — файл: создать её нельзя
        val blocker = File(temp.root, "luts").also { it.writeText("not a folder") }
        val store = LutStore(LutCache(blocker, queue::addLast))
        assertEquals(LutStore.Failure.Unsaved, refusal(store.importFile("a.cube", source(bytes(cube())))))
        assertTrue(blocker.isFile)
    }

    @Test fun replaceWithAnotherSpellingLeavesOneFileAndNothingTemporary() {
        val store = LutStore(cache())
        store.add("Look.cube", cube(inverted = false))
        store.add("LOOK.cube", cube(inverted = true))
        assertEquals(listOf("LOOK"), store.names)
        assertEquals(listOf("LOOK.cube"), stored())
        assertEquals(cube(inverted = true), File(folder, "LOOK.cube").readText())
    }

    @Test fun nothingTemporaryIsLeftAfterASuccessfulImport() {
        val store = LutStore(cache())
        store.add("a.cube", cube())
        store.add("a.cube", cube(inverted = true))
        assertEquals(listOf("a.cube"), stored())
    }

    // несколько потоков

    @Test fun importsFromSeveralThreadsNeverGoOverTheLimit() {
        val store = LutStore(cache())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val added = AtomicInteger()
        val refused = AtomicInteger()
        val tasks = (1..40).map { i ->
            pool.submit {
                start.await()
                when (store.importFile("many$i.cube", source(bytes(cube())))) {
                    is LutStore.Outcome.Added -> added.incrementAndGet()
                    is LutStore.Outcome.Refused -> refused.incrementAndGet()
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(12, added.get())
        assertEquals(28, refused.get())
        assertEquals(12, store.names.size)
        assertEquals(12, stored().size)
    }

    @Test fun deletingDoesNotWaitForASlowRead() {
        // файл из облака качается долго: «Удалить» и список на главном потоке не ждут, пока он дочитается
        val store = LutStore(cache())
        store.add("keep.cube", cube())
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = ByteArrayInputStream(bytes(cube()))
        val slow = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                release.await()
                return data.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reading.countDown()
                release.await()
                return data.read(b, off, len)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val import = pool.submit<LutStore.Outcome> { store.importFile("slow.cube") { slow } }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val meanwhile = pool.submit<List<String>> {
                store.delete("keep")
                store.names
            }
            assertEquals(emptyList<String>(), meanwhile.get(10, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(LutStore.Outcome.Added("slow"), import.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        assertEquals(listOf("slow"), store.names)
    }

    @Test fun anImportedFileIsNotReadAgainByThePicture() {
        // добавленный файл уже в кэше: кадровому циклу не нужно ни читать его, ни разбирать второй раз
        val reads = AtomicInteger()
        val cache = cache(read = { reads.incrementAndGet(); it.readBytes() })
        val store = LutStore(cache)
        store.add("x.cube", cube())
        val first = cache.file("x")
        assertNotNull(first)
        repeat(3) { assertSame(first, cache.file("x")) }
        runBackground()
        assertEquals(0, reads.get())
        // файл изменили снаружи — тогда читается, как и раньше
        File(folder, "x.cube").writeText(cube(inverted = true) + "# длиннее\n")
        assertNull(cache.file("x"))
        runBackground()
        assertEquals(1, reads.get())
        assertEquals(onPicture(cube(inverted = true)), cache.file("x"))
    }
}
