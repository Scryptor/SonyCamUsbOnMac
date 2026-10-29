package local.zve10viewer.settings

import local.zve10viewer.ptp.PtpIp
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Где камера в сети. У настоящих камер порт всегда 15740; другой бывает только у виртуальных (scripts/fake_cameras.py). */
data class WiFiAddress(val host: String, val port: Int = PtpIp.PORT) {
    /** «192.168.1.40» или «127.0.0.1:15741». */
    override fun toString(): String = if (port == PtpIp.PORT) host else "$host:$port"

    companion object {
        /** «хост» или «хост:порт»; пусто или порт не 1…65535 — null. Несколько двоеточий (IPv6) — весь текст хост. */
        fun parse(text: String): WiFiAddress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val colon = trimmed.indexOf(':')
            if (colon < 0 || colon != trimmed.lastIndexOf(':')) return WiFiAddress(trimmed)
            val host = trimmed.substring(0, colon).trim()
            val port = trimmed.substring(colon + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return if (host.isEmpty()) null else WiFiAddress(host, port)
        }
    }
}

/** Как до камеры дотянуться сейчас: по кабелю (имя устройства USB) или по Wi-Fi. */
sealed class CameraLink {
    data class Usb(val device: String) : CameraLink()
    data class WiFi(val address: WiFiAddress) : CameraLink()

    /** id строки незнакомой камеры: «usb:<устройство>», «wifi:<хост>» или «wifi:<хост>:<порт>». */
    val entryId: String get() = when (this) {
        is Usb -> "usb:$device"
        is WiFi -> "wifi:$address"
    }
}

/**
 * Камера, к которой программа уже подключалась. id — отпечаток серийного номера (сам номер не хранится); aliases — другие
 * отпечатки той же камеры (по Wi-Fi — USN из ответа на поиск, по кабелю — серийный номер USB, если он не совпал); name —
 * как назвали; host и port — где была по Wi-Fi в последний раз (port — только если не 15740); paired — сопряжена;
 * lastUsed — когда подключались, секунды Unix; inStrip — в ленте камер: программа держит её на связи рядом с главной;
 * linked — в группе (цепочка на плитке): камеры группы на связи повторяют за главной настройки, пресеты и запись, когда
 * главная тоже в группе.
 */
data class KnownCamera(
    val id: String,
    val name: String,
    val model: String,
    val aliases: List<String> = emptyList(),
    val host: String? = null,
    val port: Int? = null,
    val paired: Boolean = false,
    val lastUsed: Long? = null,
    val inStrip: Boolean = false,
    val linked: Boolean = false,
) {
    val address: WiFiAddress? get() = host?.let { WiFiAddress(it, port ?: PtpIp.PORT) }

    fun knows(fingerprint: String): Boolean = id == fingerprint || fingerprint in aliases
}

/** Камера, которую видно сейчас: на шине USB или по ответу на поиск в сети. fingerprint — если его можно узнать до подключения. */
data class CameraSighting(val link: CameraLink, val model: String?, val fingerprint: String?, val seenAt: Long)

/** С какой камерой сейчас сеанс (или к какой подключаемся): ready — сеанс готов, иначе «подключаю…». */
data class CurrentCamera(val link: CameraLink, val id: String?, val ready: Boolean, val model: String?)

enum class CameraState { current, side, available, needsPairing, offline }

/**
 * Строка списка камер. id — id знакомой камеры, у незнакомой — CameraLink.entryId; link — как видна сейчас, null — не
 * видна; pairing — идёт сопряжение именно с ней («Сопрячь» в строке), и она ещё не текущая; у камеры в ленте ready —
 * её сеанс на связи (иначе «нет связи»).
 */
