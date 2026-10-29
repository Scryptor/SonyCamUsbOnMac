package local.zve10viewer

import local.zve10viewer.monitor.MarkExtras
import local.zve10viewer.monitor.MarkExtras.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отладочные extras DEBUG_LENS для меток фокуса: `--es mark A`, `--es markOp save|go|clear|tap|stop`, `--ei pullTime 4`. */
class MarkExtrasTest {
    private fun parse(mark: Any? = null, markOp: Any? = null, pullTime: Any? = null) =
        MarkExtras.parse(MarkExtras.Request(mark, markOp, pullTime))

    @Test fun nothingInTheCommandMeansNothingToDo() {
        assertTrue(MarkExtras.Request().isEmpty)
        assertFalse(MarkExtras.Request(mark = "A").isEmpty)
        val parsed = parse()
        assertNull(parsed.mark)
        assertNull(parsed.op)
        assertNull(parsed.pullTime)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test fun aMarkWithoutAnOperationIsATapOnItsButton() {
        val parsed = parse(mark = "A")
        assertEquals('A', parsed.mark)
        assertEquals(Op.tap, parsed.op)
    }

    @Test fun everyOperationIsUnderstoodAndTheLetterMayBeLowercase() {
        for (op in Op.entries) {
            val parsed = parse(mark = "b", markOp = op.name.uppercase())
            assertEquals(op.name, 'B', parsed.mark)
            assertEquals(op, parsed.op)
        }
    }

    @Test fun stopNeedsNoMark() {
        val parsed = parse(markOp = "stop")
        assertNull(parsed.mark)
        assertEquals(Op.stop, parsed.op)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test fun anOperationOnAMarkThatIsNotThereIsRejectedByName() {
        for (op in listOf("save", "go", "clear", "tap")) {
            val parsed = parse(markOp = op)
            assertNull(op, parsed.op)
            assertEquals(op, listOf("mark"), parsed.rejected)
        }
    }

    @Test fun otherLettersAndOperationsAreRejectedAndNothingIsDone() {
        val badMark = parse(mark = "C", markOp = "save")
        assertNull(badMark.op)
        assertEquals(listOf("mark"), badMark.rejected)
        val badOp = parse(mark = "A", markOp = "jump")
        assertNull(badOp.op)
        assertEquals(listOf("markOp"), badOp.rejected)
        val notAString = parse(mark = 5, markOp = true)
        assertNull(notAString.op)
        assertEquals(listOf("mark", "markOp"), notAString.rejected)
    }

    @Test fun pullTimeIsOneOfTheFiveValuesInAnyNumberType() {
        for ((given, expected) in listOf<Pair<Any, Double>>(0 to 0.0, 1 to 1.0, 2L to 2.0, 4f to 4.0, 8.0 to 8.0, "4" to 4.0, " 8 " to 8.0,
            "2.0" to 2.0)) {
            assertEquals("$given", expected, parse(pullTime = given).pullTime!!, 0.0)
        }
    }

    @Test fun otherTimesAreRejected() {
        for (given in listOf<Any>(3, 2.5, -1, 9, "много", "4 с", Double.NaN, Double.POSITIVE_INFINITY, true)) {
            val parsed = parse(pullTime = given)
            assertNull("$given", parsed.pullTime)
            assertEquals("$given", listOf("pullTime"), parsed.rejected)
        }
    }

    @Test fun timeAndMarkAreBothTakenFromOneCommand() {
        val parsed = parse(mark = "A", markOp = "go", pullTime = 8)
        assertEquals(8.0, parsed.pullTime!!, 0.0)
        assertEquals('A', parsed.mark)
        assertEquals(Op.go, parsed.op)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test fun aBadPartDoesNotSpoilTheGoodOne() {
        // время не понято, а метка — да: метка делается, время названо в rejected
        val parsed = parse(mark = "B", markOp = "save", pullTime = 3)
        assertNull(parsed.pullTime)
        assertEquals('B', parsed.mark)
        assertEquals(Op.save, parsed.op)
        assertEquals(listOf("pullTime"), parsed.rejected)
    }
}
