package local.zve10viewer

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
        // экран не гаснет, системные панели спрятаны: видео на весь экран
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        controller = CameraController(applicationContext, lifecycleScope)
        controller.start()
        setContent { App(controller) }
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
