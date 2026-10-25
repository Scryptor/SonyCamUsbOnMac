package local.zve10viewer.wifi

/** Почему транспорт PTP/IP закрылся. */
enum class LinkLoss(val title: String) {
    /** Соединение оборвалось: RST, EOF, ошибка отправки или поток не разобрать. */
    broken("оборвалось"),
    /** Связь вернулась (камера отвечает на ping), а TCP так и молчит — соединения сбросили сами. */
    resetAfterOutage("сброшено после провала"),
    /** Связь есть (пробы отвечают), а на команды камера молчит. */
    cameraSilent("камера не отвечает"),
    /** Связи нет слишком долго. */
    noLink("связи нет"),
    /** Закрыла сама программа. */
    closed("закрыто программой");

    /**
     * После такого обрыва переподключаемся сразу и тихо: камера, скорее всего, рядом и ждёт. Связи нет 3 минуты —
     * ждём по-обычному, с поиском в сети; закрыла сама программа — не переподключаемся вовсе.
     */
    val reconnects: Boolean get() = this != noLink && this != closed
}

/** Пороги слежения за связью, мс. */
data class LinkTiming(
    /** Как часто слежение смотрит на связь. */
    val tickMs: Long = 250,
    /** Проба по событийному соединению — если прошлая отвечена и с неё прошло столько. */
    val probeEveryMs: Long = 2000,
    /** Проба без ответа так долго, хотя связь есть, — считаем её потерянной: можно слать следующую. */
    val probeGiveUpMs: Long = 5000,
    /** Ждём байтов, а их нет столько — провал связи. */
    val outageAfterMs: Long = 2500,
    val pingEveryMs: Long = 1000,
    val pingTimeoutMs: Int = 800,
    /** Камера отвечает на ping (считая от первого ответа), а байтов так и нет столько — TCP застрял: сбрасываем. */
    val stuckAfterLinkBackMs: Long = 3000,
    /** Провал дольше этого — сдаёмся. TCP_USER_TIMEOUT чуть больше, чтобы решало слежение, а не ядро. */
    val giveUpAfterMs: Long = 180_000,
    /** Пробы отвечают, а ответа на операцию нет ни байта столько — камера не отвечает. */
    val cameraSilentAfterMs: Long = 15_000,
)

/** Что сделать транспорту по решению слежения. */
sealed class LinkAction {
    data object SendProbe : LinkAction()
    data object Ping : LinkAction()
    /** rst — сбросом: камера освобождает сеанс сразу; иначе обычным закрытием (FIN). */
    data class Close(val reason: LinkLoss, val rst: Boolean) : LinkAction()
}

/**
 * Слежение за связью PTP/IP: по времени прихода байтов (а не целых пакетов) на оба соединения, пробам по
 * событийному и ping камеры. Пауза — не обрыв: пока связи нет, сеанс держим (TCP сам доставит данные, когда она
 * вернётся), а рвём, только когда связь вернулась, а TCP молчит, когда связи нет дольше 3 минут, когда камера
 * отвечает на пробы, а на команды молчит. Без сокетов и таймеров — для тестов; время подставляется, в миллисекундах.
 * Вызывать с одного потока (транспорт держит его под замком).
 */
class LinkSupervisor(private val timing: LinkTiming = LinkTiming(), now: Long) {
    /** Когда пришёл последний байт по любому соединению. */
    var lastRx = now
        private set
    /** Когда пришёл последний байт по командному соединению. */
    var commandRx = now
        private set
    /** С какого момента нет ни байта, если это уже провал; null — связь есть. */
    var outageSince: Long? = null
        private set
    /** Когда кончился последний провал: тишина до него не считается ни в тайм-ауты, ни в «камера не отвечает». */
    var outageEndedAt = NEVER
        private set
    /** Время ответа на последнюю пробу, мс. */
    var rttMs: Long? = null
        private set
    /** Сколько раз мерили время ответа: одинаковые числа подряд — всё равно новые замеры. */
    var rttSamples = 0
        private set
    /** Почему слежение закрыло транспорт; null — открыт. */
    var closedBy: LinkLoss? = null
        private set

    val isOutage: Boolean get() = outageSince != null

    /** С какого момента ждём ответа на операцию; ответ без единого байта до сих пор — тишина считается отсюда. */
    private var waitingSince: Long? = null
    private var probeSentAt: Long? = null
    /** Камера хоть раз ответила на пробу: без этого неотвеченная проба — не признак провала. */
    private var probesAnswered = false
    private var lastProbeAt = NEVER
    private var lastPingAt = NEVER
    private var pingInFlight = false
    /** Когда ping впервые ответил в этом провале. */
    private var linkBackAt: Long? = null
    private var networkLost = false

