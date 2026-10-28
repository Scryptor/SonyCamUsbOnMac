package local.zve10viewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.MonitorTool

/** «Из файла…»: системный выбор картинки (галерея, «Загрузки», облако); выбрали — она эталон, наложение видно. */
@Composable
fun rememberReferencePicker(controller: CameraController): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            controller.takeReference(uri)
            controller.showOverlay()
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

/**
 * Панель наложения — пока наложение включено и эталон есть, как у программы для Mac. Миниатюра эталона — кнопка
 * «спрятать / показать»: сравнить с живым кадром без эталона и обратно. Под ней — непрозрачность 5–100 %, «Новый кадр»,
 * «Из файла…»; ✕ выключает наложение.
 */
@Composable
fun OverlayPanel(controller: CameraController, reference: CameraController.Reference) {
    val hidden = controller.overlayHidden
    val percent = controller.assist.overlay.percent ?: 50
    val pick = rememberReferencePicker(controller)
    Column(
        Modifier.background(Color(0x73000000), RoundedCornerShape(10.dp)).padding(10.dp).width(320.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp))
                    .border(2.dp, if (hidden) Color.White.copy(alpha = 0.25f) else Accent, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = if (hidden) "Показать эталон" else "Спрятать эталон" }
                    .clickable { controller.toggleOverlayVisible() },
            ) {
                Image(reference.image, null, Modifier.fillMaxSize().alpha(if (hidden) 0.3f else 1f), contentScale = ContentScale.Crop)
                EyeGlyph(hidden, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(26.dp)
                    .background(Color(0x99000000), CircleShape).padding(5.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Наложение", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(reference.source, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (hidden) "Спрятано — коснитесь картинки" else "Касание картинки — спрятать",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, lineHeight = 15.sp)
            }
            Box(
                Modifier.size(32.dp).background(PanelLight, CircleShape)
                    .semantics { contentDescription = "Выключить наложение" }
                    .clickable { controller.setAssist(MonitorTool.overlay, "off") },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 14.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(percent / 100f, { controller.setOverlayOpacity(it) }, valueRange = 0.05f..1f,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Непрозрачность эталона" })
            Text("$percent %", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(56.dp).padding(start = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Новый кадр", enabled = controller.frame != null) {
                controller.takeReferenceFromFrame()
                controller.showOverlay()
            }
            PanelButton("Из файла…") { pick() }
        }
    }
}

@Composable
private fun PanelButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(title, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp,
        modifier = Modifier.background(PanelLight, RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp))
}

/** Глаз на миниатюре эталона: открытый — эталон виден, перечёркнутый — спрятан. */
@Composable
private fun EyeGlyph(hidden: Boolean, modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val line = w * 0.11f
    val eye = Path().apply {
        moveTo(0.04f * w, 0.5f * h)
        cubicTo(0.3f * w, 0.12f * h, 0.7f * w, 0.12f * h, 0.96f * w, 0.5f * h)
        cubicTo(0.7f * w, 0.88f * h, 0.3f * w, 0.88f * h, 0.04f * w, 0.5f * h)
        close()
    }
    drawPath(eye, Color.White, style = Stroke(line, cap = StrokeCap.Round))
    drawCircle(Color.White, radius = 0.14f * w, center = center)
    if (hidden) drawLine(Color.White, Offset(0.12f * w, 0.88f * h), Offset(0.88f * w, 0.12f * h), strokeWidth = line, cap = StrokeCap.Round)
}
