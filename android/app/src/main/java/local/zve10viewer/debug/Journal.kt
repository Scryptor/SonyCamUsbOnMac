package local.zve10viewer.debug

import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/** Событие журнала отладки: когда (мс, как System.currentTimeMillis) и что случилось. */
data class JournalEntry(val at: Long, val text: String)

/**
 * Последние события по кругу: новое вытесняет самое старое. Пишут разные потоки (слежение за связью, главный),
 * поэтому всё под замком.
 */
class EventJournal(private val capacity: Int = 200) {
    private val entries = ArrayDeque<JournalEntry>(capacity)

    @Synchronized
    fun add(at: Long, text: String) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(JournalEntry(at, text))
    }

    /** Новые сверху. */
    @Synchronized
    fun newestFirst(): List<JournalEntry> = entries.reversed()

    @Synchronized
    fun clear() = entries.clear()
}

/** Время события с миллисекундами: «13:45:10.123». */
fun clockTime(at: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = at }
    return "%02d:%02d:%02d.%03d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        c.get(Calendar.MILLISECOND))
}

/**
 * Журнал связи: каждое событие — в logcat (тег ZVLink, как и раньше) и в последние 200 событий для панели отладки.
 * Один на всю программу: пересоздание экрана журнал не теряет.
 */
object LinkJournal {
    const val TAG = "ZVLink"
    val events = EventJournal(200)

    fun event(text: String) {
        Log.i(TAG, text)
        events.add(System.currentTimeMillis(), text)
    }
}
