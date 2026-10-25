package local.zve10viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.zve10viewer.CameraController
import local.zve10viewer.debug.DebugRow
import local.zve10viewer.debug.DebugTab

/**
 * Панель отладки поверх экрана: связь, превью, камера, все свойства и журнал событий по вкладкам. Цифры обновляются
 * дважды в секунду, видео под панелью идёт как обычно. «Скопировать» — все вкладки текстом, чтобы переслать.
 * Закрывается только ×: касания по видео прячут и возвращают элементы, а панель остаётся.
 */
@Composable
fun DebugPanel(controller: CameraController, screen: ScreenState, modifier: Modifier) {
    var report by remember { mutableStateOf(controller.debugReport()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            report = controller.debugReport()
        }
    }
    val clipboard = LocalClipboardManager.current
    var copiedAt by remember { mutableLongStateOf(0L) }
    val tab = screen.debugTab
    Column(
        modifier.background(Color(0xF21C1C1E), RoundedCornerShape(18.dp))
            // касания по панели не уходят к видео и кнопкам под ней
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Отладка", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val copied = System.currentTimeMillis() - copiedAt < 2000
            Text(if (copied) "Скопировано" else "Скопировать", color = Accent, fontSize = 16.sp,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(controller.debugText()))
                    copiedAt = System.currentTimeMillis()
                }.padding(horizontal = 10.dp, vertical = 6.dp))
            Text("×", color = Color.White, fontSize = 24.sp,
                modifier = Modifier.clickable { screen.debugOpen = false }.padding(horizontal = 10.dp))
        }
        TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, contentColor = Color.White, divider = {}) {
            DebugTab.entries.forEach { item ->
                Tab(selected = item == tab, onClick = { screen.debugTab = item }, unselectedContentColor = Secondary,
                    text = { Text(item.title, fontSize = 15.sp, maxLines = 1) })
            }
        }
        val rows = report.rows(tab)
        if (rows.isEmpty()) {
            Text(if (tab == DebugTab.log) "Событий пока нет" else "Нет данных: камера не подключена", color = Secondary, fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            // свойства и журнал — моноширинным: коды и время встают ровным столбцом
            val mono = tab == DebugTab.properties || tab == DebugTab.log
            val labelWidth = when (tab) {
                DebugTab.log -> 104.dp
                DebugTab.properties -> 230.dp
                else -> 180.dp
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(rows, key = { index, row -> if (tab == DebugTab.properties) row.label else index }) { _, row ->
                    DebugLine(row, labelWidth, mono)
                }
            }
        }
    }
}

@Composable
private fun DebugLine(row: DebugRow, labelWidth: androidx.compose.ui.unit.Dp, mono: Boolean) {
    val style = TextStyle(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth()) {
        Text(row.label, color = Secondary, fontSize = if (mono) 13.sp else 14.sp, style = style, modifier = Modifier.width(labelWidth))
        Text(row.value, color = Color.White, fontSize = if (mono) 13.sp else 14.sp, lineHeight = 18.sp, style = style,
            modifier = Modifier.weight(1f))
    }
}

/**
 * Долгое нажатие где угодно на элементе (на плашке состояния — панель отладки). Смотрит касания раньше вложенных
 * кнопок и ничего у них не отнимает: короткие нажатия по надписям работают как раньше. Если нажатие стало долгим,
 * отпускание пальца уже не считается нажатием на надпись под ним.
 */
fun Modifier.onLongPress(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // палец отпустили или жест забрал кто-то другой (прокрутка) раньше срока — это не долгое нажатие
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
            true
        }
        if (ended == null) {
            action()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
