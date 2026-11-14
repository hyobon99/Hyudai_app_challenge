package com.roadtalk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 네온사인 느낌의 꾸밈: 검은 바탕 + 하늘색에서 보라·분홍으로 넘어가는 빛나는 테두리, 빛이 번지는 글자.
 * 말풍선, 말하기 버튼, 도로 화면의 동그란 버튼, 메시지 함이 같이 쓴다.
 * 빛 번짐은 같은 선을 굵고 옅게 여러 번 겹쳐 그려서 낸다.
 */
object Neon {
    val Cyan = Color(0xFF4FD8FF)
    val Violet = Color(0xFFB06BFF)
    val Pink = Color(0xFFFF6BD6)
    val Text = Color(0xFFEAFBFF)
    val Fill = Color(0xF2050810)
    /** 채도를 뺀 색 (고르지 않은 것 표시): 회색빛 테두리와 글자 */
    private val MutedLine = listOf(Color(0xFF7F8A93), Color(0xFF8A8894), Color(0xFF94888F))
    val MutedText = Color(0xFF9AA3AD)

    /** 번지는 빛이 있는 글자. strong = 더 진하게 (이미 고른 것 표시 등), muted = 회색빛 (빛 번짐 없음) */
    fun text(sizeSp: Int, strong: Boolean = false, weight: FontWeight = FontWeight.Bold, muted: Boolean = false): TextStyle = TextStyle(
        color = if (muted) MutedText else if (strong) Color.White else Text,
        fontSize = sizeSp.sp,
        fontWeight = weight,
        shadow = if (muted) null else Shadow(color = if (strong) Pink else Cyan, offset = Offset.Zero, blurRadius = if (strong) 26f else 16f),
    )

    fun brush(left: Float, right: Float, muted: Boolean = false) =
        Brush.horizontalGradient(if (muted) MutedLine else listOf(Cyan, Violet, Pink), startX = left, endX = right)
}

/** 빛 번짐을 내는 겹: (선 굵기 dp, 진하기) */
private val GLOW = listOf(18f to 0.07f, 12f to 0.12f, 7f to 0.22f)

/**
 * 네온 테두리의 둥근 네모. strength = 빛의 세기 (1 보통, 더 크면 진하게), fill = 안쪽 색 (null 이면 비움),
 * line = 테두리 굵기의 배율 (작은 상자는 1 보다 작게), muted = 채도를 뺀 회색빛 테두리 (빛 번짐 없음)
 */
fun DrawScope.neonRoundRect(
    topLeft: Offset,
    size: Size,
    radius: Float,
    strength: Float = 1f,
    fill: Color? = Neon.Fill,
    line: Float = 1f,
    muted: Boolean = false,
) {
    val corner = CornerRadius(radius)
    val brush = Neon.brush(topLeft.x, topLeft.x + size.width, muted)
    if (fill != null) drawRoundRect(fill, topLeft, size, corner)
    if (muted) {
        drawRoundRect(brush, topLeft, size, corner, style = Stroke(2.dp.toPx() * line))
        return
    }
    for ((width, alpha) in GLOW) {
        drawRoundRect(brush, topLeft, size, corner, alpha = (alpha * strength).coerceAtMost(1f), style = Stroke(width.dp.toPx() * line))
    }
    drawRoundRect(brush, topLeft, size, corner, style = Stroke(3.dp.toPx() * line))
    drawRoundRect(Color.White, topLeft, size, corner, alpha = 0.75f, style = Stroke(1.dp.toPx() * line))
}

/** 네온 테두리의 동그라미 */
fun DrawScope.neonCircle(center: Offset, radius: Float, strength: Float = 1f, fill: Color? = Neon.Fill) {
    val brush = Neon.brush(center.x - radius, center.x + radius)
    if (fill != null) drawCircle(fill, radius, center)
    for ((width, alpha) in GLOW) {
        drawCircle(brush, radius, center, alpha = (alpha * strength).coerceAtMost(1f), style = Stroke(width.dp.toPx()))
    }
    drawCircle(brush, radius, center, style = Stroke(3.dp.toPx()))
    drawCircle(Color.White, radius, center, alpha = 0.75f, style = Stroke(1.dp.toPx()))
}

