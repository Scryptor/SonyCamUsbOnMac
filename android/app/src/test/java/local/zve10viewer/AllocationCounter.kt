package local.zve10viewer

/**
 * Выделенные потоком байты (HotSpot: com.sun.management.ThreadMXBean). Через отражение: тесты собираются с android.jar, где
 * java.lang.management нет, а на JVM, где они идут, он есть. Нет счётчика — [create] отдаёт null, тест пропускается.
 */
internal class AllocationCounter private constructor(private val bean: Any, private val read: java.lang.reflect.Method) {
    fun total(ids: LongArray): Long {
        val sizes = read.invoke(bean, ids) as LongArray
        var sum = 0L
        for (size in sizes) sum += size
        return sum
    }

    /**
     * Сколько байт выделили потоки [ids] за [body] — без цены самого замера: между двумя чтениями счётчика кадров нет, и эта
     * цена вычитается. Лямбда [body] создаётся до замера. Первый замер в процессе ещё платит за своё знакомство с кодом (на
     * JVM это 40 байт), поэтому сначала идёт пробный, пустой, а его результат выбрасывается.
     */
    fun spent(ids: LongArray, body: () -> Unit): Long {
        measure(ids) { }
        return measure(ids, body)
    }

    private fun measure(ids: LongArray, body: () -> Unit): Long {
        // прогрев самого замера: отражение сначала дороже, а пока JIT собирает и его, цена чтения меняется между двумя чтениями
        repeat(30_000) { total(ids) }
        val first = total(ids)
        body()
        val second = total(ids)
        val third = total(ids)
        return (second - first) - (third - second)
    }

    companion object {
        fun create(): AllocationCounter? = try {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val sun = Class.forName("com.sun.management.ThreadMXBean")
            if (bean == null || !sun.isInstance(bean) || sun.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) {
                null
            } else {
                AllocationCounter(bean, sun.getMethod("getThreadAllocatedBytes", LongArray::class.java))
            }
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
