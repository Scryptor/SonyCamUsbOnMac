package local.zve10viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import local.zve10viewer.monitor.AssistExtras
import local.zve10viewer.settings.WiFiAddress
import local.zve10viewer.ui.App
import local.zve10viewer.ui.FilePickers
import local.zve10viewer.ui.LocalFilePickers

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController

    /**
     * Системный выбор файла: «Добавить LUT…» в меню «Монитор» и «Из файла…» у наложения. Запросы регистрируются здесь, на
     * Activity, а не в меню: пока открыт выбор файла, программа в фоне, через минуту сеанс закрывается, экран живого видео
     * сменяется экраном ожидания, и меню с его запросом уходит из композиции — результат пришёл бы по ключу, который уже никто
     * не слушает (так же при гибели процесса). Запрос Activity живёт, пока жива она сама, и Android отдаёт отложенный результат
     * и после её пересоздания. Камера не нужна: LUT пишется в `filesDir/luts`.
     */
    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::controller.isInitialized) controller.importLut(uri)
    }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::controller.isInitialized) {
            controller.takeReference(uri)
            controller.showOverlay()
        }
    }
    private val pickers = FilePickers(
        lut = { lutPicker.launch(arrayOf("*/*")) },
        reference = { referencePicker.launch(arrayOf("image/*")) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // экран не гаснет, системные панели спрятаны: видео на весь экран. Без этого планшет засыпает через ~5 мин без
        // касаний — и посреди видео, и на экране ожидания, где программа сама ищет камеру
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        controller = CameraController(applicationContext, lifecycleScope)
        applyDebugExtras(intent)
        controller.start()
        // только отладочная сборка: увеличение и галерея по adb (мультитач через adb не передать)
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(this, debugReceiver, IntentFilter().apply {
                addAction(ACTION_DEBUG_ZOOM)
                addAction(ACTION_DEBUG_GALLERY)
                addAction(ACTION_DEBUG_ASSIST)
                addAction(ACTION_DEBUG_LENS)
            }, ContextCompat.RECEIVER_EXPORTED)
        }
        setContent { CompositionLocalProvider(LocalFilePickers provides pickers) { App(controller) } }
    }

    /**
     * `adb shell am broadcast -p local.zve10viewer -a local.zve10viewer.DEBUG_ZOOM --ef factor 4 --ef x 0.3 --ef y 0.4
     * [--ez bicubic false]`; `… -a local.zve10viewer.DEBUG_GALLERY --ei index 2` (−1 — закрыть; `--ez delete true` — удалить этот снимок, без index — открытый
     * в галерее; `--ez undo true` — «Вернуть»); монитор —
     * `… -a local.zve10viewer.DEBUG_ASSIST --es tool zebra --es value 95` (без value — вкл/выкл), `--ez off true` — всё
     * выключить, `--ez menu true|false [--es tool peaking]` — меню, `--es waveform luma|parade|off` — волновая форма,
     * `--ez reference true` — эталон из текущего кадра, `--ei shot 0` — из снимка ленты, `--ez overlayToggle true` — как
     * касание миниатюры эталона (включить наложение, спрятать или показать эталон). LUT и вектороскоп —
     * `--es lut off|auto|slog3cine|slog3|slog2|hlg|file`, `--es lutFile Имя` (свой файл из `filesDir/luts`),
     * `--ez lutScopes false`, `--ez vectorscope true`, `--ei vectorscopeZoom 2` — после остального, в одной команде.
     * Растр, картинка и вектороскоп — в logcat с тегом ZVAssist.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DEBUG_ZOOM -> controller.debugCommand(CameraController.DebugCommand.Zoom(
                    intent.getFloatExtra("factor", 1f).toDouble(), intent.getFloatExtra("x", 0.5f).toDouble(),
                    intent.getFloatExtra("y", 0.5f).toDouble(),
                    if (intent.hasExtra("bicubic")) intent.getBooleanExtra("bicubic", true) else null))
                ACTION_DEBUG_GALLERY -> controller.debugCommand(CameraController.DebugCommand.Gallery(intent.getIntExtra("index", -1),
                    intent.getBooleanExtra("delete", false), intent.getBooleanExtra("undo", false)))
                ACTION_DEBUG_LENS -> debugLens(intent)
                ACTION_DEBUG_ASSIST -> controller.debugCommand(CameraController.DebugCommand.Assist(
                    intent.getStringExtra("tool"), intent.getStringExtra("value"), intent.getBooleanExtra("off", false),
                    if (intent.hasExtra("menu")) intent.getBooleanExtra("menu", false) else null,
                    intent.getStringExtra("waveform"), intent.getBooleanExtra("reference", false),
                    if (intent.hasExtra("shot")) intent.getIntExtra("shot", 0) else null,
                    intent.getBooleanExtra("overlayToggle", false), assistExtras(intent)))
            }
        }
    }

    /** Extras про LUT и вектороскоп как их положил adb (`--es`, `--ez`, `--ei`): тип разбирает `AssistExtras`. */
    private fun assistExtras(intent: Intent) = AssistExtras.Request(
        lut = extra(intent, "lut"), lutFile = extra(intent, "lutFile"), lutScopes = extra(intent, "lutScopes"),
        vectorscope = extra(intent, "vectorscope"), vectorscopeZoom = extra(intent, "vectorscopeZoom"),
    )

    // типизированные getXxxExtra молча подставляют значение по умолчанию, если тип не тот; тут тип нужен как он есть
    @Suppress("DEPRECATION")
    private fun extra(intent: Intent, name: String): Any? = intent.extras?.get(name)

    /**
     * Фокус и зум с экрана: `… -a local.zve10viewer.DEBUG_LENS --ez touch true|false` — режим «фокус касанием»,
     * `--ef x 0.3 --ef y 0.4` — фокус в эту точку кадра, `--ez cancel true` — снять слежение, `--es zoom tele|wide|stop`,
     * `--ei focus -7` — шкала фокуса. Что вышло — в logcat с тегом ZVLens.
     */
    private fun debugLens(intent: Intent) {
        if (intent.hasExtra("touch") && intent.getBooleanExtra("touch", false) != controller.touchFocusOn) controller.toggleTouchFocus()
        if (intent.hasExtra("x") && intent.hasExtra("y")) {
            controller.touchFocus(intent.getFloatExtra("x", 0.5f).toDouble(), intent.getFloatExtra("y", 0.5f).toDouble())
        }
        if (intent.getBooleanExtra("cancel", false)) controller.cancelTouch()
        when (intent.getStringExtra("zoom")) {
            "tele" -> controller.zoom(1)
            "wide" -> controller.zoom(-1)
            "stop" -> controller.zoom(0)
        }
        if (intent.hasExtra("focus")) controller.focusStep(intent.getIntExtra("focus", 0))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        controller.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugReceiver) }
        controller.stop()
        super.onDestroy()
    }

    /**
     * Только отладочная сборка: прокси стенда (scripts/badnet.py) или виртуальная камера между программой и камерой,
     * например `adb shell am start -n local.zve10viewer/.MainActivity --es ptpipHost 192.168.1.123
     * --es linkCheckUrl http://192.168.1.123:15798/link`; с портом — `--es ptpipHost 192.168.1.123:15741`. Релизная сборка
     * эти параметры не читает.
     */
    private fun applyDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val host = intent.getStringExtra(EXTRA_PTPIP_HOST)?.let(WiFiAddress::parse)
        val linkCheck = intent.getStringExtra(EXTRA_LINK_CHECK_URL)?.trim()?.takeIf { it.isNotEmpty() }
        if (host != null || linkCheck != null) controller.setDebugTarget(host, linkCheck)
        // поворот экрана для проверки без настроек системы: только своё окно, `--es orientation portrait|landscape|auto`
        when (intent.getStringExtra(EXTRA_ORIENTATION)) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private companion object {
        const val EXTRA_PTPIP_HOST = "ptpipHost"
        const val EXTRA_LINK_CHECK_URL = "linkCheckUrl"
        const val EXTRA_ORIENTATION = "orientation"
        const val ACTION_DEBUG_ZOOM = "local.zve10viewer.DEBUG_ZOOM"
        const val ACTION_DEBUG_GALLERY = "local.zve10viewer.DEBUG_GALLERY"
        const val ACTION_DEBUG_ASSIST = "local.zve10viewer.DEBUG_ASSIST"
        const val ACTION_DEBUG_LENS = "local.zve10viewer.DEBUG_LENS"
    }
}
