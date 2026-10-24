package local.zve10viewer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.ValueFormatter

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

private val Panel = Color(0xCC1C1C1E)
private val Secondary = Color(0xFF9A9AA0)

@Composable
private fun App(controller: CameraController) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (controller.phase) {
            CameraController.Phase.searching, CameraController.Phase.permission -> WaitingScreen(controller, notPcRemote = false)
            CameraController.Phase.notPcRemote -> WaitingScreen(controller, notPcRemote = true)
            else -> LiveScreen(controller)
        }
    }
}

@Composable
private fun WaitingScreen(controller: CameraController, notPcRemote: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (notPcRemote) "Камера подключена, но не в режиме PC Remote" else "Подключите камеру",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))
        listOf(
            "1. На камере: MENU → Network → Smartphone Connect → Smartphone Connection: Off",
            "2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: USB",
            if (notPcRemote) "Переподключите кабель" else "3. Подключите камеру к планшету кабелем USB-C",
        ).forEach {
            Text(it, color = Secondary, fontSize = 20.sp, modifier = Modifier.padding(vertical = 5.dp))
        }
        Spacer(Modifier.height(16.dp))
        val status = when {
            controller.phase == CameraController.Phase.permission -> "Разрешите доступ к камере в окне Android…"
            controller.message != null -> controller.message!!
            notPcRemote -> null
            else -> "Ищу камеру…"
        }
        if (status != null) Text(status, color = Secondary, fontSize = 18.sp)
    }
}

@Composable
private fun LiveScreen(controller: CameraController) {
    Box(Modifier.fillMaxSize()) {
        val frame = controller.frame
        if (frame != null) {
            Image(
                frame, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (controller.isFrameStale) 0.45f else 1f),
            )
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        StatusPill(controller, Modifier.align(Alignment.TopEnd).padding(20.dp))
        InfoLine(controller, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun StatusPill(controller: CameraController, modifier: Modifier) {
    Row(
        modifier.background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ready = controller.phase == CameraController.Phase.ready
        Box(Modifier.size(10.dp).background(if (ready) Color(0xFF34C759) else Color(0xFFFF9F0A), CircleShape))
        Text("${controller.deviceInfo?.model ?: "Камера"} · USB", color = Color.White, fontSize = 16.sp)
        Text(ValueFormatter.label(SettingID.exposureProgram, controller.property(SettingID.exposureProgram)),
            color = Color.White, fontSize = 16.sp)
        Text("Камера: ${ValueFormatter.label(SettingID.battery, controller.property(SettingID.battery))}",
            color = Color.White, fontSize = 16.sp)
        controller.tabletBattery?.let { level ->
            Text("Планшет: $level%" + if (controller.tabletCharging) " ⚡" else "", color = Color.White, fontSize = 16.sp)
        }
        Text("%.0f к/с".format(controller.fps), color = Secondary, fontSize = 16.sp)
    }
}

@Composable
private fun InfoLine(controller: CameraController, modifier: Modifier) {
    Row(
        modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        SettingCatalog.infoLine(controller.mode).forEach { id ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(id.shortTitle.uppercase(), color = Secondary, fontSize = 12.sp)
                Text(ValueFormatter.label(id, controller.property(id)), color = Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
