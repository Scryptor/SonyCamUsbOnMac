package local.zve10viewer

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
        setContent { App(controller) }
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
        controller.stop()
        super.onDestroy()
    }

    /**
     * Только отладочная сборка: прокси стенда (scripts/badnet.py) между программой и камерой, например
     * `adb shell am start -n local.zve10viewer/.MainActivity --es ptpipHost 192.168.1.123
     * --es linkCheckUrl http://192.168.1.123:15798/link`. Релизная сборка эти параметры не читает.
     */
    private fun applyDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val host = intent.getStringExtra(EXTRA_PTPIP_HOST)?.trim()?.takeIf { it.isNotEmpty() }
        val linkCheck = intent.getStringExtra(EXTRA_LINK_CHECK_URL)?.trim()?.takeIf { it.isNotEmpty() }
        if (host != null || linkCheck != null) controller.setDebugTarget(host, linkCheck)
    }

    private companion object {
        const val EXTRA_PTPIP_HOST = "ptpipHost"
        const val EXTRA_LINK_CHECK_URL = "linkCheckUrl"
    }
}