data class CameraEntry(
    val id: String,
    val name: String,
    val state: CameraState,
    val link: CameraLink?,
    val known: KnownCamera?,
    val ready: Boolean = false,
    val pairing: Boolean = false,
) {
    /** Адрес по Wi-Fi: где видна сейчас, иначе где была. */
    val address: WiFiAddress? get() = (link as? CameraLink.WiFi)?.address ?: known?.address

    /** «по кабелю», «Wi-Fi · 192.168.1.40» (порт — если не 15740); не видна — где была. */
    val detail: String
        get() = when (val l = link) {
            is CameraLink.Usb -> "по кабелю"
            is CameraLink.WiFi -> "Wi-Fi · ${l.address}"
            null -> known?.address?.let { "Wi-Fi · $it" } ?: "по кабелю"
        }

    val stateLabel: String
        get() = when {
            pairing && state != CameraState.current -> "сопрягаю…"
            else -> plainStateLabel
        }

    private val plainStateLabel: String
        get() = when (state) {
            CameraState.current -> if (ready) "на связи" else "подключаю…"
            CameraState.side -> if (ready) "в ленте" else "в ленте · нет связи"
            CameraState.available -> "свободна"
            CameraState.needsPairing -> "нужно сопряжение"
            CameraState.offline -> if (known?.host != null) "не видна в сети" else "не подключена"
        }

    /**
     * Кнопка в строке; null — кнопки нет. Невидимую знакомую по Wi-Fi пробуют один раз по последнему адресу; камера ленты
     * на связи становится главной сразу, без связи — подключается главной, как свободная.
     */
    val action: String?
        get() = if (pairing) null else when (state) {
            CameraState.current -> null
            CameraState.side -> if (ready) "Сделать главной" else if (address != null) "Подключить" else null
            CameraState.available -> "Подключить"
            CameraState.needsPairing -> "Сопрячь"
            CameraState.offline -> if (known?.host != null) "Подключить" else null
        }

    /** «В ленту» (долгое нажатие): знакомая сопряжённая камера по Wi-Fi, ещё не в ленте и не главная. */
    val canAddToStrip: Boolean
        get() = known != null && known.paired && !known.inStrip && link !is CameraLink.Usb && address != null &&
            (state == CameraState.available || state == CameraState.offline)

    /** «Убрать из ленты». */
    val canRemoveFromStrip: Boolean get() = state == CameraState.side

    /** «Забыть»: знакомая, не главная и не в ленте (оттуда сначала убирают). */
    val canForget: Boolean get() = known != null && state != CameraState.current && state != CameraState.side
}

/**
 * Камеры, которые программа знает, и та, к которой подключались последней (preferred). Неизменяемая: каждое действие —
 * новая книга. Без Android — чтобы проверять тестами; как на Mac (ZVCore/Session/CameraRoster.swift).
 */
data class CameraBook(val cameras: List<KnownCamera> = emptyList(), val preferred: String? = null) {
    fun find(id: String): KnownCamera? = cameras.firstOrNull { it.id == id }

    /** Чья это камера: отпечаток совпал с id или aliases; иначе по Wi-Fi — тот же хост и порт. */
    fun match(sighting: CameraSighting): KnownCamera? = match(sighting.fingerprint, (sighting.link as? CameraLink.WiFi)?.address)

    fun match(fingerprint: String?, address: WiFiAddress?): KnownCamera? {
        fingerprint?.let { print -> cameras.firstOrNull { it.knows(print) }?.let { return it } }
        return address?.let { a -> cameras.firstOrNull { it.address == a } }
    }

    /**
     * Сопряжена ли камера по этому адресу: приветствие — только ей (или при сопряжении). Своя по USN — её флаг; иначе
     * запись с тем же хостом и портом — её; иначе — есть ли сопряжённая запись из прежней памяти: прежние версии
     * стучались к любой найденной камере, адрес которой мог смениться.
     */
    fun isPaired(address: WiFiAddress, fingerprint: String? = null): Boolean {
        fingerprint?.let { print -> cameras.firstOrNull { it.knows(print) }?.let { return it.paired } }
        cameras.firstOrNull { it.address == address }?.let { return it.paired }
        return cameras.any { it.id == LEGACY_ID && it.paired }
    }

    /**
     * С какого адреса начинать без поиска: предпочтённая, если сопряжена и была в сети; иначе сопряжённая, к которой
     * подключались позже всех.
     */
    fun firstAddress(): WiFiAddress? {
        preferred?.let(::find)?.takeIf { it.paired }?.address?.let { return it }
        return cameras.filter { it.paired && it.host != null }.maxByOrNull { it.lastUsed ?: Long.MIN_VALUE }?.address
    }

    /** Имя новой камеры: модель; такое уже у другой — «ZV-E10 2», «ZV-E10 3»… */
    fun defaultName(model: String, except: String? = null): String {
        val taken = cameras.filter { it.id != except }.map { it.name }.toSet()
        if (model !in taken) return model
        var number = 2
        while ("$model $number" in taken) number++
        return "$model $number"
    }

