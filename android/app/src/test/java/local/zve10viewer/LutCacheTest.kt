package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.CameraProfile
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.LutCache
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorAssist.LutMode
import local.zve10viewer.monitor.PicturePath
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Кубы LUT на экране: один экземпляр на LUT (таблица прохода живёт в экземпляре), встроенные — по виду, свои — по имени и
 * дате файла из папки программы, «Авто» — по профилю главной камеры.
 */
class LutCacheTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    @get:Rule val folder = TemporaryFolder()

    /** Фоновые задания кэша складываются сюда и выполняются, когда тест скажет. */
    private class Queue {
        val tasks = ArrayDeque<Runnable>()
        fun post(task: Runnable) {
            tasks.addLast(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val queue = Queue()
    private val notes = ArrayList<String>()

    private fun cache(limit: Long = LutCache.MAX_FILE_BYTES) = LutCache(lutsFolder(), queue::post, { notes += it }, limit)

    private fun lutsFolder(): File = File(folder.root, "luts").also { it.mkdirs() }

    private val identity2 = """
        # тождественный куб 2×2×2
        TITLE "тождество"
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    private val inverted2 = """
        LUT_3D_SIZE 2
        1 1 1
        0 1 1
        1 0 1
        0 0 1
        1 1 0
        0 1 0
        1 0 0
        0 0 0
    """.trimIndent()

    private fun write(name: String, text: String, stamp: Long? = null): File {
        val file = File(lutsFolder(), "$name.cube")
        file.writeText(text)
        if (stamp != null) assertTrue(file.setLastModified(stamp))
        return file
    }

    private fun assist(mode: LutMode, file: String? = null) = MonitorAssist(lut = mode, lutFile = file)

    // встроенные

    @Test fun builtinCubeIsMadeOncePerKind() {
        val cache = cache()
        val first = cache.builtin(BuiltinLut.slog3cine)
        assertEquals(33, first.size)
        assertSame(first, cache.builtin(BuiltinLut.slog3cine))
        for (kind in BuiltinLut.entries) {
            val cube = cache.builtin(kind)
            assertSame(kind.name, cube, cache.builtin(kind))
            if (kind != BuiltinLut.slog3cine) assertNotSame(kind.name, first, cube)
        }
    }

    @Test fun buildingABuiltinCubeIsWrittenToTheJournalOnce() {
        val cache = cache()
        repeat(3) { cache.builtin(BuiltinLut.hlg) }
        assertEquals(notes.toString(), 1, notes.count { "HLG" in it && "33³" in it && "мс" in it })
        cache.builtin(BuiltinLut.slog2)
        assertEquals(2, notes.size)
    }

    @Test fun builtinCubeIsTheExactPathOfItsKind() {
        val cube = cache().builtin(BuiltinLut.hlg)
        assertEquals(BuiltinLut.hlg.make(), cube)
    }

    @Test fun builtinCubeIsTheSameInstanceForAllThreads() {
        val cache = cache()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 8).map { pool.submit<ColorLut> { start.await(); cache.builtin(BuiltinLut.slog2) } }
            start.countDown()
            val cubes = results.map { it.get(30, TimeUnit.SECONDS) }
            for (cube in cubes) assertSame(cubes[0], cube)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun warmingMakesTheCubeInTheBackgroundAndOnlyOnce() {
        val cache = cache()
        cache.warm(BuiltinLut.slog3)
        cache.warm(BuiltinLut.slog3)
        assertEquals("второй раз то же задание не ставится", 1, queue.tasks.size)
        queue.runAll()
        val cube = cache.builtin(BuiltinLut.slog3)
        cache.warm(BuiltinLut.slog3)
        assertEquals("готовый куб греть не нужно", 0, queue.tasks.size)
        assertSame(cube, cache.builtin(BuiltinLut.slog3))
    }

    @Test fun warmingDoesNotWaitForABuildInProgressAndNobodyBuildsTwice() {
        // главный поток зовёт warm, пока кадровый цикл собирает тот же куб: не ждёт, а второй сборки нет
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val made = AtomicInteger()
        val cache = LutCache(lutsFolder(), queue::post, { notes += it }, 32L shl 20) {
            made.incrementAndGet()
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            ColorLut.identity(2)
        }
        val builder = thread(name = "frame loop") { cache.builtin(BuiltinLut.slog3) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val warmed = CountDownLatch(1)
        thread(name = "main") {
            cache.warm(BuiltinLut.slog3)
            warmed.countDown()
        }
        assertTrue("warm ждёт чужую сборку", warmed.await(5, TimeUnit.SECONDS))
        val second = AtomicReference<ColorLut>()
        val waiter = thread(name = "second user") { second.set(cache.builtin(BuiltinLut.slog3)) }
        // второй должен упереться в чужую сборку, а не прийти к готовому кубу
        val deadline = System.nanoTime() + 5_000_000_000L
        while (waiter.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(Thread.State.BLOCKED, waiter.state)
        release.countDown()
        builder.join(10_000)
        waiter.join(10_000)
        assertEquals("куб сделан один раз", 1, made.get())
        assertSame(cache.builtin(BuiltinLut.slog3), second.get())
        queue.runAll()
        assertEquals(1, made.get())
        assertEquals("в журнале одна строка о сборке", 1, notes.size)
    }

    @Test fun cubeCalledDuringWarmingIsTheCubeWarmingMakes() {
        val cache = cache()
        cache.warm(BuiltinLut.slog3)
        val early = cache.builtin(BuiltinLut.slog3)
        queue.runAll()
        assertSame(early, cache.builtin(BuiltinLut.slog3))
    }

    // выбор: что на экране

    @Test fun autoFollowsThePictureProfileOfTheMainCamera() {
        val cache = cache()
        val auto = assist(LutMode.auto)
        assertNull("профиль 0 — не лог", cache.cube(auto, CameraProfile(pictureProfile = 0)))
        assertNull(cache.cube(auto, CameraProfile(pictureProfile = 1)))
        assertNull("камеры нет", cache.cube(auto, CameraProfile.none))
        assertSame(cache.builtin(BuiltinLut.slog2), cache.cube(auto, CameraProfile(pictureProfile = 7)))
        assertSame(cache.builtin(BuiltinLut.slog3cine), cache.cube(auto, CameraProfile(pictureProfile = 8)))
        assertSame(cache.builtin(BuiltinLut.slog3), cache.cube(auto, CameraProfile(pictureProfile = 9)))
        assertSame(cache.builtin(BuiltinLut.hlg), cache.cube(auto, CameraProfile(pictureProfile = 10)))
    }

    @Test fun autoChangesWithTheProfileBetweenFramesAndKeepsOneCubePerLut() {
        val cache = cache()
        val auto = assist(LutMode.auto)
        val seen = listOf(0L, 8L, 8L, 0L, 10L, 8L).map { cache.cube(auto, CameraProfile(pictureProfile = it)) }
        assertNull(seen[0])
        assertNotNull(seen[1])
        assertSame(seen[1], seen[2])
        assertNull(seen[3])
        assertNotSame(seen[1], seen[4])
        assertSame("после возврата профиля — тот же куб, таблицу не строить заново", seen[1], seen[5])
    }

    @Test fun cameraThatNamesItsGammaDecidesByTheGamma() {
        val cache = cache()
        val auto = assist(LutMode.auto)
        // S-Log3 (0x0303) с S-Gamut3 (0x0009) — свой LUT, с Cine (0x0008) — другой; PP-номер уже не смотрят
        assertSame(cache.builtin(BuiltinLut.slog3), cache.cube(auto, CameraProfile(pictureProfile = 1, gamma = 0x0303, colorMode = 0x0009)))
        assertSame(cache.builtin(BuiltinLut.slog3cine), cache.cube(auto, CameraProfile(pictureProfile = 1, gamma = 0x0303, colorMode = 0x0008)))
        assertSame(cache.builtin(BuiltinLut.hlg), cache.cube(auto, CameraProfile(pictureProfile = 1, gamma = 0x0402)))
        assertNull(cache.cube(auto, CameraProfile(pictureProfile = 8, gamma = 0x0201)))
    }

    @Test fun manualChoiceStaysWhateverTheProfileIs() {
        val cache = cache()
        for (profile in listOf(CameraProfile.none, CameraProfile(pictureProfile = 0), CameraProfile(pictureProfile = 8))) {
            assertSame(cache.builtin(BuiltinLut.slog2), cache.cube(assist(LutMode.slog2), profile))
            assertSame(cache.builtin(BuiltinLut.hlg), cache.cube(assist(LutMode.hlg), profile))
            assertNull(cache.cube(assist(LutMode.off), profile))
        }
    }

    @Test fun resolvedNamesTheBuiltinOnTheScreen() {
        val cache = cache()
        assertEquals(BuiltinLut.slog3cine, cache.resolved(assist(LutMode.auto), CameraProfile(pictureProfile = 8)))
        assertNull(cache.resolved(assist(LutMode.auto), CameraProfile(pictureProfile = 0)))
        assertNull(cache.resolved(assist(LutMode.file, "x"), CameraProfile(pictureProfile = 8)))
        assertNull(cache.resolved(assist(LutMode.off), CameraProfile(pictureProfile = 8)))
    }

    @Test fun autoWithoutALogProfileMeansNoPassAtAll() {
        // 90 % частоты кадров держатся тем, что ни куба, ни поворота — и кадр не трогают
        val cache = cache()
        val auto = assist(LutMode.auto)
        assertFalse(PicturePath.isNeeded(FrameOrientation.off, cache.cube(auto, CameraProfile(pictureProfile = 0))))
        assertTrue(PicturePath.isNeeded(FrameOrientation.off, cache.cube(auto, CameraProfile(pictureProfile = 8))))
        assertTrue(PicturePath.isNeeded(FrameOrientation.mirror, cache.cube(auto, CameraProfile(pictureProfile = 0))))
    }

    // свои файлы

    @Test fun ownFileIsReadInTheBackgroundAndNeverOnTheCallersThread() {
        write("look", identity2)
        val cache = cache()
        assertNull("в первый раз куба в памяти нет", cache.file("look"))
        assertEquals(1, queue.tasks.size)
        assertNull(cache.file("look"))
        assertEquals("читается один раз, а не на каждый кадр", 1, queue.tasks.size)
        queue.runAll()
        val cube = cache.file("look")
        assertNotNull(cube)
        assertEquals(2, cube!!.size)
        assertSame(cube, cache.file("look"))
        assertEquals(0, queue.tasks.size)
    }

    @Test fun fileModeShowsTheLoadedCube() {
        write("look", identity2)
        val cache = cache()
        val mode = assist(LutMode.file, "look")
        assertNull(cache.cube(mode, CameraProfile.none))
        queue.runAll()
        assertSame(cache.file("look"), cache.cube(mode, CameraProfile.none))
        assertNotNull(cache.cube(mode, CameraProfile.none))
    }

    @Test fun fileModeWithoutAFileChosenGivesNothing() {
        val cache = cache()
        assertNull(cache.cube(assist(LutMode.file, null), CameraProfile.none))
        assertEquals(0, queue.tasks.size)
    }

    @Test fun missingFileGivesNothingAndReadsNothing() {
        val cache = cache()
        assertNull(cache.file("нет такого"))
        assertEquals(0, queue.tasks.size)
        // файл положили руками — со следующего вызова он читается
        write("нет такого", identity2)
        assertNull(cache.file("нет такого"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        assertNotNull(cache.file("нет такого"))
    }

    @Test fun deletedFileGoesBackToNothing() {
        val file = write("look", identity2)
        val cache = cache()
        cache.file("look")
        queue.runAll()
        assertNotNull(cache.file("look"))
        assertTrue(file.delete())
        assertNull(cache.file("look"))
        assertEquals(0, queue.tasks.size)
    }

    @Test fun changedFileIsReadAgainAndGivesAFreshCube() {
        val file = write("look", identity2, stamp = 1_700_000_000_000L)
        val cache = cache()
        cache.file("look")
        queue.runAll()
        val old = cache.file("look")
        assertNotNull(old)
        // тот же файл под тем же именем, другое содержимое и дата
        file.writeText(inverted2)
        assertTrue(file.setLastModified(1_700_000_005_000L))
        assertNull("пока новое не разобрано, старый куб не отдаётся", cache.file("look"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        val fresh = cache.file("look")
        assertNotNull(fresh)
        assertNotSame(old, fresh)
        assertEquals(1f, fresh!!.values[0], 0f)
    }

    @Test fun fileWithTheSameDateButAnotherLengthIsAlsoNew() {
        val file = write("look", identity2, stamp = 1_700_000_000_000L)
        val cache = cache()
        cache.file("look")
        queue.runAll()
        val old = cache.file("look")
        file.writeText(identity2 + "\n# и ещё строка\n")
        assertTrue(file.setLastModified(1_700_000_000_000L))
        assertNull(cache.file("look"))
        queue.runAll()
        assertNotSame(old, cache.file("look"))
    }

    @Test fun fileWithTheSameLengthButAnotherDateIsAlsoNew() {
        val file = write("look", identity2, stamp = 1_700_000_000_000L)
        val cache = cache()
        cache.file("look")
        queue.runAll()
        val old = cache.file("look")
        // в строках данных 0 и 1 меняются местами: другой куб ровно той же длины
        val swapped = identity2.lines().joinToString("\n") { line ->
            if (line.isNotEmpty() && line[0].isDigit()) line.map { if (it == '0') '1' else if (it == '1') '0' else it }.joinToString("") else line
        }
        assertEquals(identity2.toByteArray().size, swapped.toByteArray().size)
        file.writeText(swapped)
        assertTrue(file.setLastModified(1_700_000_007_000L))
        assertNull(cache.file("look"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        val fresh = cache.file("look")
        assertNotSame(old, fresh)
        assertEquals(1f, fresh!!.values[0], 0f)
    }

    @Test fun deletedFileGivesItsPlaceBack() {
        // удалённый файл не занимает одно из трёх мест, даже если к его имени только что обращались
        for (n in 1..4) write("l$n", identity2)
        val cache = cache()
        for (n in 1..3) {
            cache.file("l$n")
            queue.runAll()
        }
        assertTrue(File(lutsFolder(), "l1.cube").delete())
        assertNull(cache.file("l1"))
        cache.file("l4")
        queue.runAll()
        for (n in 2..4) assertNotNull("l$n", cache.file("l$n"))
        assertEquals(0, queue.tasks.size)
    }

    @Test fun brokenFileIsTriedOncePerChangeAndMessageGoesToTheJournal() {
        val file = write("bad", "LUT_1D_SIZE 16\n0 0 0\n", stamp = 1_700_000_000_000L)
        val cache = cache()
        assertNull(cache.file("bad"))
        queue.runAll()
        assertNull(cache.file("bad"))
        repeat(5) { assertNull(cache.file("bad")) }
        assertEquals("испорченный файл заново не разбирается на каждый кадр", 0, queue.tasks.size)
        assertTrue("в журнал — что с файлом: $notes", notes.any { "bad" in it && "3D" in it })
        // починили — читается снова
        file.writeText(identity2)
        assertTrue(file.setLastModified(1_700_000_009_000L))
        assertNull(cache.file("bad"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        assertNotNull(cache.file("bad"))
    }

    @Test fun fileWithoutEnoughRowsIsReportedWithItsCounts() {
        write("short", "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n")
        val cache = cache()
        cache.file("short")
        queue.runAll()
        assertNull(cache.file("short"))
        assertTrue("$notes", notes.any { "short" in it && "8" in it })
    }

    @Test fun latinOneTitleDoesNotBreakReading() {
        // TITLE в Latin-1 (байт 0xE9) — не UTF-8: читается с заменой, а не отказом
        val head = "TITLE \"caf".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xE9.toByte()) + "\"\nLUT_3D_SIZE 2\n".toByteArray(Charsets.ISO_8859_1)
        val rows = identity2.lines().filter { it.isNotEmpty() && it[0].isDigit() }.joinToString("\n", postfix = "\n")
        File(lutsFolder(), "latin.cube").writeBytes(head + rows.toByteArray())
        val cache = cache()
        cache.file("latin")
        queue.runAll()
        assertNotNull("$notes", cache.file("latin"))
    }

    @Test fun byteOrderMarkAndWindowsLineEndsAreFine() {
        File(lutsFolder(), "win.cube").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + identity2.replace("\n", "\r\n").toByteArray())
        val cache = cache()
        cache.file("win")
        queue.runAll()
        assertNotNull("$notes", cache.file("win"))
    }

    @Test fun cubeWithAReversedDomainIsAcceptedLikeTheParserAccepts() {
        write("flip", "LUT_3D_SIZE 2\nDOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\n" + identity2.lines().filter { it.isNotEmpty() && it[0].isDigit() }.joinToString("\n"))
        val cache = cache()
        cache.file("flip")
        queue.runAll()
        val cube = cache.file("flip")
        assertNotNull("$notes", cube)
        assertEquals(1f, cube!!.domainMin[0], 0f)
        assertEquals(0f, cube.domainMax[0], 0f)
    }

    @Test fun tooHeavyFileIsRefusedWithoutReadingIt() {
        write("huge", identity2 + "\n" + "# ".repeat(600))
        val reads = AtomicInteger()
        val cache = LutCache(lutsFolder(), queue::post, { notes += it }, 200, { file -> reads.incrementAndGet(); file.readBytes() })
        cache.file("huge")
        queue.runAll()
        assertNull(cache.file("huge"))
        assertTrue("$notes", notes.any { "huge" in it && "большой" in it })
        assertEquals("тяжёлый файл не читается", 0, reads.get())
        assertEquals("и заново не пробуется, пока он не изменится", 0, queue.tasks.size)
    }

    @Test fun fileOfExactlyTheCapIsReadAndOneByteMoreIsNot() {
        val cap = 4096L
        val exact = write("exact", identity2 + "\n" + "#".repeat((cap - identity2.toByteArray().size - 1).toInt()))
        assertEquals(cap, exact.length())
        val over = write("over", identity2 + "\n" + "#".repeat((cap - identity2.toByteArray().size).toInt()))
        assertEquals(cap + 1, over.length())
        val cache = cache(limit = cap)
        cache.file("exact")
        cache.file("over")
        queue.runAll()
        assertNotNull("ровно по пределу читается", cache.file("exact"))
        assertNull("на байт больше — нет", cache.file("over"))
    }

    @Test fun outOfMemoryWhileReadingIsAFileThatIsTooBigAndIsNotRetried() {
        write("big", identity2, stamp = 1_700_000_000_000L)
        val reads = AtomicInteger()
        val cache = LutCache(lutsFolder(), queue::post, { notes += it }, LutCache.MAX_FILE_BYTES, { reads.incrementAndGet(); throw OutOfMemoryError("Failed to allocate") })
        assertNull(cache.file("big"))
        queue.runAll()
        assertNull(cache.file("big"))
        assertTrue("в журнал: что файл слишком большой — $notes", notes.any { "big" in it && "большой" in it })
        repeat(5) { assertNull(cache.file("big")) }
        assertEquals("заново не читается, пока файл не изменился", 1, reads.get())
        assertEquals(0, queue.tasks.size)
    }

    @Test fun outOfMemoryThenChangedFileIsReadAgainBySameCache() {
        val file = write("big", identity2, stamp = 1_700_000_000_000L)
        var memory = false
        val cache = LutCache(lutsFolder(), queue::post, { notes += it }, LutCache.MAX_FILE_BYTES, { f -> if (memory) f.readBytes() else throw OutOfMemoryError() })
        cache.file("big")
        queue.runAll()
        assertNull(cache.file("big"))
        memory = true
        assertNull("файл тот же — не пробуется", cache.file("big"))
        assertEquals(0, queue.tasks.size)
        file.writeText(identity2 + "\n# теперь другой\n")
        assertTrue(file.setLastModified(1_700_000_009_000L))
        assertNull(cache.file("big"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        assertNotNull(cache.file("big"))
    }

    @Test fun defaultCapIsSixteenMegabytesAndAHeavyRealCubeFitsUnderIt() {
        assertEquals(16L shl 20, LutCache.MAX_FILE_BYTES)
        // куб 65³ с длинными числами и CRLF — самый тяжёлый настоящий .cube (около 10 МБ): под пределом, и читается
        val axis = Array(65) { String.format(java.util.Locale.ROOT, "%.9f", it / 64.0) }
        val text = StringBuilder("TITLE \"тяжёлый\"\r\nLUT_3D_SIZE 65\r\n")
        for (b in 0 until 65) for (g in 0 until 65) for (r in 0 until 65) {
            text.append(axis[r]).append(' ').append(axis[g]).append(' ').append(axis[b]).append("\r\n")
        }
        val file = write("heavy", text.toString())
        assertTrue("${file.length()} байт", file.length() in (8L shl 20)..LutCache.MAX_FILE_BYTES)
        val cache = cache()
        cache.file("heavy")
        queue.runAll()
        val cube = cache.file("heavy")
        assertNotNull("$notes", cube)
        assertEquals(65, cube!!.size)
    }

    @Test fun fileOverTheDefaultCapIsRefusedWithoutBeingParsed() {
        val file = write("toomuch", identity2 + "\n" + "#".repeat(((LutCache.MAX_FILE_BYTES - identity2.toByteArray().size) + 1).toInt()))
        assertEquals(LutCache.MAX_FILE_BYTES + 2, file.length())
        val cache = cache()
        cache.file("toomuch")
        queue.runAll()
        assertNull(cache.file("toomuch"))
        assertTrue("$notes", notes.any { "toomuch" in it && "большой" in it })
    }

    // имя для журнала и logcat

    @Test fun appliedNameIsWhatIsReallyOnThePicture() {
        write("look", identity2)
        val cache = cache()
        val profile8 = CameraProfile(pictureProfile = 8)
        assertEquals("slog3cine", cache.appliedName(assist(LutMode.auto), profile8, applied = true))
        assertEquals("off", cache.appliedName(assist(LutMode.auto), CameraProfile(pictureProfile = 0), applied = false))
        assertEquals("hlg", cache.appliedName(assist(LutMode.hlg), CameraProfile.none, applied = true))
        assertEquals("off", cache.appliedName(assist(LutMode.off), profile8, applied = false))
        // «Свой»: пока куб не разобран, на картинке ничего нет
        assertEquals("off", cache.appliedName(assist(LutMode.file, "look"), profile8, applied = false))
        assertEquals("file:look", cache.appliedName(assist(LutMode.file, "look"), profile8, applied = true))
    }

    @Test fun namesWithPathPartsNeverReachTheDisk() {
        // файл вне папки программы читать нельзя: имя — это имя файла в папке, не путь
        File(folder.root, "outside.cube").writeText(identity2)
        val cache = cache()
        for (name in listOf("../outside", "a/b", "..", ".", "", ".hidden", "x\\y", "..\\outside")) {
            assertNull("«$name»", cache.file(name))
        }
        assertEquals(0, queue.tasks.size)
    }

    @Test fun forgetMakesTheNextCallReadAgain() {
        write("look", identity2)
        val cache = cache()
        cache.file("look")
        queue.runAll()
        val old = cache.file("look")
        cache.forget("look")
        assertNull(cache.file("look"))
        assertEquals(1, queue.tasks.size)
        queue.runAll()
        assertNotSame(old, cache.file("look"))
    }

    @Test fun onlyThreeOwnCubesAreKeptInMemory() {
        for (n in 1..4) write("l$n", identity2)
        val cache = cache()
        for (n in 1..4) {
            cache.file("l$n")
            queue.runAll()
        }
        // после четвёртого вытеснен самый давний, l1; остальные на месте
        for (n in 2..4) assertNotNull("l$n", cache.file("l$n"))
        assertNull(cache.file("l1"))
        assertEquals("вытесненный читается заново", 1, queue.tasks.size)
    }

    @Test fun missingFilesDoNotPushGoodCubesOut() {
        for (n in 1..3) write("l$n", identity2)
        val cache = cache()
        for (n in 1..3) {
            cache.file("l$n")
            queue.runAll()
        }
        repeat(10) { assertNull(cache.file("нет $it")) }
        for (n in 1..3) assertNotNull("l$n", cache.file("l$n"))
        assertEquals(0, queue.tasks.size)
    }

    @Test fun lastUsedCubeStaysWhenAnotherIsAdded() {
        for (n in 1..4) write("l$n", identity2)
        val cache = cache()
        for (n in 1..3) {
            cache.file("l$n")
            queue.runAll()
        }
        // l1 снова в ходу — вытесняться будет l2
        val l1 = cache.file("l1")
        assertNotNull(l1)
        cache.file("l4")
        queue.runAll()
        assertSame(l1, cache.file("l1"))
        assertNull(cache.file("l2"))
    }

    @Test fun loadingOneNameDoesNotBlockAnother() {
        write("a", identity2)
        write("b", inverted2)
        val cache = cache()
        cache.file("a")
        cache.file("b")
        assertEquals(2, queue.tasks.size)
        queue.runAll()
        assertNotSame(cache.file("a"), cache.file("b"))
    }
}
