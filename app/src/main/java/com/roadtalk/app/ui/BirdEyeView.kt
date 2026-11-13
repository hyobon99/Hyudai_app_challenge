package com.roadtalk.app.ui

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadtalk.app.chat.PresetMessages
import com.roadtalk.app.net.Geo
import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.RoadMessage
import com.roadtalk.app.voice.VoiceGateway
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 화면에 보이는 범위 (m). 내 차 주변을 크게 보도록 좁게 잡고, 앞쪽을 더 많이 보도록 내 차를 가운데보다 아래에 둔다 */
private const val FRONT_M = 12f
private const val REAR_M = 7f
private const val LANE_M = 3.5f
private const val CAR_LENGTH_M = 4.6f
private const val CAR_WIDTH_M = 1.9f
/**
 * 위에서 내려다본(bird's-eye) 도로 화면 + 말하기 버튼.
 * 실제 주변 풍경이 아니라 Car-ind CHAT 을 켠 차만, GPS 로 계산한 상대 위치 벡터(앞뒤·좌우 m) 자리에 그린다.
 *  - 차를 탭: 그 차를 받는 사람으로 선택 (파란 테두리). 말할 때 대상을 말하지 않으면 이 차에게 간다
 *  - 빈 도로를 탭: 그 자리가 속한 8방향 칸에서 가장 가까운 차를 선택
 *  - 아래 버튼을 꾹 누른 채 말하기: 누르고 있는 동안 음성을 인식하고(STT SDK), 손을 떼면 인식된 말이
 *    정해진 메시지(chat/PresetMessages.kt) 중 가장 가까운 것으로 바뀌어 전송된다
 */