    /** Пришли байты. command — по командному соединению. Любой новый байт кончает провал. */
    fun received(at: Long, command: Boolean) {
        if (command && at > commandRx) commandRx = at
        if (at <= lastRx) return
        lastRx = at
        if (outageSince != null) {
            outageSince = null
            outageEndedAt = at
            linkBackAt = null
            networkLost = false
        }
    }

    /**
     * Отправлен запрос операции. Тишина считается от самой старой операции, на которую не пришло ни байта:
     * тайм-аут у вызывающего (он спросит снова) её не обнуляет.
     */
    fun operationStarted(now: Long) {
        if (waitingSince == null) waitingSince = now
    }

    /** Пришёл ответ на операцию — камера ничего не должна. */
    fun operationAnswered() {
        waitingSince = null
    }

    fun probeSent(now: Long) {
        probeSentAt = now
        lastProbeAt = now
    }

    fun probeAnswered(now: Long) {
        val sent = probeSentAt
        // ответ на пробу, пережившую провал, — не время отклика
        if (sent != null && now - sent < timing.outageAfterMs) {
            rttMs = now - sent
            rttSamples++
        }
        probeSentAt = null
        probesAnswered = true
        received(now, command = false)
    }

    fun pinged(answered: Boolean, now: Long) {
        pingInFlight = false
        if (outageSince == null) return
        // связь пропала снова — ждём следующего ответа
        linkBackAt = if (answered) linkBackAt ?: now else null
    }

    /** Сеть Wi-Fi пропала — провал сразу, не дожидаясь тишины. */
    fun networkLost(now: Long) {
        networkLost = true
        if (outageSince == null) startOutage(now)
    }

    /**
     * Истёк ли тайм-аут операции: timeoutMs без единого байта с progressAt. Пока связи нет, тайм-аут не идёт
     * (операция просто ждёт), а тишина до конца провала не считается.
     */
    fun expired(now: Long, progressAt: Long, timeoutMs: Long): Boolean =
        outageSince == null && now - maxOf(progressAt, outageEndedAt) >= timeoutMs

    /** Раз в tickMs: что сделать сейчас. Закрытие — последнее решение: дальше слежение молчит. */
    fun tick(now: Long): List<LinkAction> {
        if (closedBy != null) return emptyList()
        if (outageSince == null) {
            val silent = silentFrom()
            if (networkLost || (silent != null && now - silent >= timing.outageAfterMs)) startOutage(silent ?: now)
        }
        val since = outageSince
        if (since != null) {
            // связи нет — обычное закрытие: FIN ядро повторяет само, и он дойдёт, если связь вернётся;
            // связь вернулась, а TCP молчит — RST: камера освобождает сеанс сразу
            if (now - since >= timing.giveUpAfterMs) return close(LinkLoss.noLink, rst = false)
            val back = linkBackAt
            if (back != null && now - back >= timing.stuckAfterLinkBackMs) return close(LinkLoss.resetAfterOutage, rst = true)
        } else {
            val waiting = waitingSince
            if (waiting != null && now - maxOf(waiting, commandRx, outageEndedAt) >= timing.cameraSilentAfterMs) {
                // камера застряла на команде — RST освобождает её сразу, FIN она бы могла и не разобрать
                return close(LinkLoss.cameraSilent, rst = true)
            }
        }
        val actions = ArrayList<LinkAction>(2)
        if (since != null && !pingInFlight && now - lastPingAt >= timing.pingEveryMs) {
            pingInFlight = true
            lastPingAt = now
            actions += LinkAction.Ping
        }
        // во время провала — только ping: пробы копились бы в очереди TCP и пришли бы пачкой, когда связь вернётся
        if (since != null) return actions
        val sent = probeSentAt
        val open = sent != null && now - sent < timing.probeGiveUpMs
        if (!open && now - lastProbeAt >= timing.probeEveryMs) {
            probeSent(now)
            actions += LinkAction.SendProbe
        }
        return actions
    }

    /** С какого момента ждём байтов и ничего не получаем; null — ничего и не ждём (тишина — не провал). */
    private fun silentFrom(): Long? {
        val probe = if (probesAnswered) probeSentAt else null
        val earliest = listOfNotNull(waitingSince, probe).minOrNull() ?: return null
        return maxOf(lastRx, earliest)
    }

    private fun startOutage(since: Long) {
        outageSince = since
        linkBackAt = null
        lastPingAt = NEVER
    }

    private fun close(reason: LinkLoss, rst: Boolean): List<LinkAction> {
        closedBy = reason
        return listOf(LinkAction.Close(reason, rst))
    }

    private companion object {
        /** «Никогда»: далеко в прошлом, но без переполнения в `now - NEVER`. */
        const val NEVER = Long.MIN_VALUE / 4
    }
}
