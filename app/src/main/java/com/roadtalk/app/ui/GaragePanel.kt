package com.roadtalk.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roadtalk.app.ui.scene3d.CarLook
import com.roadtalk.app.ui.scene3d.GarageView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * '내 차 고르기' 화면. 왼쪽 도로에서 내 차를 누르면 오른쪽 지도 자리에 뜬다.
 * 가운데 회전 받침 위의 큰 차([GarageView], 3D) 둘레에 차종 카드가 부채꼴로 놓이고, 아래에서 색을 고른다.
 * 고르는 즉시 적용돼서 왼쪽 도로의 내 차가 바뀌고, 서버를 거쳐 다른 차 화면에도 그 모습으로 보인다.
 */
@Composable
fun GaragePanel(
    myType: Int,
    mySkin: Int,
    onPickType: (Int) -> Unit,
    onPickSkin: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val garage = remember { GarageView(context) }
    var thumbnails by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    DisposableEffect(garage) {
        garage.onThumbnails = { list: List<Bitmap> -> thumbnails = list.map { it.asImageBitmap() } }
        garage.onResume()
        onDispose {
            garage.onThumbnails = null
            garage.onPause()
        }
    }
    SideEffect {
        garage.type = myType
        garage.skin = mySkin
    }
    val textMeasurer = rememberTextMeasurer()
    val currentType by rememberUpdatedState(myType)
    val currentOnPickType by rememberUpdatedState(onPickType)
    val (koreanName, englishName) = CarLook.TYPE_NAMES[myType.coerceIn(0, CarLook.TYPE_COUNT - 1)]

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(GarageColors.Backdrop)
    ) {
        AndroidView(factory = { garage }, modifier = Modifier.fillMaxSize())

        // 차종 카드 (부채꼴)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cardWidth = w * CARD_WIDTH
                        val hit = CarLook.GARAGE_TYPES.indices
                            .map { it to cardCenter(it, w, h) }
                            .minByOrNull { (_, c) -> hypot(c.x - tap.x, c.y - tap.y) }
                            ?.takeIf { (_, c) -> hypot(c.x - tap.x, c.y - tap.y) < cardWidth * 0.62f }
                        if (hit != null) currentOnPickType(CarLook.GARAGE_TYPES[hit.first])
                    }
                }
        ) {
            val order = CarLook.GARAGE_TYPES.indices.sortedBy { CarLook.GARAGE_TYPES[it] == currentType }   // 고른 카드를 맨 위에
            for (slot in order) {
                val type = CarLook.GARAGE_TYPES[slot]
                drawCard(
                    center = cardCenter(slot, size.width, size.height),
                    angle = cardAngle(slot) * 0.42f,
                    width = size.width * CARD_WIDTH * (if (type == myType) 1.24f else 1f),
                    image = thumbnails.getOrNull(type),
                    label = CarLook.TYPE_NAMES[type].first,
                    selected = type == myType,
                    textMeasurer = textMeasurer,
                )
            }
        }

        // 머리글
        Column(Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 20.dp)) {
            Text("MY CAR", fontSize = 38.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
            Box(Modifier.width(132.dp).height(5.dp).background(GarageColors.Hot))
            Spacer(Modifier.height(6.dp))
            Text("내 차 고르기 · 다른 차 화면에도 이 모습으로 보여요", fontSize = 14.sp, color = GarageColors.Dim)
        }
        Text(
            "✕  지도로 돌아가기",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x66000000))
                .border(1.5.dp, GarageColors.Cool, RoundedCornerShape(50))
                .clickable(onClick = onClose)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )

        // 아래: 색 고르기 · 완료 · 이름판
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("COLOR  색상", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = GarageColors.Cool)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarLook.SKIN_COLORS.forEachIndexed { index, argb ->
                            val picked = index == mySkin
                            Box(
                                Modifier
                                    .size(if (picked) 58.dp else 50.dp)
                                    .clip(CircleShape)
                                    .background(if (picked) Color.White else Color(0x55FFFFFF))
                                    .padding(if (picked) 4.dp else 2.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable { onPickSkin(index) }
                            )
                        }
                    }
                }
                Text(
                    "이 차로 달리기  ▶",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF1A1200),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(GarageColors.Hot, Color(0xFFFFD25E))))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 26.dp, vertical = 16.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            // 이름판: 비스듬한 판 위아래에 주황 줄
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .drawBehind {
                        val slant = size.height * 0.34f
                        val plate = Path().apply {
                            moveTo(slant, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width - slant, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(plate, Brush.verticalGradient(listOf(Color(0xF02B3345), Color(0xF0121823))))
                        val line = 5.dp.toPx()
                        drawLine(GarageColors.Hot, Offset(slant, line / 2), Offset(size.width, line / 2), line)
                        drawLine(GarageColors.Hot, Offset(0f, size.height - line / 2), Offset(size.width - slant, size.height - line / 2), line)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(koreanName, fontSize = 46.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "$englishName · ${CarLook.SKIN_NAMES[mySkin.coerceIn(0, CarLook.SKIN_COUNT - 1)]}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        color = GarageColors.Cool,
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                }
            }
        }
    }
}

