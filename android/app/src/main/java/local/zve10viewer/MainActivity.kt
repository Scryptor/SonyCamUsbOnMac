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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import local.zve10viewer.settings.WiFiAddress
import local.zve10viewer.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController

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
            }, ContextCompat.RECEIVER_EXPORTED)
        }
        setContent { App(controller) }
    }

    /**
     * `adb shell am broadcast -p local.zve10viewer -a local.zve10viewer.DEBUG_ZOOM --ef factor 4 --ef x 0.3 --ef y 0.4
     * [--ez bicubic false]`; `… -a local.zve10viewer.DEBUG_GALLERY --ei index 2` (−1 — закрыть); монитор —
     * `… -a local.zve10viewer.DEBUG_ASSIST --es tool zebra --es value 95` (без value — вкл/выкл), `--ez off true` — всё
     * выключить, `--ez menu true|false [--es tool peaking]` — меню, `--es waveform luma|parade|off` — волновая форма,
     * `--ez reference true` — эталон из текущего кадра, `--ei shot 0` — из снимка ленты. Растр — в logcat с тегом ZVAssist.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DEBUG_ZOOM -> controller.debugCommand(CameraController.DebugCommand.Zoom(
                    intent.getFloatExtra("factor", 1f).toDouble(), intent.getFloatExtra("x", 0.5f).toDouble(),
                    intent.getFloatExtra("y", 0.5f).toDouble(),
                    if (intent.hasExtra("bicubic")) intent.getBooleanExtra("bicubic", true) else null))
                ACTION_DEBUG_GALLERY -> controller.debugCommand(CameraController.DebugCommand.Gallery(intent.getIntExtra("index", -1)))
                ACTION_DEBUG_ASSIST -> controller.debugCommand(CameraController.DebugCommand.Assist(
                    intent.getStringExtra("tool"), intent.getStringExtra("value"), intent.getBooleanExtra("off", false),
                    if (intent.hasExtra("menu")) intent.getBooleanExtra("menu", false) else null,
                    intent.getStringExtra("waveform"), intent.getBooleanExtra("reference", false),
                    if (intent.hasExtra("shot")) intent.getIntExtra("shot", 0) else null))
            }
        }
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
    }
}
