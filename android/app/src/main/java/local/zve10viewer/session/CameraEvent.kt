package local.zve10viewer.session

import java.util.Locale

/**
 * События новых камер Sony, которые сеанс передаёт слушателям ([CameraSession.events]). Изменение свойств (0xC203) сеанс
 * разбирает сам — перечитывает свойства, и слушатель узнаёт о нём из [CameraSession.properties].
 */
sealed class CameraEvent {
    /**
     * 0xC20F: список подписей изменился. [type] — тип списка из параметра события; 0 — параметра не было, значит, все списки:
     * заново 0x9215 с этим типом.
     */
    data class DisplayListChanged(val type: Long) : CameraEvent()

    /** 0xC21B: данные объектива изменились — объектив сменили: заново 0x9223. */
    data object LensChanged : CameraEvent()

    /** 0xC218: запись в 0xE042 закончилась. [reached] — параметр события равен 1: фокус дошёл до цели. */
    data class FocusPositionResult(val reached: Boolean) : CameraEvent()
}

/** Версия кода производителя — ответ 0x9216 ([local.zve10viewer.sony.SonyOp.GET_VENDOR_CODE_VERSION]). */
object VendorCodeVersion {
    /** Версию Sony пишет ×100: 310 (0x0136) — «3.10», 100 — «1.00». */
    fun label(version: Int): String = String.format(Locale.ROOT, "%d.%02d", version / 100, version % 100)
}