    fun renamed(id: String, name: String): CameraBook {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || find(id) == null) return this
        return copy(cameras = cameras.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /** «Забыть»: записи нет, и предпочтение, если было на ней, снято; из группы она тоже выходит. */
    fun forgotten(id: String): CameraBook =
        CameraBook(cameras.filter { it.id != id }, preferred.takeIf { it != id }).withoutLoneMember()

    /** «Забыть сопряжение» (новый GUID): ни одна камера программу больше не знает, имена остаются. */
    fun unpairedAll(): CameraBook = copy(cameras = cameras.map { it.copy(paired = false) })

    /** «В ленту» / «Убрать из ленты» (убранная — и не в группе); камера, с которой переключились на другую, тоже уходит в ленту. */
    fun withStrip(id: String, inStrip: Boolean): CameraBook {
        val camera = find(id) ?: return this
        val linked = camera.linked && inStrip
        if (camera.inStrip == inStrip && camera.linked == linked) return this
        return copy(cameras = cameras.map { if (it.id == id) it.copy(inStrip = inStrip, linked = linked) else it }).withoutLoneMember()
    }

    /**
     * Цепочка на плитке камеры ленты. Связать — в группе она и главная main; группа одна, так что главная не из группы
     * входит в неё вместе со всеми, кто там уже есть. Отвязать — камера выходит из группы.
     */
    fun withLinked(id: String, linked: Boolean, main: String?): CameraBook {
        if (find(id) == null) return this
        val members = if (linked) setOfNotNull(id, main) else setOf(id)
        val book = copy(cameras = cameras.map { if (it.id in members) it.copy(linked = linked) else it }).withoutLoneMember()
        return if (book == this) this else book
    }

    /**
     * Кто из камер ленты sides повторяет за главной main: камеры группы, если главная сама в группе. Главная не из
     * группы — группа ждёт: ни за кем не повторяет.
     */
    fun followers(main: String?, sides: List<String>): List<String> {
        if (main == null || find(main)?.linked != true) return emptyList()
        return sides.filter { find(it)?.linked == true }
    }

    /** Группа из одной камеры — не группа. */
    private fun withoutLoneMember(): CameraBook {
        val lone = cameras.singleOrNull { it.linked } ?: return this
        return copy(cameras = cameras.map { if (it === lone) it.copy(linked = false) else it })
    }

    /**
     * Плитки ленты сверху вниз: те же id в порядке знакомых камер — выбор главной их не переставляет. id без записи — в
     * конце, в том порядке, в каком пришли.
     */
    fun stripOrder(ids: List<String>): List<String> =
        ids.sortedBy { id -> cameras.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: Int.MAX_VALUE }

    /**
     * Плитку id перетащили на плитку onto: запись id встаёт на место onto — тащили вниз — сразу за ней, вверх — перед ней.
     * Для плиток ленты это и есть «встала на место той, на которую навели», даже когда между ними в книге камеры не из
     * ленты. Неизвестный id или тот же самый — книга та же; предпочтённая не меняется.
     */
    fun moved(id: String, onto: String): CameraBook {
        val from = cameras.indexOfFirst { it.id == id }
        val to = cameras.indexOfFirst { it.id == onto }
        if (from < 0 || to < 0 || from == to) return this
        // вниз — без неё onto на одну выше, и место to как раз за onto; вверх — место to и есть перед onto
        return copy(cameras = cameras.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * Камера по этому адресу ответила «не знаю программу» (Init Fail 1): больше не стучимся. Своей записи нет — значит,
     * стучались из-за прежней памяти: её сопряжение и снимается, как снимали прежние версии.
     */
    fun pairingLost(address: WiFiAddress, fingerprint: String? = null): CameraBook {
        val camera = match(fingerprint, address) ?: cameras.firstOrNull { it.id == LEGACY_ID && it.paired } ?: return this
        return copy(cameras = cameras.map { if (it.id == camera.id) it.copy(paired = false) else it })
    }

    /**
     * Камера подключилась (пришёл DeviceInfo): запомнить её и сделать предпочтённой. Своя — по отпечатку (серийный номер,
     * USN, серийный номер USB). Запись из прежней памяти («legacy») достаётся первой камере по Wi-Fi — по хосту, а нет
     * такой — любой: адрес мог смениться, а помнили прежние версии одну камеру; id становится настоящим отпечатком,
     * имя остаётся. Новая — id: отпечаток серийного номера, иначе USN или USB, иначе адреса. Адрес теперь её: у другой
     * записи с тем же адресом он снимается. Возвращает книгу и id камеры.
     */
    fun connected(serial: String?, model: String, link: CameraLink, prints: List<String>, now: Long): Pair<CameraBook, String> {
        val serialPrint = serial?.trim()?.takeIf { it.isNotEmpty() }?.let(::serialFingerprint)
        val all = (listOfNotNull(serialPrint) + prints).distinct()
        val address = (link as? CameraLink.WiFi)?.address
        val existing = cameras.firstOrNull { camera -> all.any(camera::knows) }
            ?: address?.let { a ->
                cameras.firstOrNull { it.id == LEGACY_ID && it.host == a.host } ?: cameras.firstOrNull { it.id == LEGACY_ID }
            }
        val id = serialPrint ?: existing?.id?.takeIf { it != LEGACY_ID } ?: all.firstOrNull() ?: fingerprint(
            when (link) {
                is CameraLink.Usb -> "usb:${link.device}"
                is CameraLink.WiFi -> "addr:${link.address}"
            })
        val previousId = existing?.id?.takeIf { it != LEGACY_ID && it != id }
        val aliases = ((existing?.aliases ?: emptyList()) + listOfNotNull(previousId) + all).filter { it != id }.distinct()
        val base = existing ?: KnownCamera(id, defaultName(model), model)
        val updated = base.copy(
            id = id,
            aliases = aliases,
            model = model,
            host = address?.host ?: base.host,
            port = if (address != null) address.port.takeIf { it != PtpIp.PORT } else base.port,
            // по кабелю сопряжение не проверяется — остаётся каким было
            paired = address != null || base.paired,
            lastUsed = now / 1000,
        )
        val list = cameras.map { camera ->
            when {
                camera === existing -> updated
                address != null && camera.address == address -> camera.copy(host = null, port = null)
                else -> camera
            }
        }
        return CameraBook(if (existing == null) list + updated else list, id) to id
    }

    /**
     * Список: текущая; видимые — сначала на кабеле, потом по Wi-Fi, каждые по lastUsed (незнакомые в конце); невидимые
     * знакомые — по lastUsed. Камера по Wi-Fi видна WIFI_VISIBLE_MS после последнего ответа. Одна камера — одна строка.
     */
    fun entries(sightings: List<CameraSighting>, current: CurrentCamera?, now: Long, sideReady: Set<String> = emptySet()): List<CameraEntry> {
        val visible = sightings.filter { it.link is CameraLink.Usb || now - it.seenAt < WIFI_VISIBLE_MS }
        val result = ArrayList<CameraEntry>()
        val shown = HashSet<String>()
        current?.let { c ->
            val sighting = visible.firstOrNull { it.link == c.link }
            val known = c.id?.let(::find) ?: sighting?.let(::match)
                ?: (c.link as? CameraLink.WiFi)?.let { match(null, it.address) }
            val name = known?.name ?: c.model ?: sighting?.model ?: unknownName(c.link)
            result += CameraEntry(known?.id ?: c.link.entryId, name, CameraState.current, c.link, known, c.ready)
            known?.let { shown += it.id }
            shown += c.link.entryId
        }
        // камеры ленты — за главной, по порядку книги: на связи — «в ленте», нет — «в ленте · нет связи»
        cameras.filter { it.inStrip && it.id !in shown }.forEach { camera ->
            val sighting = visible.firstOrNull { match(it) == camera }
            val link = sighting?.link ?: camera.address?.takeIf { camera.id in sideReady }?.let { CameraLink.WiFi(it) }
            result += CameraEntry(camera.id, camera.name, CameraState.side, link, camera, camera.id in sideReady)
            shown += camera.id
            sighting?.let { shown += it.link.entryId }
        }
        val seen = visible.map { it to match(it) }
            .sortedWith(compareBy<Pair<CameraSighting, KnownCamera?>> { if (it.first.link is CameraLink.Usb) 0 else 1 }
                .thenByDescending { it.second?.lastUsed ?: Long.MIN_VALUE })
        for ((sighting, known) in seen) {
            val id = known?.id ?: sighting.link.entryId
            if (id in shown || sighting.link.entryId in shown) continue
            shown += id
            shown += sighting.link.entryId
            val state = when {
                sighting.link is CameraLink.Usb -> CameraState.available
                known?.paired == true -> CameraState.available
                else -> CameraState.needsPairing
            }
            result += CameraEntry(id, known?.name ?: sighting.model?.takeIf { sighting.link is CameraLink.Usb } ?: unknownName(sighting.link),
                state, sighting.link, known)
        }
        cameras.filter { it.id !in shown }.sortedByDescending { it.lastUsed ?: Long.MIN_VALUE }.forEach { camera ->
            result += CameraEntry(camera.id, camera.name, CameraState.offline, null, camera)
        }
        return result
    }

    private fun unknownName(link: CameraLink): String = if (link is CameraLink.Usb) "Камера" else "Камера в сети"

    companion object {
        /** Запись из памяти на одну камеру (wifiCameraName / wifiHost): отпечатка ещё нет. */
        const val LEGACY_ID = "legacy"
        /** Сколько камера по Wi-Fi видна после последнего ответа на поиск. */
        const val WIFI_VISIBLE_MS = 30_000L

        /** Отпечаток: 16 первых знаков SHA-256 (hex) от строки — «sn:<номер>», «usn:<USN>» и т.п. */
        fun fingerprint(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).take(8)
                .joinToString("") { "%02x".format(it) }

        fun serialFingerprint(serial: String): String = fingerprint("sn:$serial")

        /** USN — как его отдаёт разбор ответа на поиск: «uuid:…», часть до «::». */
        fun usnFingerprint(usn: String): String = fingerprint("usn:$usn")

        /**
         * Книга из настроек: ключа cameras нет, а есть память на одну камеру (wifiCameraName, wifiHost) — одна запись
         * «legacy»: имя и модель — имя камеры, адрес — последний, сопряжена.
         */
        fun load(stored: String?, preferred: String?, legacyName: String?, legacyHost: String?): CameraBook {
            if (stored != null) return CameraBook(fromJson(stored), preferred).withLegacyGroup(stored)
            if (legacyName == null) return CameraBook(preferred = preferred)
            val address = legacyHost?.let(WiFiAddress::parse)
            val legacy = KnownCamera(LEGACY_ID, legacyName, legacyName, host = address?.host,
                port = address?.port?.takeIf { it != PtpIp.PORT }, paired = true)
            return CameraBook(listOf(legacy), preferred)
        }

        /** Порядок записей — порядок плиток ленты: перетаскивание переставляет записи, сами записи прежние. */
        fun toJson(cameras: List<KnownCamera>): String = JSONArray().apply {
            cameras.forEach { camera ->
                val item = JSONObject().put("id", camera.id).put("aliases", JSONArray(camera.aliases)).put("name", camera.name)
                    .put("model", camera.model)
                camera.host?.let { item.put("host", it) }
                camera.port?.takeIf { it != PtpIp.PORT }?.let { item.put("port", it) }
                item.put("paired", camera.paired)
                camera.lastUsed?.let { item.put("lastUsed", it) }
                // не в ленте — поля нет: запись та же, что у прежних версий
                if (camera.inStrip) item.put("strip", true)
                // «group», а не «linked»: прежние версии писали «linked» с другим смыслом (см. withLegacyGroup)
                if (camera.linked) item.put("group", true)
                put(item)
            }
        }.toString()

        /** Чего эта версия не знает (поля из более новой) — пропускает; запись без id — тоже. */
        fun fromJson(json: String): List<KnownCamera> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val id = text(item, "id") ?: return@mapNotNull null
                val name = text(item, "name")
                val model = text(item, "model") ?: name ?: "Камера"
                val aliases = item.optJSONArray("aliases")?.let { list ->
                    (0 until list.length()).mapNotNull { list.opt(it) as? String }.filter { it.isNotEmpty() }
                } ?: emptyList()
                val port = if (item.has("port") && !item.isNull("port")) item.optInt("port").takeIf { it in 1..65535 && it != PtpIp.PORT } else null
                val lastUsed = if (item.has("lastUsed") && !item.isNull("lastUsed")) item.optLong("lastUsed") else null
                KnownCamera(id, name ?: model, model, aliases, text(item, "host"), port, item.optBoolean("paired", false), lastUsed,
                    item.optBoolean("strip", false), item.optBoolean("group", false))
            }
        }.getOrDefault(emptyList())

        /**
         * Прежние версии: «linked» — у камер ленты, а повторяли они за любой главной. Записей с «group» нет, а с «linked»
         * есть — группа: они и предпочтённая (последняя главная).
         */
        private fun CameraBook.withLegacyGroup(json: String): CameraBook {
            if (cameras.any { it.linked }) return this
            val legacy = runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.takeIf { it.optBoolean("linked", false) }?.let { text(it, "id") }
                }
            }.getOrDefault(emptyList())
            if (legacy.isEmpty()) return this
            val members = legacy.toSet() + listOfNotNull(preferred)
            return copy(cameras = cameras.map { if (it.id in members) it.copy(linked = true) else it }).withoutLoneMember()
        }

