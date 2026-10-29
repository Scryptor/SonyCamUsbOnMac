package local.zve10viewer

import local.zve10viewer.monitor.FocusMarkBook
import local.zve10viewer.monitor.FocusMarkBook.Tap
import local.zve10viewer.monitor.FocusMarks
import local.zve10viewer.monitor.FocusPosition
import local.zve10viewer.monitor.FocusPull
import local.zve10viewer.monitor.FocusPull.Mode
import local.zve10viewer.monitor.get
import local.zve10viewer.monitor.with
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Метки фокуса A и B и положение фокуса по свойствам камеры — те же проверки и те же числа, что у программы для Mac
 * (FocusMarkBookTests.swift, 14 проверок).
 */
class FocusMarkBookTest {
    // MARK: - Метки по камерам

    @Test fun marksAreKeptPerCamera() {
        var book = FocusMarkBook()
        book = book.save('A', 20.0, "cam-1", Mode.steps)
        book = book.save('B', 80.0, "cam-1", Mode.steps)
        book = book.save('A', 55.0, "cam-2", Mode.steps)
        assertEquals(FocusMarks(a = 20.0, b = 80.0), book.marks("cam-1", Mode.steps))
        assertEquals(FocusMarks(a = 55.0), book.marks("cam-2", Mode.steps))
        // камеры, которой ещё не было, — пустые метки и две секунды по умолчанию
        assertEquals(FocusMarks(), book.marks("cam-3", Mode.steps))
        // новая метка заменяет прежнюю, вторую не трогает
        book = book.save('A', 31.0, "cam-1", Mode.steps)
        assertEquals(FocusMarks(a = 31.0, b = 80.0), book.marks("cam-1", Mode.steps))
    }

    @Test fun clearErasesOnlyThatMark() {
        var book = FocusMarkBook()
        book = book.save('A', 20.0, "cam", Mode.steps).save('B', 80.0, "cam", Mode.steps)
        book = book.clear('A', "cam", Mode.steps)
        assertEquals(FocusMarks(a = null, b = 80.0), book.marks("cam", Mode.steps))
        // пустую стирать нечего: ничего не меняется, и другая камера не появляется в книге
        val before = book
        book = book.clear('A', "cam", Mode.steps).clear('B', "nobody", Mode.steps)
        assertEquals(before, book)
    }

    @Test fun onlyMarksAAndBExistAndOnlyFinitePositionsAreSaved() {
        var book = FocusMarkBook()
        book = book.save('C', 10.0, "cam", Mode.steps)
        book = book.save('a', 10.0, "cam", Mode.steps)
        book = book.save('A', Double.NaN, "cam", Mode.steps)
        book = book.save('B', Double.POSITIVE_INFINITY, "cam", Mode.steps)
        assertEquals(FocusMarkBook(), book)
        assertEquals(FocusMarks(), book.marks("cam", Mode.steps))
        // положение 0 — настоящая метка (самый ближний фокус), а не «пусто»
        book = book.save('A', 0.0, "cam", Mode.steps)
        assertEquals(0.0, book.marks("cam", Mode.steps).a!!, 0.0)
    }

    @Test fun marksInOtherUnitsAreNotReadInTheOtherMode() {
        var book = FocusMarkBook()
        book = book.save('A', 40.0, "cam", Mode.steps).save('B', 90.0, "cam", Mode.steps).cycleDuration("cam")
        // камера перешла на абсолютный фокус: метки по шкале 0…100 по шкале 0…65535 не читаются, время остаётся
        assertEquals(FocusMarks(duration = 4.0), book.marks("cam", Mode.absolute))
        // вернулась — метки на месте
        assertEquals(FocusMarks(a = 40.0, b = 90.0, duration = 4.0), book.marks("cam", Mode.steps))
        // первая же метка в новом режиме начинает набор заново: рядом с 30000 прежних «90» не лежит
        book = book.save('A', 30000.0, "cam", Mode.absolute)
        assertEquals(FocusMarks(a = 30000.0, b = null, duration = 4.0), book.marks("cam", Mode.absolute))
        assertEquals(FocusMarks(a = null, b = null, duration = 4.0), book.marks("cam", Mode.steps))
        // стереть то, чего в этом режиме не видно, нельзя — и метки другого режима не пропадают
        val other = FocusMarkBook().save('A', 40.0, "cam", Mode.steps).clear('A', "cam", Mode.absolute)
        assertEquals(40.0, other.marks("cam", Mode.steps).a!!, 0.0)
    }

