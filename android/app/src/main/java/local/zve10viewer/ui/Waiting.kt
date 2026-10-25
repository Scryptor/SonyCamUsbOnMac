package local.zve10viewer.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController

/**
 * Экран, пока камеры нет, — пустой видоискатель, как на Mac. В рамке фокуса — что планшет делает и что сделать вам, под
 * ней — камеры (подключить, сопрячь), ниже — как подключить: раскрыто, пока планшет не знает ни одной камеры, потом
 * свёрнуто. wifiOn — включено ли подключение по Wi-Fi (в настройках); onWifiOn — кнопка «Включить Wi-Fi» в рамке.
 */
@Composable
fun WaitingContent(controller: CameraController, notPcRemote: Boolean, wifiOn: Boolean, onWifiOn: () -> Unit) {
    val pairing = controller.isPairing
    val permission = controller.phase == CameraController.Phase.permission
    val paired = controller.cameraBook.cameras.count { it.paired }
    val headline = when {
        notPcRemote -> "Камера на кабеле, но не в режиме PC Remote"
        permission -> "Разрешите доступ к камере"
        pairing -> "Сопряжение с камерой"
        !wifiOn -> "Подключите камеру кабелем"
        paired == 0 -> "Подключите камеру"
        paired > 1 -> "Ищу камеры"
        else -> "Ищу камеру"
    }
    val lead = when {
        notPcRemote -> "Включите на камере PC Remote по USB — шаги ниже — и переподключите кабель. По Wi-Fi камера может подключиться и так."
        permission -> "Android спрашивает, можно ли программе работать с камерой на кабеле, — нажмите «ОК» в его окне."
        pairing -> "На камере: MENU › Network › PC Remote Function › Pairing — и подтвердите сопряжение там."
        !wifiOn -> "Подключение по Wi-Fi выключено."
        paired == 0 -> "Кабелем USB-C — сразу. По Wi-Fi — сначала один раз сопрягите камеру с этим планшетом."
        else -> "Включите камеру — программа подключится сама, по кабелю USB-C или по Wi-Fi."
    }
    // ход дела: при сопряжении — что с камерой сейчас (это не беда, а этап), иначе — когда следующая попытка
    val progress = if (pairing) controller.wifiStatus else controller.message
    val problem = if (pairing) null else controller.wifiStatus
    var guideOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val open = guideOpen ?: (paired == 0 || notPcRemote)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        // ищет — рамка «дышит», как камера, что ищет фокус; сопряжение — синяя: ждём ответа камеры
        Viewfinder(breathing = !notPcRemote && !permission && (pairing || wifiOn), color = if (pairing) Accent else Color.White) {
            Text(headline, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(lead, color = Secondary, fontSize = 18.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
            progress?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Secondary, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            }
            if (pairing) {
                PillButton("Отмена", PanelLight) { controller.cancelPairing() }
            } else if (!notPcRemote && !permission && !wifiOn) {
                PillButton("Включить Wi-Fi", Accent, onWifiOn)
            }
        }
        problem?.let { Notice(it, Modifier.widthIn(max = 640.dp).fillMaxWidth()) }
        // список ищет камеры в сети, пока он на экране, — поэтому он здесь всегда, и пустой тоже
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().background(Panel, RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp)) {
            // сопряжение идёт — «Отмена» и ход дела в рамке, второй раз в списке не нужны
            CameraList(controller, Modifier.fillMaxWidth(), pairButton = !pairing, titleSize = 22.sp,
                emptyNote = "Планшет пока не видит ни одной камеры: подключите кабелем или сопрягите по Wi-Fi.")
        }
        Guide(open, notPcRemote) { guideOpen = !open }
    }
}

/** Рамка фокуса из четырёх уголков, как в видоискателе. «Дышит» за 3,2 с; с выключенной анимацией в Android — стоит. */
@Composable
private fun Viewfinder(breathing: Boolean, color: Color, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val phase = if (breathing && !still) {
        rememberInfiniteTransition(label = "рамка").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "вдох").value
    } else 1f
    Box(Modifier.widthIn(max = 600.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().graphicsLayer {
            val scale = 0.97f + 0.03f * phase
            scaleX = scale
            scaleY = scale
            alpha = 0.4f + 0.6f * phase
        }) {
            val width = 2.dp.toPx()
            val inset = width / 2
            val length = minOf(30.dp.toPx(), size.width / 3, size.height / 3)
            listOf(Offset(inset, inset) to Offset(1f, 1f), Offset(size.width - inset, inset) to Offset(-1f, 1f),
                Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f), Offset(inset, size.height - inset) to Offset(1f, -1f))
                .forEach { (corner, toward) ->
                    drawLine(color, corner, Offset(corner.x + toward.x * length, corner.y), width, StrokeCap.Round)
                    drawLine(color, corner, Offset(corner.x, corner.y + toward.y * length), width, StrokeCap.Round)
                }
        }
        Column(Modifier.padding(horizontal = 56.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun PillButton(label: String, background: Color, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 18.dp)
        .background(background, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp))
}