        /** Строка из JSON; нет, null или пусто — null (org.json на Android и на JVM по-разному отдают null). */
        private fun text(item: JSONObject, key: String): String? =
            if (!item.has(key) || item.isNull(key)) null else (item.opt(key) as? String)?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Каких камер ленты нет на связи и к каким пора стучаться: в ленте, сопряжены, есть адрес, не главная, их сеанс не открыт и
 * не открывается, адрес не занят главной (или попыткой к ней); к одной — не чаще раза в RETRY_MS; всего на связи — не
 * больше MAX_CAMERAS (главная и до трёх в ленте). Без Android — чтобы проверять тестами.
 */
object CameraStrip {
    /** Главная и до трёх в ленте. */
    const val MAX_CAMERAS = 4
    /** Нет связи с камерой ленты — стучимся раз во столько. */
    const val RETRY_MS = 10_000L

    /**
     * busy — камеры ленты, чей сеанс открыт или открывается; busyAddresses — адреса, занятые главной (сеанс, попытка,
     * восстановление); lastTry — когда к камере стучались в последний раз. Видна в сети — по её нынешнему адресу,
     * иначе по последнему.
     */
    fun due(book: CameraBook, mainId: String?, busy: Set<String>, busyAddresses: Set<WiFiAddress>, lastTry: Map<String, Long>,
            sightings: List<CameraSighting>, now: Long): List<Pair<KnownCamera, WiFiAddress>> {
        val room = MAX_CAMERAS - (if (mainId != null || busyAddresses.isNotEmpty()) 1 else 0) - busy.size
        if (room <= 0) return emptyList()
        return book.cameras.asSequence()
            .filter { it.inStrip && it.paired && it.id != mainId && it.id !in busy }
            .filter { camera -> lastTry[camera.id]?.let { now - it >= RETRY_MS } ?: true }
            .mapNotNull { camera ->
                val seen = sightings.firstOrNull { it.link is CameraLink.WiFi && now - it.seenAt < CameraBook.WIFI_VISIBLE_MS &&
                    book.match(it) == camera }
                val address = (seen?.link as? CameraLink.WiFi)?.address ?: camera.address ?: return@mapNotNull null
                if (address in busyAddresses) null else camera to address
            }
            .take(room)
            .toList()
    }

