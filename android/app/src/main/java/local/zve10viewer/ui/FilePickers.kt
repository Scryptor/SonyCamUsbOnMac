package local.zve10viewer.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Системный выбор файла — «Добавить LUT…» в меню «Монитор» и «Из файла…» у наложения. Экраны только запускают выбор
 * ([lut], [reference]); запрос и приём результата — у `MainActivity`, потому что экран, с которого выбор начали, до результата
 * может не дожить: пока открыт выбор файла, программа в фоне, через минуту сеанс закрывается, `App` сменяет экран живого видео
 * экраном ожидания, и запрос, зарегистрированный в меню, уходит из композиции — результат пришёл бы по ключу, который уже никто
 * не слушает (так же при гибели процесса). Запрос Activity живёт, пока жива она сама, и Android отдаёт отложенный результат и
 * после её пересоздания. Камера для этого не нужна: LUT пишется в `filesDir/luts`, эталон — в файл программы.
 */
class FilePickers(
    /** Выбрать файл `.cube` (любой файл: у `.cube` в системе часто нет своего типа); результат — `CameraController.importLut`. */
    val lut: () -> Unit,
    /** Выбрать картинку-эталон; результат — `CameraController.takeReference` и включённое наложение. */
    val reference: () -> Unit,
) {
    companion object {
        /** Без Activity (предпросмотр): ничего не запускается. */
        val none = FilePickers(lut = {}, reference = {})
    }
}

/** Кто запускает выбор файла для экранов; задаёт `MainActivity`. */
val LocalFilePickers = staticCompositionLocalOf { FilePickers.none }