/** Что мешает подключиться — жёлтым, с треугольником. */
@Composable
private fun Notice(text: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.padding(top = 3.dp).size(18.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height * 0.92f)
                lineTo(0f, size.height * 0.92f)
                close()
            }
            drawPath(path, Amber)
            val x = size.width / 2
            drawLine(Color.Black, Offset(x, size.height * 0.32f), Offset(x, size.height * 0.6f), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.Black, 1.2.dp.toPx(), Offset(x, size.height * 0.76f))
        }
        Text(text, color = Amber, fontSize = 17.sp, lineHeight = 23.sp)
    }
}

/** «Как подключить камеру»: касание раскрывает и сворачивает; шаги — по кабелю и по Wi-Fi. */
@Composable
private fun Guide(open: Boolean, notPcRemote: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = if (open) "Свернуть: как подключить камеру" else "Как подключить камеру" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Как подключить камеру", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Canvas(Modifier.size(12.dp)) {
                // стрелка: раскрыто — вниз, свёрнуто — вправо
                val path = if (open) Path().apply { moveTo(0f, size.height * 0.3f); lineTo(size.width / 2, size.height * 0.75f); lineTo(size.width, size.height * 0.3f) }
                else Path().apply { moveTo(size.width * 0.3f, 0f); lineTo(size.width * 0.75f, size.height / 2); lineTo(size.width * 0.3f, size.height) }
                drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (open) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Steps("По кабелю USB", listOf(
                    menu("MENU", "Network", "Smartphone Connect") + separator + setting("Smartphone Connection", "Off"),
                    menu("MENU", "Network", "PC Remote Function") + separator + setting("PC Remote", "On") + plain(", ")
                        + setting("PC Remote Cnct Method", "USB"),
                    plain(if (notPcRemote) "Переподключите кабель" else "Подключите камеру к планшету кабелем USB-C"),
                ), Modifier.weight(1f))
                Steps("По Wi-Fi", listOf(
                    plain("Камера и планшет — в одной сети Wi-Fi. На камере: ") + menu("MENU", "Network", "Wi-Fi Settings", "Access Point Set."),
                    menu("MENU", "Network", "PC Remote Function") + separator + setting("PC Remote", "On") + plain(", ")
                        + setting("PC Remote Cnct Method", "Wi-Fi Access Point"),
                    plain("Один раз — сопряжение: «Сопрячь новую камеру…» выше, а на камере ") + menu("MENU", "Network", "PC Remote Function", "Pairing"),
                ), Modifier.weight(1f), note = plain("Без роутера: на камере ") + setting("PC Remote Cnct Method", "Wi-Fi Direct")
                    + plain(", а планшет подключите к сети камеры DIRECT-… — пароль на экране камеры."))
            }
        }
    }
}

/** Шаги одного способа: номер — это и правда порядок действий. */
@Composable
private fun Steps(title: String, items: List<AnnotatedString>, modifier: Modifier, note: AnnotatedString? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(26.dp).border(1.dp, Secondary.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = Secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(item, color = Secondary, fontSize = 17.sp, lineHeight = 24.sp)
            }
        }
        note?.let { Text(it, color = Secondary, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

/** Путь в меню камеры, как на её экране: разделы — ярче текста вокруг, между ними «›». */
private fun menu(vararg items: String): AnnotatedString = buildAnnotatedString {
    items.forEachIndexed { index, item ->
        if (index > 0) append(separator)
        withStyle(SpanStyle(color = Color.White)) { append(item) }
    }
}

private val separator: AnnotatedString = buildAnnotatedString { withStyle(SpanStyle(color = Color.White.copy(alpha = 0.35f))) { append(" › ") } }

/** Пункт меню и что в нём выбрать: значение — синим. */
private fun setting(name: String, value: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Color.White)) { append("$name: ") }
    withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold)) { append(value) }
}

private fun plain(text: String): AnnotatedString = AnnotatedString(text)