    /** Главная пропала: главной становится верхняя камера ленты на связи (connected — их id), а не та, что подключилась раньше. */
    fun successor(book: CameraBook, connected: List<String>): String? = book.stripOrder(connected).firstOrNull()
}

/**
 * Что не удалось на связанных камерах за одну серию изменений — одной строкой состояния: «Не удалось на «C»: ISO 51200»,
 * несколько камер — через «; ». Неизменяемая, как книга; как на Mac (GroupFailures в ZVCore/Session/CameraRoster.swift).
 */
data class GroupFailures(val items: List<Pair<String, String>> = emptyList()) {
    /** Одно и то же дважды не повторяем. */
    fun plus(camera: String, what: String): GroupFailures =
        if (camera to what in items) this else GroupFailures(items + (camera to what))

    val text: String?
        get() {
            if (items.isEmpty()) return null
            val byCamera = LinkedHashMap<String, MutableList<String>>()
            items.forEach { (camera, what) -> byCamera.getOrPut(camera) { mutableListOf() } += what }
            return "Не удалось " + byCamera.entries.joinToString("; ") { (camera, failed) -> "на «$camera»: " + failed.joinToString(", ") }
        }

    companion object {
        /** Запись не началась или не остановилась. */
        const val RECORDING = "запись"

        /**
         * Что не дошло: «ISO 51200», «1/50», «f/2.8», «AWB», «Видео M», «Фокус AF-C», «Look FL». [names] — названия значений от
         * самой главной камеры (`DisplayStrings.names`): значение выбрано по её списку.
         */
        fun label(id: SettingID, value: Long, names: Map<Long, String>? = null): String = when (id) {
            SettingID.shutter, SettingID.aperture, SettingID.colorTemperature, SettingID.whiteBalance, SettingID.exposureProgram,
            SettingID.movieRecordingSetting -> ValueFormatter.label(id, value)
            SettingID.iso -> "ISO " + ValueFormatter.label(id, value)
            else -> id.shortTitle + " " + ValueFormatter.label(id, value, names)
        }
    }
}