/** 네온 선으로 그린 그림 (아이콘) */
fun DrawScope.neonPath(path: Path, bounds: Rect, strokeDp: Float = 2.6f, strength: Float = 1f) {
    val brush = Neon.brush(bounds.left, bounds.right)
    for ((width, alpha) in GLOW) {
        drawPath(path, brush, alpha = (alpha * strength).coerceAtMost(1f), style = Stroke((width * 0.6f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(path, brush, style = Stroke(strokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White, alpha = 0.7f, style = Stroke((strokeDp * 0.35f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 네온 테두리의 둥근 네모를 바탕으로 깐다 */
fun Modifier.neonBox(radius: Dp = 18.dp, strength: Float = 1f, fill: Color? = Neon.Fill, muted: Boolean = false): Modifier =
    drawBehind { neonRoundRect(Offset.Zero, size, radius.toPx(), strength, fill, muted = muted) }

/** 옆에서 본 차 (지붕 선 + 바퀴 둘 + 바닥 선). box = 그릴 자리 */
fun carIconPath(box: Rect): Path {
    fun x(v: Float) = box.left + box.width * v
    fun y(v: Float) = box.top + box.height * v
    return Path().apply {
        // 차체 윤곽: 뒤 범퍼 → 트렁크 → 지붕 → 보닛 → 앞 범퍼
        moveTo(x(0.10f), y(0.66f))
        quadraticTo(x(0.02f), y(0.66f), x(0.03f), y(0.56f))
        quadraticTo(x(0.04f), y(0.44f), x(0.14f), y(0.41f))
        quadraticTo(x(0.24f), y(0.38f), x(0.32f), y(0.29f))
        quadraticTo(x(0.42f), y(0.20f), x(0.56f), y(0.22f))
        quadraticTo(x(0.68f), y(0.24f), x(0.76f), y(0.36f))
        quadraticTo(x(0.92f), y(0.38f), x(0.97f), y(0.50f))
        quadraticTo(x(1.00f), y(0.66f), x(0.90f), y(0.66f))
        // 바퀴 사이 바닥 선
        moveTo(x(0.36f), y(0.66f))
        lineTo(x(0.64f), y(0.66f))
        // 바퀴
        addOval(Rect(Offset(x(0.23f), y(0.66f)), box.width * 0.085f))
        addOval(Rect(Offset(x(0.77f), y(0.66f)), box.width * 0.085f))
    }
}

/** 말풍선 (둥근 풍선 + 왼쪽 아래 꼬리) */
fun bubbleIconPath(box: Rect): Path {
    fun x(v: Float) = box.left + box.width * v
    fun y(v: Float) = box.top + box.height * v
    return Path().apply {
        // 꼬리 끝에서 시작해 풍선을 한 바퀴 돌아 꼬리로 돌아온다
        moveTo(x(0.12f), y(0.90f))
        quadraticTo(x(0.22f), y(0.80f), x(0.20f), y(0.70f))
        cubicTo(x(-0.04f), y(0.52f), x(0.10f), y(0.10f), x(0.52f), y(0.12f))
        cubicTo(x(1.06f), y(0.12f), x(1.08f), y(0.78f), x(0.56f), y(0.80f))
        quadraticTo(x(0.44f), y(0.80f), x(0.36f), y(0.76f))
        quadraticTo(x(0.26f), y(0.88f), x(0.12f), y(0.90f))
    }
}

/** 네온 동그라미 안에 그림 하나가 든 작은 버튼 (도로 화면 오른쪽 위) */
@Composable
fun NeonIconButton(icon: (Rect) -> Path, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    Canvas(
        modifier
            .size(size)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        val radius = this.size.minDimension / 2 - 8.dp.toPx()
        neonCircle(center, radius)
        val half = radius * 0.62f
        val box = Rect(center.x - half, center.y - half, center.x + half, center.y + half)
        neonPath(icon(box), box, strokeDp = 2.2f)
    }
}