    // MARK: - Время перевода

    @Test fun durationCyclesPerCameraAndKeepsMarks() {
        var book = FocusMarkBook()
        assertEquals(2.0, book.duration("cam"), 0.0)
        book = book.save('A', 12.0, "cam", Mode.steps)
        val seen = ArrayList<Double>()
        repeat(5) {
            book = book.cycleDuration("cam")
            seen += book.duration("cam")
        }
        assertEquals(listOf(4.0, 8.0, 0.0, 1.0, 2.0), seen)
        assertEquals(12.0, book.marks("cam", Mode.steps).a!!, 0.0)
        // у другой камеры своё время
        book = book.cycleDuration("other")
        assertEquals(4.0, book.duration("other"), 0.0)
        assertEquals(2.0, book.duration("cam"), 0.0)
    }

    @Test fun durationIsSetOnlyToListedValues() {
        var book = FocusMarkBook()
        for (seconds in FocusPull.durations) {
            book = book.setDuration(seconds, "cam")!!
            assertEquals(seconds, book.duration("cam"), 0.0)
        }
        val before = book
        for (bad in listOf(3.0, 2.5, -1.0, 9.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull("$bad", book.setDuration(bad, "cam"))
        }
        assertEquals(before, book)
        // время можно поставить и до первой метки
        val fresh = book.setDuration(8.0, "fresh")
        assertNotNull(fresh)
        assertEquals(FocusMarks(duration = 8.0), fresh!!.marks("fresh", Mode.steps))
    }

    @Test fun negativeZeroSecondsIsZeroNotAnotherValue() {
        // −0,0 == 0,0 по IEEE: время из списка. Хранится как 0, а не как −0 — в подписи «0 с», а не «-0 с»
        val book = FocusMarkBook().setDuration(-0.0, "cam")!!
        assertEquals(java.lang.Double.doubleToRawLongBits(0.0), java.lang.Double.doubleToRawLongBits(book.duration("cam")))
    }

    // MARK: - Смена объектива

    @Test fun lensChangeErasesMarksButKeepsTime() {
        var book = FocusMarkBook()
        book = book.save('A', 20.0, "cam", Mode.absolute).save('B', 60000.0, "cam", Mode.absolute)
        book = book.save('A', 70.0, "other", Mode.steps)
        book = book.setDuration(8.0, "cam")!!
        book = book.lensChanged("cam")
        assertEquals(FocusMarks(duration = 8.0), book.marks("cam", Mode.absolute))
        // чужая камера не задета, и камера без меток не появляется в книге
        assertEquals(70.0, book.marks("other", Mode.steps).a!!, 0.0)
        val before = book
        book = book.lensChanged("never-seen")
        assertEquals(before, book)
        // после смены метки ставятся заново, в любом режиме
        book = book.save('A', 33.0, "cam", Mode.steps)
        assertEquals(FocusMarks(a = 33.0, duration = 8.0), book.marks("cam", Mode.steps))
    }

    // MARK: - Касание кнопки

    @Test fun tapOnAnEmptyMarkSaves() {
        assertEquals(Tap.save, FocusMarkBook.tap('A', FocusMarks(), moving = null))
        assertEquals(Tap.save, FocusMarkBook.tap('B', FocusMarks(a = 10.0), moving = null))
        // положение 0 — заданная метка, а не пустая
        assertEquals(Tap.go, FocusMarkBook.tap('A', FocusMarks(a = 0.0), moving = null))
    }

    @Test fun tapOnASetMarkGoesAndOnTheMovingOneStops() {
        val marks = FocusMarks(a = 10.0, b = 90.0)
        assertEquals(Tap.go, FocusMarkBook.tap('A', marks, moving = null))
        assertEquals(Tap.stop, FocusMarkBook.tap('A', marks, moving = 'A'))
        // едет к A, коснулись B: фокус поедет к B, а не остановится
        assertEquals(Tap.go, FocusMarkBook.tap('B', marks, moving = 'A'))
        // едет к A, а B пуста: B запоминает положение, перевод к A не касается
        assertEquals(Tap.save, FocusMarkBook.tap('B', FocusMarks(a = 10.0), moving = 'A'))
    }

    @Test fun marksAreReadAndWrittenByLetter() {
        var marks = FocusMarks(a = 1.0, b = 2.0)
        assertEquals(1.0, marks['A']!!, 0.0)
        assertEquals(2.0, marks['B']!!, 0.0)
        assertNull(marks['C'])
        marks = marks.with('A', 7.0).with('B', null).with('C', 9.0)
        assertEquals(FocusMarks(a = 7.0, b = null), marks)
    }

    @Test fun onlyTwoLettersAreMarks() {
        assertEquals(listOf('A', 'B'), FocusMarkBook.letters)
    }

    // MARK: - Положение фокуса по свойствам камеры

    private fun property(code: Int, type: Int, current: Long, getSet: Int = 1, enabled: Int = 1) =
        SonyProperty(code, type, getSet, enabled, PtpValue.Num(current), null, emptyList(), emptyList())

    private fun properties(vararg list: SonyProperty): Map<Int, SonyProperty> = list.associateBy { it.code }

    @Test fun absoluteModeNeedsAnOpenTargetAndAPosition() {
        val target = property(SonyProp.FOCUS_POSITION_SETTING, PtpType.UINT16, 100)
        val position = property(SonyProp.FOCUS_POSITION_CURRENT, PtpType.UINT16, 40000)
        val percent = property(SonyProp.FOCAL_POSITION, PtpType.UINT8, 61, getSet = 0)
        assertEquals(Mode.absolute, FocusPosition.mode(properties(target, position, percent)))
        assertEquals(40000.0, FocusPosition.current(properties(target, position, percent), Mode.absolute)!!, 0.0)
        // цель перечислена, но не открыта на запись (только показ, закрыта) — шаги
        val shown = property(SonyProp.FOCUS_POSITION_SETTING, PtpType.UINT16, 100, enabled = 2)
        val closed = property(SonyProp.FOCUS_POSITION_SETTING, PtpType.UINT16, 100, enabled = 0)
        val readOnly = property(SonyProp.FOCUS_POSITION_SETTING, PtpType.UINT16, 100, getSet = 0)
        for (notWritable in listOf(shown, closed, readOnly)) {
            assertEquals(Mode.steps, FocusPosition.mode(properties(notWritable, position, percent)))
        }
        // положения 0xE043 нет или оно закрыто — абсолютно ехать нечем
        assertEquals(Mode.steps, FocusPosition.mode(properties(target, percent)))
        val closedPosition = property(SonyProp.FOCUS_POSITION_CURRENT, PtpType.UINT16, 40000, enabled = 0)
        assertEquals(Mode.steps, FocusPosition.mode(properties(target, closedPosition, percent)))
        assertNull(FocusPosition.mode(properties(target, closedPosition)))
    }

    @Test fun stepsModeReadsTheFocalPosition() {
        val percent = property(SonyProp.FOCAL_POSITION, PtpType.UINT8, 61, getSet = 0)
        assertEquals(Mode.steps, FocusPosition.mode(properties(percent)))
        assertEquals(61.0, FocusPosition.current(properties(percent), Mode.steps)!!, 0.0)
        // 0xE043 в режиме шагов не читается, и наоборот
        assertNull(FocusPosition.current(properties(percent), Mode.absolute))
        val position = property(SonyProp.FOCUS_POSITION_CURRENT, PtpType.UINT16, 40000)
        assertNull(FocusPosition.current(properties(position), Mode.steps))
    }

    @Test fun noPositionMeansNoMarks() {
        assertNull(FocusPosition.mode(emptyMap()))
        val closed = property(SonyProp.FOCAL_POSITION, PtpType.UINT8, 61, enabled = 0)
        assertNull(FocusPosition.mode(properties(closed)))
        assertNull(FocusPosition.current(properties(closed), Mode.steps))
        // свойство без числа (строка) положением не считается
        val odd = SonyProperty(SonyProp.FOCAL_POSITION, PtpType.STRING, 0, 1, PtpValue.Text("x"), null, emptyList(), emptyList())
        assertNull(FocusPosition.mode(properties(odd)))
    }

    @Test fun scaleFractionAndTolerance() {
        assertEquals(100.0, FocusPosition.scale(Mode.steps), 0.0)
        assertEquals(65535.0, FocusPosition.scale(Mode.absolute), 0.0)
        assertEquals(0.25, FocusPosition.fraction(25.0, Mode.steps), 0.0)
        assertEquals(0.5, FocusPosition.fraction(32767.5, Mode.absolute), 0.0)
        // за краем и не-число — край шкалы
        assertEquals(0.0, FocusPosition.fraction(-5.0, Mode.steps), 0.0)
        assertEquals(1.0, FocusPosition.fraction(400.0, Mode.steps), 0.0)
        assertEquals(0.0, FocusPosition.fraction(Double.NaN, Mode.absolute), 0.0)
        // допуск «уже у метки» — тот же, что у самого перевода: 0,5 % шкалы и max(1, k/2) при k = 3,5
        assertTrue(abs(FocusPosition.tolerance(Mode.absolute) - 327.675) < 1e-9)
        assertEquals(1.75, FocusPosition.tolerance(Mode.steps), 0.0)
    }

    // MARK: - Допуск один на перевод и на «уже у метки»

    /** Конец абсолютного перевода при расстоянии `distance` до цели: `reached` — вышло, иначе ждёт. */
    private fun absoluteEndsAt(distance: Double): Boolean {
        val pull = FocusPull(Mode.absolute, 10000.0, 30000.0, 1.0)
        // время вышло: цель записана, остаётся ждать, пока фокус подойдёт
        pull.next(1.0, 10000.0)
        val action = pull.next(1.2, 30000.0 - distance)
        return action == FocusPull.Action.Finish(FocusPull.Outcome.reached)
    }

    @Test fun absoluteToleranceIsWhatFocusPullCallsNear() {
        val tolerance = FocusPull.tolerance(Mode.absolute)
        assertEquals(327.675, tolerance, 1e-9)
        assertEquals(tolerance, FocusPosition.tolerance(Mode.absolute), 0.0)
        assertTrue(absoluteEndsAt(tolerance))
        assertFalse(absoluteEndsAt(tolerance + 0.01))
    }

    /** Шаги: перевод 50 → 50 + distance, время вышло, фокус стоит на 50. `reached` — «ближе камера не подойдёт». */
    private fun stepsEndsAt(distance: Double): Boolean {
        val pull = FocusPull(Mode.steps, 50.0, 50.0 + distance, 0.0)
        return pull.next(0.0, 50.0) == FocusPull.Action.Finish(FocusPull.Outcome.reached)
    }

    @Test fun stepsToleranceIsWhatFocusPullCallsNear() {
        val tolerance = FocusPull.tolerance(Mode.steps)
        assertEquals(1.75, tolerance, 0.0)
        assertEquals(tolerance, FocusPosition.tolerance(Mode.steps), 0.0)
        assertTrue(stepsEndsAt(tolerance))
        assertFalse(stepsEndsAt(tolerance + 0.01))
    }
}
