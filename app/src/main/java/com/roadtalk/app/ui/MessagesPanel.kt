package com.roadtalk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadtalk.app.chat.MessageCatalog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * '메시지 함' 화면. 도로 화면의 말풍선 버튼을 누르면 오른쪽 지도 자리에 뜬다.
 *  - 왼쪽: 보낼 수 있는 말의 전체 목록 ([MessageCatalog.ALL]). 지금 기본 메시지 함에 든 말은 네온으로 빛나고,
 *    들지 않은 말은 채도를 뺀 회색빛이다
 *  - 오른쪽: 기본 메시지 함 6칸. 음성으로 말하면 이 6개 중 뜻이 가장 가까운 것이 전송된다
 * 바꾸는 법: 목록의 말을 바꾸고 싶은 칸 위로 끌어다 놓는다 (옆이나 비스듬히 끌면 바로 따라오고, 잠깐 누르고 있어도 집힌다.
 * 거의 위아래로 끌면 목록이 넘어간다). 이미 다른 칸에 있는 말을 놓으면 두 칸이 맞바뀐다.
 */
@Composable
fun MessagesPanel(
    slots: List<String>,
    onAssign: (slot: Int, message: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    /** 끌고 있는 말과 그 위치 (화면 전체 기준 px) */
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val slotBounds = remember { arrayOfNulls<Rect>(MessageCatalog.SLOT_COUNT) }
    val currentSlots by rememberUpdatedState(slots)
    val currentOnAssign by rememberUpdatedState(onAssign)
    val density = LocalDensity.current

    fun slotAt(position: Offset): Int? = slotBounds.indices.firstOrNull { slotBounds[it]?.contains(position) == true }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1322), Color(0xFF05080F))))
            .onGloballyPositioned { panelOrigin = it.positionInRoot() }
    ) {
        Row(Modifier.fillMaxSize().padding(18.dp)) {
            // ---- 왼쪽: 전체 목록
            Column(Modifier.weight(0.46f).fillMaxHeight()) {
                Text("메시지 목록", style = Neon.text(26))
                Spacer(Modifier.height(4.dp))
                Text("바꾸고 싶은 칸 위로 끌어다 놓으세요", fontSize = 13.sp, color = MessageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    items(MessageCatalog.ALL, key = { it }) { message ->
                        val inSlots = message in slots
                        var origin by remember { mutableStateOf(Offset.Zero) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .onGloballyPositioned { origin = it.positionInRoot() }
                                .neonBox(radius = 12.dp, strength = 2.4f, muted = !inSlots)
                                .pointerInput(message) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        // 옆이나 비스듬히 끌기 시작하면 바로 집는다. 거의 위아래로 끌면 목록이 넘어가고(null),
                                        // 가만히 누르고 있으면 길게 누른 것으로 보고 집는다
                                        var moved: PointerInputChange? = null
                                        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            moved = awaitTouchSlopOrCancellation(down.id) { change, over ->
                                                if (abs(over.x) >= abs(over.y) * GRAB_SLOPE) change.consume()
                                            }
                                            true
                                        }
                                        val begin = if (decided == null) down else moved ?: return@awaitEachGesture
                                        dragging = message
                                        dragAt = origin + begin.position
                                        val dropped = drag(begin.id) { change ->
                                            dragAt = origin + change.position
                                            change.consume()
                                        }
                                        if (dropped) slotAt(dragAt)?.let { slot -> currentOnAssign(slot, message) }
                                        dragging = null
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                message,
                                style = Neon.text(17, strong = inSlots, weight = if (inSlots) FontWeight.Bold else FontWeight.Medium, muted = !inSlots),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))

            // ---- 오른쪽: 기본 메시지 함 6칸
            Column(Modifier.weight(0.54f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("기본 메시지 함", style = Neon.text(26), modifier = Modifier.weight(1f))
                    Text(
                        "✕  지도로",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x66000000))
                            .border(1.5.dp, Neon.Cyan, RoundedCornerShape(50))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("음성으로 말하면 이 6개 중 가장 가까운 말이 전송돼요", fontSize = 13.sp, color = MessageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val slotWidth = maxWidth * 0.40f
                    val slotHeight = 74.dp
                    val radiusX = maxWidth * 0.29f
                    val radiusY = maxHeight * 0.34f
                    // 가운데: 말풍선 그림
                    Canvas(Modifier.size(96.dp)) {
                        val r = size.minDimension / 2 - 10.dp.toPx()
                        neonCircle(center, r)
                        val half = r * 0.6f
                        val box = Rect(center.x - half, center.y - half, center.x + half, center.y + half)
                        neonPath(bubbleIconPath(box), box)
                    }
                    for (index in 0 until MessageCatalog.SLOT_COUNT) {
                        val angle = Math.toRadians(60.0 * index)        // 0 = 위, 시계 방향
                        val dx = radiusX * sin(angle).toFloat()
                        val dy = -radiusY * cos(angle).toFloat()
                        val hovered = dragging != null && slotAt(dragAt) == index
                        Box(
                            Modifier
                                .offset(dx, dy)
                                .size(slotWidth, slotHeight)
                                .onGloballyPositioned { slotBounds[index] = it.boundsInRoot() }
                                .neonBox(radius = 18.dp, strength = if (hovered) 3.2f else 1f)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                slots.getOrElse(index) { "" },
                                style = Neon.text(16, strong = hovered).copy(textAlign = TextAlign.Center),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 끌고 있는 말 (손가락을 따라다닌다)
        dragging?.let { message ->
            val local = dragAt - panelOrigin
            val w = with(density) { 220.dp.toPx() }
            val h = with(density) { 46.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((local.x - w / 2).roundToInt(), (local.y - h / 2).roundToInt()) }
                    .size(220.dp, 46.dp)
                    .neonBox(radius = 12.dp, strength = 3f),
                contentAlignment = Alignment.Center,
            ) {
                Text(message, style = Neon.text(17, strong = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 끄는 방향의 가로 움직임이 세로 움직임의 이 배수보다 크면 말을 집는다 (그보다 가파르게 위아래로 끌면 목록 넘기기) */
private const val GRAB_SLOPE = 0.5f

private object MessageColors {
    val Dim = Color(0xFF8FA3BF)
}