@Composable
fun BirdEyePanel(
    cars: List<NearbyCar>,
    selectedPlate: String?,
    incoming: RoadMessage?,
    sent: RoadMessage?,
    voice: VoiceGateway.State,
    onSelectCar: (String) -> Unit,
    onSelectDirection: (RelativePosition) -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    @Suppress("NAME_SHADOWING")
    val cars = rememberSmoothedCars(cars)
    // 위치가 계속 갱신돼도 탭 처리가 최신 위치를 보도록
    val currentCars by rememberUpdatedState(cars)
    // 기본은 크게(앞 12m·뒤 7m) 보여주고, 그보다 멀리 있는 차가 있으면 그 차가 들어올 만큼만 부드럽게 넓힌다
    val rangeSteps = remember { RangeSteps() }
    val (targetFront, targetRear) = rangeSteps.update(cars)
    val frontM by animateFloatAsState(targetFront, animationSpec = tween(600), label = "roadFront")
    val rearM by animateFloatAsState(targetRear, animationSpec = tween(600), label = "roadRear")
    val currentFront by rememberUpdatedState(frontM)
    val currentRear by rememberUpdatedState(rearM)
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 시연 자동화(tools/scenario)가 '화면에서 그 차가 지금 어디 그려져 있는지' 알 수 있게 0.4초마다 로그로 남긴다
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            if (canvasSize.width == 0) continue
            val scene = Scene(canvasSize.width.toFloat(), canvasSize.height.toFloat(), currentFront, currentRear)
            currentCars.forEach { car ->
                val c = scene.toScreen(placement(car, scene))
                Log.d(UI_LOG_TAG, "car ${Geo.plateKey(car.plate)} ${(canvasOrigin.x + c.x).toInt()} ${(canvasOrigin.y + c.y).toInt()}")
            }
        }
    }
    val currentOnSelectCar by rememberUpdatedState(onSelectCar)
    val currentOnSelectDirection by rememberUpdatedState(onSelectDirection)

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RoadColors.Surface)
            .padding(16.dp)
    ) {
        Text("내 주변 Car-ind CHAT 차량", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RoadColors.TextPrimary)
        Text(
            "받을 차를 누르고, 아래 버튼을 꾹 누른 채 말하세요",
            fontSize = 14.sp,
            color = RoadColors.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RoadColors.Background)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        canvasOrigin = it.positionOnScreen()
                        canvasSize = it.size
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            val scene = Scene(size.width.toFloat(), size.height.toFloat(), currentFront, currentRear)
                            val hitRadius = maxOf(32.dp.toPx(), scene.ppm * 2.6f)
                            val hit = currentCars
                                .map { it to scene.toScreen(placement(it, scene)) }
                                .minByOrNull { (_, p) -> hypot(p.x - up.position.x, p.y - up.position.y) }
                                ?.takeIf { (_, p) -> hypot(p.x - up.position.x, p.y - up.position.y) <= hitRadius }
                            if (hit != null) {
                                currentOnSelectCar(hit.first.plate)
                            } else {
                                // 빈 도로: 그 방향 칸의 차를 선택
                                val (forward, lateral) = scene.toMeters(up.position)
                                currentOnSelectDirection(Geo.positionOf(forward, lateral))
                            }
                        }
                    }
            ) {
                val scene = Scene(size.width, size.height, frontM, rearM)
                drawRoad(scene)
                // 내 차
                val me = scene.toScreen(0f to 0f)
                drawCar(me, 0f, scene.ppm, RoadColors.Accent, null)
                drawCenteredText(
                    textMeasurer, "내 차",
                    Offset(me.x, me.y + CAR_LENGTH_M * scene.ppm / 2 + 4.dp.toPx()),
                    TextStyle(color = RoadColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                cars.forEach { car ->
                    val (f, l) = placement(car, scene)
                    val clipped = car.forwardMeters != null && car.distanceMeters >= 0 &&
                        (abs(car.forwardMeters - f) > 0.5f || abs((car.lateralMeters ?: 0f) - l) > 0.5f)
                    val center = scene.toScreen(f to l)
                    val isSelected = car.plate == selectedPlate
                    drawCar(
                        center = center,
                        headingDeg = car.relativeHeadingDeg ?: 0f,
                        ppm = scene.ppm,
                        body = if (clipped) RoadColors.SurfaceHigh.copy(alpha = 0.6f) else RoadColors.SurfaceHigh,
                        outline = if (isSelected) RoadColors.Accent else null,
                    )
                    // 선택 테두리가 있으면 그만큼 글자를 더 바깥으로
                    val half = CAR_LENGTH_M * scene.ppm / 2 + (if (isSelected) 12.dp.toPx() else 0f)
                    val distance = if (car.distanceMeters < 0) "거리 미확인" else "${car.distanceMeters}m"
                    drawCenteredText(
                        textMeasurer,
                        if (clipped) "${car.position.label} · $distance ${arrowFor(car)}" else "${car.position.label} · $distance",
                        Offset(center.x, center.y + half + 4.dp.toPx()),
                        TextStyle(
                            color = if (isSelected) RoadColors.Accent else RoadColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
                // 말풍선은 크게 그리므로 다른 차에 가려지지 않게 차를 다 그린 뒤에 얹는다
                cars.forEach { car ->
                    val key = Geo.plateKey(car.plate)
                    val isIncoming = incoming != null && Geo.plateKey(incoming.fromPlate) == key
                    val isSent = sent != null && Geo.plateKey(sent.toPlate) == key
                    if (!isIncoming && !isSent) return@forEach
                    val center = scene.toScreen(placement(car, scene))
                    val half = CAR_LENGTH_M * scene.ppm / 2 + (if (car.plate == selectedPlate) 12.dp.toPx() else 0f)
                    val bubbleAt = Offset(center.x, center.y - half - 18.dp.toPx())
                    if (isIncoming) drawBubble(textMeasurer, incoming!!.text, bubbleAt, RoadColors.Warn, Color.Black)
                    else drawBubble(textMeasurer, "보냄 · ${sent!!.text}", bubbleAt, RoadColors.Accent, Color.White)
                }
                if (cars.isEmpty()) {
                    drawCenteredText(
                        textMeasurer, "근처에 Car-ind CHAT 차가 없어요",
                        Offset(size.width / 2, size.height * 0.2f),
                        TextStyle(color = RoadColors.TextSecondary, fontSize = 15.sp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TalkButton(voice, onTalkStart, onTalkEnd)
    }
}

/**
 * 꾹 누르고 있는 동안만 듣는 말하기 버튼. 운전 중에도 누르기 쉽게 크게 만든다.
 * 누르면 [onStart] (음성 인식 시작), 손을 떼거나 버튼 밖으로 벗어나면 [onEnd] (인식 끝 → 전송).
 */
@Composable
private fun TalkButton(voice: VoiceGateway.State, onStart: () -> Unit, onEnd: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentStart by rememberUpdatedState(onStart)
    val currentEnd by rememberUpdatedState(onEnd)
    val listening = pressed || voice.listen != VoiceGateway.ListenState.IDLE
    Column(
        Modifier
            .fillMaxWidth()
            .height(TALK_BUTTON_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (pressed) RoadColors.Error else RoadColors.Accent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    currentStart()
                    waitForUpOrCancellation()
                    pressed = false
                    currentEnd()
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (pressed) "듣는 중… 말하고 손을 떼세요" else "🎤 꾹 누르고 말하기",
            fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
        )
        Text(
            when {
                voice.partial.isNotEmpty() -> if (listening) voice.partial else "인식됨: ${voice.partial}"
                pressed -> "예: 뒷차한테 고맙다고 해줘"
                else -> "정해진 인사 중 가장 가까운 말이 전송돼요"
            },
            fontSize = 17.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val TALK_BUTTON_HEIGHT_DP = 132

/**
 * 상대 위치는 칼만 필터가 0.1초마다 계산해 준다 (net/CarTracker). 그 사이를 매 프레임 조금씩 따라가게 해서 화면 주사율로 매끈하게 그린다.
 * (시정수 0.1초. 위치·방향만 보간하고 나머지 값은 최신 것을 쓴다)
 */
@Composable
private fun rememberSmoothedCars(cars: List<NearbyCar>): List<NearbyCar> {
    val target by rememberUpdatedState(cars)
    var shown by remember { mutableStateOf(cars) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val k = 1f - exp(-dt / SMOOTH_SECONDS)
                val previous = shown.associateBy { it.plate }
                val next = target.map { t -> previous[t.plate]?.let { p -> approach(p, t, k) } ?: t }
                if (next != shown) shown = next
            }
        }
    }
    return shown
}

private fun approach(from: NearbyCar, to: NearbyCar, k: Float): NearbyCar {
    val f0 = from.forwardMeters
    val l0 = from.lateralMeters
    val f1 = to.forwardMeters
    val l1 = to.lateralMeters
    if (f0 == null || l0 == null || f1 == null || l1 == null) return to
    val h0 = from.relativeHeadingDeg
    val h1 = to.relativeHeadingDeg
    val heading = if (h0 != null && h1 != null) {
        var d = (h1 - h0) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        h0 + d * k
    } else h1
    // 거의 다 왔으면 목표값으로 맞춰서 더 이상 다시 그리지 않게 한다
    val f = if (abs(f1 - f0) < 0.02f) f1 else f0 + (f1 - f0) * k
    val l = if (abs(l1 - l0) < 0.02f) l1 else l0 + (l1 - l0) * k
    return to.copy(forwardMeters = f, lateralMeters = l, relativeHeadingDeg = heading)
}

private const val UI_LOG_TAG = "RoadTalkUi"
/** 0.1초마다 오는 위치를 따라가는 빠르기. 갱신 간격과 같게 잡아 계단은 없애고 뒤처짐은 작게 (상대 속도 5m/s 에서 0.5m) */
private const val SMOOTH_SECONDS = 0.1f
/** 화면을 넓히는 한계 (m). 이보다 먼 차는 가장자리에 흐리게 붙는다 */
private const val MAX_FRONT_M = 30f
private const val MAX_REAR_M = 22f
/** 화면 범위를 넓히고 좁히는 단위 (앞 12 → 18 → 24 → 30m, 뒤 7 → 12 → 17 → 22m) */
private const val FRONT_STEP_M = 6f
private const val REAR_STEP_M = 5f
private const val RANGE_SLACK_M = 2f

/**
 * 근처 차가 모두 들어오도록 화면에 보이는 앞·뒤 범위 (m) 를 정한다. 기본 범위보다 좁아지지는 않는다.
 * 차가 조금 움직일 때마다 배율이 따라 바뀌면 화면 전체가 출렁여 보이므로, 범위는 몇 m 단계로만 바꾸고
 * 다시 좁힐 때는 여유([RANGE_SLACK_M])가 생긴 뒤에 좁힌다.
 */
private class RangeSteps {
    private var front = FRONT_M
    private var rear = REAR_M

    fun update(cars: List<NearbyCar>): Pair<Float, Float> {
        var needFront = FRONT_M
        var needRear = REAR_M
        val margin = CAR_LENGTH_M * 0.9f
        for (car in cars) {
            val f = car.forwardMeters ?: continue
            if (car.distanceMeters < 0) continue
            if (f >= 0 && f + margin <= MAX_FRONT_M) needFront = maxOf(needFront, f + margin)
            if (f < 0 && -f + margin <= MAX_REAR_M) needRear = maxOf(needRear, -f + margin)
        }
        front = step(front, needFront, FRONT_M, MAX_FRONT_M, FRONT_STEP_M)
        rear = step(rear, needRear, REAR_M, MAX_REAR_M, REAR_STEP_M)
        return front to rear
    }

    private fun step(current: Float, needed: Float, base: Float, max: Float, stepM: Float): Float {
        var range = current
        while (range < needed && range < max) range = (range + stepM).coerceAtMost(max)
        while (range - stepM >= base && needed < range - stepM - RANGE_SLACK_M) range -= stepM
        return range
    }
}

/** 화면 크기 ↔ 미터 변환 */
/** frontM / rearM = 지금 보이는 범위 (m). 기본은 앞 12m · 뒤 7m, 멀리 있는 차가 있으면 그쪽만 넓어진다 */
private class Scene(val width: Float, val height: Float, val frontM: Float = FRONT_M, val rearM: Float = REAR_M) {
    /** 1m 가 몇 px 인지. 세로 범위(앞 12m + 뒤 7m)에 맞추고, 가로는 패널 폭만큼 전부 도로로 채운다 (여백 없음) */
    val ppm = height / (frontM + rearM)
    /** 내 차 중심에서 화면 좌우 끝까지의 거리 (m) */
    val halfWidthM = width / 2 / ppm
    private val cx = width / 2
    private val cy = height * frontM / (frontM + rearM)

    fun toScreen(fl: Pair<Float, Float>) = Offset(cx + fl.second * ppm, cy - fl.first * ppm)
    fun toMeters(p: Offset) = (cy - p.y) / ppm to (p.x - cx) / ppm
}

/** 차를 그릴 (앞뒤, 좌우) m. 벡터를 모르면 칸의 대표 위치, 화면 밖이면 가장자리로 당겨서 */
private fun placement(car: NearbyCar, scene: Scene): Pair<Float, Float> {
    val f = car.forwardMeters
    val l = car.lateralMeters
    val (forward, lateral) = if (f != null && l != null && car.distanceMeters >= 0) f to l else nominal(car.position)
    val maxLateral = (scene.halfWidthM - CAR_WIDTH_M / 2 - 0.3f).coerceAtLeast(0f)
    return forward.coerceIn(-(scene.rearM - CAR_LENGTH_M * 0.8f), scene.frontM - CAR_LENGTH_M * 0.8f) to lateral.coerceIn(-maxLateral, maxLateral)
}

private fun nominal(position: RelativePosition): Pair<Float, Float> = when (position) {
    RelativePosition.FRONT -> 7.5f to 0f
    RelativePosition.REAR -> -5f to 0f
    RelativePosition.LEFT -> 0f to -LANE_M
    RelativePosition.RIGHT -> 0f to LANE_M
    RelativePosition.FRONT_LEFT -> 7.5f to -LANE_M
    RelativePosition.FRONT_RIGHT -> 7.5f to LANE_M
    RelativePosition.REAR_LEFT -> -5f to -LANE_M
    RelativePosition.REAR_RIGHT -> -5f to LANE_M
}

private fun arrowFor(car: NearbyCar): String {
    val f = car.forwardMeters ?: 0f
    val l = car.lateralMeters ?: 0f
    return when {
        abs(f) >= abs(l) -> if (f > 0) "↑" else "↓"
        else -> if (l > 0) "→" else "←"
    }
}

private fun DrawScope.drawRoad(scene: Scene) {
    // 패널 전체가 도로 (아스팔트). 차선은 내 차선을 기준으로 3.5m 마다 화면 끝까지 점선으로
    drawRect(RoadColors.Surface)
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f * scene.ppm, 4f * scene.ppm))
    var lateral = LANE_M / 2
    while (lateral < scene.halfWidthM) {
        for (side in listOf(-1f, 1f)) {
            val x = scene.toScreen(0f to side * lateral).x
            drawLine(
                color = RoadColors.TextSecondary.copy(alpha = 0.35f),
                start = Offset(x, 0f), end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
                pathEffect = dash,
                cap = StrokeCap.Butt,
            )
        }
        lateral += LANE_M
    }
}

private fun DrawScope.drawCar(center: Offset, headingDeg: Float, ppm: Float, body: Color, outline: Color?) {
    val l = CAR_LENGTH_M * ppm
    val w = CAR_WIDTH_M * ppm
    rotate(headingDeg, pivot = center) {
        val tl = Offset(center.x - w / 2, center.y - l / 2)
        if (outline != null) {
            val pad = 5.dp.toPx()
            drawRoundRect(
                outline.copy(alpha = 0.25f), Offset(tl.x - pad * 2, tl.y - pad * 2), Size(w + pad * 4, l + pad * 4),
                CornerRadius(w * 0.45f),
            )
            drawRoundRect(
                outline, Offset(tl.x - pad, tl.y - pad), Size(w + pad * 2, l + pad * 2),
                CornerRadius(w * 0.4f), style = Stroke(2.dp.toPx()),
            )
        }
        drawRoundRect(body, tl, Size(w, l), CornerRadius(w * 0.3f))
        // 지붕 (살짝 밝게) 와 앞·뒤 유리 (어둡게)
        drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(tl.x + w * 0.1f, tl.y + l * 0.36f), Size(w * 0.8f, l * 0.3f), CornerRadius(w * 0.1f))
        val glass = Color.Black.copy(alpha = 0.4f)
        drawRoundRect(glass, Offset(tl.x + w * 0.12f, tl.y + l * 0.2f), Size(w * 0.76f, l * 0.15f), CornerRadius(w * 0.1f))
        drawRoundRect(glass, Offset(tl.x + w * 0.12f, tl.y + l * 0.68f), Size(w * 0.76f, l * 0.11f), CornerRadius(w * 0.1f))
        // 헤드라이트(앞) · 테일라이트(뒤)
        val r = w * 0.1f
        val head = Color(0xFFFFF1B8)
        val tail = Color(0xFFFF5252)
        drawCircle(head, r, Offset(tl.x + w * 0.22f, tl.y + l * 0.05f))
        drawCircle(head, r, Offset(tl.x + w * 0.78f, tl.y + l * 0.05f))
        drawCircle(tail, r, Offset(tl.x + w * 0.22f, tl.y + l * 0.95f))
        drawCircle(tail, r, Offset(tl.x + w * 0.78f, tl.y + l * 0.95f))
    }
}

/** 주행 중에 한눈에 읽히도록 크게 그린다 (글자 60sp). 화면 폭을 넘는 문구는 줄을 바꾼다 */
private fun DrawScope.drawBubble(textMeasurer: TextMeasurer, text: String, bottomCenter: Offset, background: Color, textColor: Color) {
    val padX = 28.dp.toPx()
    val padY = 18.dp.toPx()
    val edge = 6.dp.toPx()
    val maxTextWidth = (size.width - edge * 2 - padX * 2).coerceAtLeast(1f)
    val layout = textMeasurer.measure(
        AnnotatedString(text),
        TextStyle(color = textColor, fontSize = BUBBLE_TEXT_SP.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        constraints = Constraints(maxWidth = maxTextWidth.toInt()),
        maxLines = 3,
    )
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (bottomCenter.x - w / 2).coerceIn(edge, (size.width - w - edge).coerceAtLeast(edge))
    val top = (bottomCenter.y - h).coerceAtLeast(edge)
    drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(32.dp.toPx()))
    // 말풍선 꼬리
    drawCircle(background, 14.dp.toPx(), Offset(bottomCenter.x.coerceIn(left + 40.dp.toPx(), left + w - 40.dp.toPx()), top + h + 6.dp.toPx()))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

/** 말풍선 글자 크기 (예전 15sp 의 4배) */
private const val BUBBLE_TEXT_SP = 60

private fun DrawScope.drawCenteredText(textMeasurer: TextMeasurer, text: String, topCenter: Offset, style: TextStyle) {
    val layout = textMeasurer.measure(AnnotatedString(text), style)
    drawText(layout, topLeft = Offset(topCenter.x - layout.size.width / 2, topCenter.y))
}
