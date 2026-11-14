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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
import com.roadtalk.app.location.LocationGateway
import com.roadtalk.app.map.OsmBuildings
import com.roadtalk.app.net.Geo
import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.RoadMessage
import com.roadtalk.app.ui.scene3d.Camera3D
import com.roadtalk.app.ui.scene3d.CarLook
import com.roadtalk.app.ui.scene3d.Scene3DView
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
private const val CAR_HEIGHT_M = 1.6f
/** 이름표를 차 꽁무니에서 얼마나 뒤에 둘지 (m) */
private const val TAG_GAP_M = 0.7f
/**
 * 앞·옆의 차는 실제 위치에 그린다 (옆길에 있으면 옆길 자리에). 다만 이보다 먼 차는 3D 화면에서는 안개 너머라 이 자리까지 당겨 그린다 (m).
 * 지도에는 언제나 실제 위치에 점으로 나온다
 */
private const val MAX_SHOWN_FRONT_M = 260f
private const val MAX_SHOWN_SIDE_M = 260f
private val SKY_COLOR = Color(0xFFECEEF2)
private val TAG_BACKGROUND = Color(0xCC1B2230)
/**
 * 내 차 뒤 위에서 본 3D 도로 화면 + 말하기 버튼.
 * 실제 주변 풍경이 아니라 Car-ind CHAT 을 켠 차만, GPS 로 계산한 상대 위치 벡터(앞뒤·좌우 m) 자리에 그린다.
 * 3D 그림은 [Scene3DView] (OpenGL) 가 그리고, 그 위에 차 이름표와 말풍선을 같은 카메라([Camera3D])로 자리를 계산해 얹는다.
 *  - 차를 탭: 그 차를 받는 사람으로 선택 (파란 빛 고리). 말할 때 대상을 말하지 않으면 이 차에게 간다
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
    myType: Int,
    mySkin: Int,
    myFix: LocationGateway.Fix?,
    buildings: OsmBuildings.Area?,
    onMyCarTap: () -> Unit,
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
    // 기본은 가깝게(뒤 7m 까지) 보여주고, 그보다 뒤에 있는 차가 있으면 그 차가 들어올 만큼만 카메라를 부드럽게 물린다
    val rangeSteps = remember { RangeSteps() }
    val (_, targetRear) = rangeSteps.update(cars)
    val rearM by animateFloatAsState(targetRear, animationSpec = tween(600), label = "roadRear")
    val currentRear by rememberUpdatedState(rearM)
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 시연 자동화(tools/scenario)가 '화면에서 그 차가 지금 어디 그려져 있는지' 알 수 있게 0.4초마다 로그로 남긴다
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            if (canvasSize.width == 0) continue
            val camera = Camera3D(canvasSize.width.toFloat(), canvasSize.height.toFloat(), currentRear)
            currentCars.forEach { car ->
                val (f, l) = placement(car, currentRear)
                val c = camera.project(l, CAR_HEIGHT_M / 2, -f) ?: return@forEach
                Log.d(UI_LOG_TAG, "car ${Geo.plateKey(car.plate)} ${(canvasOrigin.x + c.first).toInt()} ${(canvasOrigin.y + c.second).toInt()}")
            }
        }
    }
    val currentOnSelectCar by rememberUpdatedState(onSelectCar)
    val currentOnSelectDirection by rememberUpdatedState(onSelectDirection)
    val currentOnMyCarTap by rememberUpdatedState(onMyCarTap)
    val context = LocalContext.current
    val sceneView = remember { Scene3DView(context) }
    DisposableEffect(sceneView) {
        sceneView.onResume()
        onDispose { sceneView.onPause() }
    }
    // 지도에서 받은 건물·도로·횡단보도와 같은 기준점에서 본 내 위치
    val ego = if (buildings != null && myFix != null) {
        Scene3DView.Ego(
            east = OsmBuildings.east(myFix.longitude, buildings.originLat, buildings.originLng),
            north = OsmBuildings.north(myFix.latitude, buildings.originLat),
            headingDeg = myFix.heading,
            speedMps = myFix.speedMps ?: 0f,
            atMillis = myFix.timestampMillis,
        )
    } else null
    SideEffect {
        sceneView.frame = Scene3DView.Frame(
            cars = cars.map { car ->
                val (f, l) = placement(car, rearM)
                Scene3DView.Car(
                    lateral = l,
                    forward = f,
                    headingDeg = car.relativeHeadingDeg ?: 0f,
                    type = car.carType ?: CarLook.typeOf(car.plate),
                    skin = car.skin ?: CarLook.defaultSkinOf(car.plate),
                    selected = car.plate == selectedPlate,
                )
            },
            myType = myType,
            mySkin = mySkin,
            rearM = rearM,
            speedMps = myFix?.speedMps ?: 0f,
            city = buildings?.city,
            roads = buildings?.roads.orEmpty(),
            crossings = buildings?.crossings.orEmpty(),
            ego = ego,
        )
    }

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RoadColors.Surface)
            .padding(16.dp)
    ) {
        Text("내 주변 Car-ind CHAT 차량 · ${cars.size}대", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RoadColors.TextPrimary)
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
                .background(SKY_COLOR)
        ) {
            AndroidView(factory = { sceneView }, modifier = Modifier.fillMaxSize())
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
                            val camera = Camera3D(size.width.toFloat(), size.height.toFloat(), currentRear)
                            fun distanceTo(car: NearbyCar): Float {
                                val (f, l) = placement(car, currentRear)
                                val c = camera.project(l, CAR_HEIGHT_M / 2, -f) ?: return Float.MAX_VALUE
                                return hypot(c.first - up.position.x, c.second - up.position.y)
                            }
                            val hit = currentCars.minByOrNull { distanceTo(it) }?.takeIf { distanceTo(it) <= 72.dp.toPx() }
                            // 내 차를 누르면 '내 차 고르기' 를 연다 (더 가까운 다른 차가 있으면 그 차 선택이 먼저)
                            val me = camera.project(0f, CAR_HEIGHT_M / 2, 0f)
                            val toMe = if (me == null) Float.MAX_VALUE else hypot(me.first - up.position.x, me.second - up.position.y)
                            if (toMe <= 64.dp.toPx() && (hit == null || toMe < distanceTo(hit))) {
                                currentOnMyCarTap()
                            } else if (hit != null) {
                                currentOnSelectCar(hit.plate)
                            } else {
                                // 빈 도로: 그 방향 칸의 차를 선택
                                val (forward, lateral) = camera.ground(up.position.x, up.position.y) ?: return@awaitEachGesture
                                currentOnSelectDirection(Geo.positionOf(forward, lateral))
                            }
                        }
                    }
            ) {
                val camera = Camera3D(size.width, size.height, rearM)
                // 이름표는 차 꽁무니 바로 뒤 바닥 자리에 (화면에서는 차 아래)
                camera.project(0f, 0f, CAR_LENGTH_M / 2 + TAG_GAP_M)?.let {
                    drawTag(textMeasurer, "내 차 · 눌러서 꾸미기", Offset(it.first, it.second), RoadColors.Accent, Color.White, bold = true)
                }
                cars.forEach { car ->
                    val (f, l) = placement(car, rearM)
                    val clipped = car.forwardMeters != null && car.distanceMeters >= 0 &&
                        (abs(car.forwardMeters - f) > 0.5f || abs((car.lateralMeters ?: 0f) - l) > 0.5f)
                    val at = camera.project(l, 0f, -f + CAR_LENGTH_M / 2 + TAG_GAP_M) ?: return@forEach
                    // 화면 밖에 있는 차는 이름표도 그리지 않는다 (뒤쪽 가장자리로 당겨 그린 차는 화면 안이라 그대로 나온다)
                    if (at.first < 0f || at.first > size.width || at.second < 0f || at.second > size.height) return@forEach
                    val isSelected = car.plate == selectedPlate
                    drawTag(
                        textMeasurer,
                        if (clipped) "${car.position.label} ${arrowFor(car)}" else car.position.label,
                        Offset(at.first, at.second),
                        if (isSelected) RoadColors.Accent else TAG_BACKGROUND,
                        Color.White,
                        bold = isSelected,
                    )
                }
                // 말풍선은 크게 그리므로 이름표에 가려지지 않게 마지막에, 차 지붕 위에 얹는다
                cars.forEach { car ->
                    val key = Geo.plateKey(car.plate)
                    val isIncoming = incoming != null && Geo.plateKey(incoming.fromPlate) == key
                    val isSent = sent != null && Geo.plateKey(sent.toPlate) == key
                    if (!isIncoming && !isSent) return@forEach
                    val (f, l) = placement(car, rearM)
                    val top = camera.project(l, CAR_HEIGHT_M + 0.6f, -f) ?: return@forEach
                    val bubbleAt = Offset(top.first, top.second - 14.dp.toPx())
                    if (isIncoming) drawBubble(textMeasurer, incoming!!.text, bubbleAt, RoadColors.Warn, Color.Black)
                    else drawBubble(textMeasurer, "보냄 · ${sent!!.text}", bubbleAt, RoadColors.Accent, Color.White)
                }
                if (cars.isEmpty()) {
                    drawTag(
                        textMeasurer, "근처에 Car-ind CHAT 차가 없어요",
                        Offset(size.width / 2, size.height * 0.16f), TAG_BACKGROUND, Color.White, bold = false,
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

/** 차를 그릴 (앞뒤, 좌우) m. 벡터를 모르면 칸의 대표 위치, 너무 멀면 보이는 범위 끝으로 당겨서 */
private fun placement(car: NearbyCar, rearM: Float): Pair<Float, Float> {
    val f = car.forwardMeters
    val l = car.lateralMeters
    val (forward, lateral) = if (f != null && l != null && car.distanceMeters >= 0) f to l else nominal(car.position)
    return forward.coerceIn(-(rearM - CAR_LENGTH_M * 0.5f), MAX_SHOWN_FRONT_M) to lateral.coerceIn(-MAX_SHOWN_SIDE_M, MAX_SHOWN_SIDE_M)
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

/** 차 아래에 붙는 작은 이름표 (둥근 바탕 + 글자). 도로 색과 상관없이 읽히게 바탕을 깐다 */
private fun DrawScope.drawTag(textMeasurer: TextMeasurer, text: String, topCenter: Offset, background: Color, textColor: Color, bold: Boolean) {
    val layout = textMeasurer.measure(
        AnnotatedString(text),
        TextStyle(color = textColor, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
    )
    val padX = 10.dp.toPx()
    val padY = 4.dp.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (topCenter.x - w / 2).coerceIn(2.dp.toPx(), (size.width - w - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
    val top = topCenter.y.coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
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