private object GarageColors {
    val Backdrop = Color(0xFF0B1220)
    val Cool = Color(0xFF39C6F4)
    val Hot = Color(0xFFFFA726)
    val Dim = Color(0xFFA9B6CC)
}

/** 카드 가로 길이 (패널 폭 대비) */
private const val CARD_WIDTH = 0.158f
private const val CARD_ASPECT = 0.64f

/** slot 번째 카드가 부채꼴에서 놓이는 각도 (도, 위쪽이 0, 오른쪽이 +) */
private fun cardAngle(slot: Int): Float {
    val count = CarLook.GARAGE_TYPES.size
    return -70f + 140f * slot / (count - 1)
}

private fun cardCenter(slot: Int, width: Float, height: Float): Offset {
    val a = Math.toRadians(cardAngle(slot).toDouble())
    return Offset(width / 2 + width * 0.415f * sin(a).toFloat(), height * 0.47f - height * 0.29f * cos(a).toFloat())
}

/** 비스듬한 네모 카드 하나: 어두운 바탕 + 차 그림 + 테두리 (고른 카드는 주황, 나머지는 하늘색) + 이름 */
private fun DrawScope.drawCard(
    center: Offset,
    angle: Float,
    width: Float,
    image: ImageBitmap?,
    label: String,
    selected: Boolean,
    textMeasurer: TextMeasurer,
) {
    val w = width
    val h = width * CARD_ASPECT
    val slant = w * 0.11f
    translate(center.x, center.y) {
        rotate(angle, pivot = Offset.Zero) {
            val shape = Path().apply {
                moveTo(-w / 2 + slant, -h / 2)
                lineTo(w / 2, -h / 2)
                lineTo(w / 2 - slant, h / 2)
                lineTo(-w / 2, h / 2)
                close()
            }
            val edge = if (selected) GarageColors.Hot else GarageColors.Cool
            if (selected) drawPath(shape, edge.copy(alpha = 0.35f), style = Stroke(14.dp.toPx()))
            drawPath(shape, Brush.verticalGradient(listOf(Color(0xF21B2A45), Color(0xF20A111E)), startY = -h / 2, endY = h / 2))
            clipPath(shape) {
                if (image != null) {
                    drawImage(
                        image,
                        dstOffset = IntOffset((-w / 2).toInt(), (-h / 2 - h * 0.03f).toInt()),
                        dstSize = IntSize(w.toInt(), (w * image.height / image.width).toInt()),
                    )
                }
                val text = textMeasurer.measure(
                    AnnotatedString(label),
                    TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                )
                drawRect(Color(0x99000000), Offset(-w / 2, h / 2 - text.size.height - 6.dp.toPx()), Size(w, text.size.height + 6.dp.toPx()))
                drawText(text, topLeft = Offset(-w / 2 + slant * 0.5f + 8.dp.toPx(), h / 2 - text.size.height - 3.dp.toPx()))
            }
            drawPath(shape, edge, style = Stroke((if (selected) 5.dp else 3.dp).toPx()))
        }
    }
}
