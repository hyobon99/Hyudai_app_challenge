package com.roadtalk.app.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.roadtalk.app.location.LocationGateway
import com.roadtalk.app.map.OsmBuildings
import com.roadtalk.app.net.Geo
import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.RoadMessage
import com.roadtalk.app.ui.scene3d.Camera3D
import com.roadtalk.app.ui.scene3d.CarLook
import com.roadtalk.app.ui.scene3d.Lanes
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

/** 화면 아래쪽에 보이는 뒤쪽 거리 (m). 내 차 주변을 크게 보도록 가깝게 잡는다. 카메라 거리는 이 값으로 고정이다 */
private const val REAR_M = 7f
private const val LANE_M = 3.5f
private const val CAR_LENGTH_M = 4.6f
private const val CAR_HEIGHT_M = 1.6f
/** 누른 자리에서 이만큼은 끌어야 방향이 정해진다 (그 안에서 놓으면 전송하지 않고 차 선택만) */
private const val DEAD_ZONE_DP = 44
/** 방향 메뉴에서 메시지가 놓이는 원의 반지름 */
private const val RADIAL_RADIUS_DP = 180
/**
 * 앞·옆의 차는 실제 위치에 그린다 (옆길에 있으면 옆길 자리에). 다만 이보다 먼 차는 3D 화면에서는 안개 너머라 이 자리까지 당겨 그린다 (m).
 * 지도에는 언제나 실제 위치에 점으로 나온다
 */
private const val MAX_SHOWN_FRONT_M = 260f
private const val MAX_SHOWN_SIDE_M = 260f
private val SKY_COLOR = Color(0xFFECEEF2)
/** 누른 차(plate)와 손가락 위치. anchor = 방향 메뉴를 그릴 중심 */
private data class DragState(val plate: String, val anchor: Offset, val start: Offset, val current: Offset) {
    /** 끈 방향이 가리키는 메시지 번호 (위쪽이 0, 시계 방향). 거의 안 움직였으면(취소 영역) null */
    fun sector(deadZonePx: Float, count: Int): Int? {
        val dx = current.x - start.x
        val dy = current.y - start.y
        if (count <= 0 || hypot(dx, dy) < deadZonePx) return null
        val deg = (Math.toDegrees(atan2(dx, -dy).toDouble()) + 360) % 360
        return (deg / (360.0 / count)).roundToInt() % count
    }
}

/**
 * 내 차 뒤 위에서 본 3D 도로 화면 + 말하기 버튼.
 * 실제 주변 풍경이 아니라 Car-ind CHAT 을 켠 차만, GPS 로 계산한 상대 위치 벡터(앞뒤·좌우 m) 자리에 그린다.
 * 3D 그림은 [Scene3DView] (OpenGL) 가 그리고, 그 위에 차 이름표와 말풍선을 같은 카메라([Camera3D])로 자리를 계산해 얹는다.
 *  - 차를 누른 채 끌기: 방향 메뉴(기본 메시지 함의 6개가 정육각형으로 놓임)가 뜨고, 놓은 방향의 메시지가 그 차에게 전송된다.
 *    메시지가 놓인 자리는 메시지 함 화면의 6칸과 같다 (1번이 위, 시계 방향)
 *  - 차를 짧게 탭: 그 차를 받는 사람으로 선택 (네온 고리). 말할 때 대상을 말하지 않으면 이 차에게 간다
 *  - 빈 도로를 탭: 그 자리가 속한 8방향 칸에서 가장 가까운 차를 선택
 *  - 주변 차는 내 길 위에 있으면 가장 가까운 차로의 가운데에 그린다 (GPS 오차 때문에 차선을 밟고 달리는 것처럼 보이지 않게)
 *  - 아래 버튼을 한 번 누르고 말하기: 음성을 인식하다가(STT SDK) 말을 멈추면 알아서 끝내고, 인식된 말이
 *    기본 메시지 함의 6개(chat/MessageCatalog.kt) 중 가장 가까운 것으로 바뀌어 전송된다
 */
@Composable
fun BirdEyePanel(
    cars: List<NearbyCar>,
    selectedPlate: String?,
    incoming: RoadMessage?,
    sent: RoadMessage?,
    sendFailed: Boolean,
    voice: VoiceGateway.State,
    myType: Int,
    mySkin: Int,
    myFix: LocationGateway.Fix?,
    buildings: OsmBuildings.Area?,
    onOpenGarage: () -> Unit,
    onOpenMessages: () -> Unit,
    onSelectCar: (String) -> Unit,
    onSelectDirection: (RelativePosition) -> Unit,
    messageSlots: List<String>,
    onSend: (plate: String, text: String) -> Unit,
    onTalk: () -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val context = LocalContext.current
    val sceneView = remember { Scene3DView(context) }
    // 차로에 맞추기 전의 실제 좌우 위치 (내 차로와 차로 수를 정하는 데 쓴다)
    val measuredCars = cars
    @Suppress("NAME_SHADOWING")
    val cars = rememberSmoothedCars(cars) { sceneView.laneCentre to sceneView.laneCount }
    // 위치가 계속 갱신돼도 탭 처리가 최신 위치를 보도록
    val currentCars by rememberUpdatedState(cars)
    // 카메라 거리는 언제나 같다 (내 차 뒤 7m 까지 보이는 가까운 뷰). 뒤쪽에 차가 있어도 카메라를 물리지 않고,
    // 화면 아래로 벗어나는 뒤차는 화면 맨 아래에 걸쳐 그린다 → placement
    val rearM = REAR_M
    val currentRear = REAR_M
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
    val currentSlots by rememberUpdatedState(messageSlots)
    val currentOnSend by rememberUpdatedState(onSend)
    /** 차를 누른 채 끄는 중이면 그 상태 (방향 메뉴를 그린다) */
    var drag by remember { mutableStateOf<DragState?>(null) }
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
            signals = buildings?.signals.orEmpty(),
            ego = ego,
            neighbours = measuredCars
                .filter { it.distanceMeters >= 0 && it.lateralMeters != null && abs(it.forwardMeters ?: 0f) <= NEIGHBOUR_RANGE_M }
                .flatMap { listOf(it.lateralMeters!!, it.relativeHeadingDeg ?: Float.NaN) }
                .toFloatArray(),
        )
    }

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RoadColors.Surface)
            .padding(16.dp)
    ) {
        Text("Car-ind Map", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RoadColors.TextPrimary, maxLines = 1)
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
                        val deadZone = DEAD_ZONE_DP.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val camera = Camera3D(size.width.toFloat(), size.height.toFloat(), currentRear)
                            fun centreOf(car: NearbyCar): Offset? {
                                val (f, l) = placement(car, currentRear)
                                return camera.project(l, CAR_HEIGHT_M / 2, -f)?.let { Offset(it.first, it.second) }
                            }
                            val hit = currentCars
                                .mapNotNull { car -> centreOf(car)?.let { car to it } }
                                .minByOrNull { (_, at) -> (at - down.position).getDistance() }
                                ?.takeIf { (_, at) -> (at - down.position).getDistance() <= 72.dp.toPx() }
                            if (hit == null) {
                                // 빈 도로: 탭하면 그 방향 칸의 차를 선택
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                val (forward, lateral) = camera.ground(up.position.x, up.position.y) ?: return@awaitEachGesture
                                currentOnSelectDirection(Geo.positionOf(forward, lateral))
                                return@awaitEachGesture
                            }
                            // 차를 눌렀다: 방향 메뉴를 띄우고, 손을 뗀 방향의 메시지를 보낸다 (거의 안 끌었으면 그 차를 선택만 한다)
                            down.consume()
                            val (car, carCentre) = hit
                            // 방향 메뉴가 화면 밖으로 잘리지 않게 중심을 안쪽으로 당긴다
                            val margin = min(250.dp.toPx(), min(size.width, size.height) / 2f)
                            val anchor = Offset(
                                carCentre.x.coerceIn(margin, (size.width - margin).coerceAtLeast(margin)),
                                carCentre.y.coerceIn(margin, (size.height - margin).coerceAtLeast(margin)),
                            )
                            var state = DragState(car.plate, anchor, down.position, down.position)
                            drag = state
                            var released = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                state = state.copy(current = change.position)
                                if (change.changedToUp()) {
                                    released = true
                                    break
                                }
                                if (!change.pressed) break
                                change.consume()
                                drag = state
                            }
                            drag = null
                            if (!released) return@awaitEachGesture
                            val sector = state.sector(deadZone, currentSlots.size)
                            if (sector == null) currentOnSelectCar(state.plate)
                            else currentSlots.getOrNull(sector)?.let { currentOnSend(state.plate, it) }
                        }
                    }
            ) {
                val camera = Camera3D(size.width, size.height, rearM)
                // 차에는 이름표를 붙이지 않는다 (내 차는 언제나 화면 가운데 아래, 받는 차는 차 아래 네온 고리로 표시)
                // 말풍선은 크게 그리므로 이름표에 가려지지 않게 마지막에, 차 지붕 위에 얹는다
                cars.forEach { car ->
                    val key = Geo.plateKey(car.plate)
                    val isIncoming = incoming != null && Geo.plateKey(incoming.fromPlate) == key
                    val isSent = sent != null && Geo.plateKey(sent.toPlate) == key
                    if (!isIncoming && !isSent) return@forEach
                    val (f, l) = placement(car, rearM)
                    val top = camera.project(l, CAR_HEIGHT_M + 0.6f, -f) ?: return@forEach
                    val bubbleAt = Offset(top.first, top.second - 14.dp.toPx())
                    if (isIncoming) drawBubble(textMeasurer, incoming!!.text, bubbleAt, strong = true)
                    else drawBubble(textMeasurer, "보냄 · ${sent!!.text}", bubbleAt, strong = false)
                }
                // 차를 누른 채 끄는 동안: 방향 메뉴
                drag?.let { drawRadialMenu(it, messageSlots, textMeasurer) }
            }
            // 전송 실패: 도로 화면 한가운데에 말하기 버튼과 같은 네온 상자로 알린다 (욕설·보낼 수 없는 말·받을 차 없음). 상자는 글자에 맞춘 크기
            if (sendFailed) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .neonBox(radius = 20.dp, strength = 2.2f)
                        .padding(horizontal = 26.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("전송에 실패했습니다", style = Neon.text(26, strong = true), maxLines = 1)
                }
            }
            // 오른쪽 위의 작은 동그란 버튼: 내 차 고르기, 메시지 함 (누르면 오른쪽 지도 자리에 그 화면이 뜬다)
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                NeonIconButton(::carIconPath, onOpenGarage)
                NeonIconButton(::bubbleIconPath, onOpenMessages)
            }
        }
        Spacer(Modifier.height(12.dp))
        TalkButton(voice, onTalk)
    }
}

/**
 * 말하기 버튼 (네온 상자). 운전 중에도 누르기 쉽게 크게 만든다.
 * 한 번 누르면 [onTap] → 음성 인식 시작. 말을 멈추면 알아서 끝나고 전송된다 (voice/Endpointer). 듣는 중에 한 번 더 누르면 바로 끝난다.
 * 평소에는 '음성으로 말하기' 만 보이고, 듣는 동안과 인식 직후에는 인식된 말을 아래에 작게 보여 준다.
 */
@Composable
private fun TalkButton(voice: VoiceGateway.State, onTap: () -> Unit) {
    val listening = voice.listen != VoiceGateway.ListenState.IDLE
    // 누른 직후 마이크가 준비되기 전에는 '준비 중…', 들을 수 있게 되면 '듣는 중…' (준비 전에 한 말은 인식되지 않는다)
    val label = when (voice.listen) {
        VoiceGateway.ListenState.IDLE -> "음성으로 말하기"
        VoiceGateway.ListenState.PREPARING -> "준비 중…"
        VoiceGateway.ListenState.LISTENING -> "듣는 중…"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(TALK_BUTTON_HEIGHT_DP.dp)
            .padding(8.dp)
            .neonBox(radius = 26.dp, strength = if (listening) 2.2f else 1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = Neon.text(34, strong = voice.listen == VoiceGateway.ListenState.LISTENING), maxLines = 1)
        if (voice.partial.isNotEmpty()) {
            Text(
                if (listening) voice.partial else "인식됨: ${voice.partial}",
                style = Neon.text(16, weight = FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private const val TALK_BUTTON_HEIGHT_DP = 132

/**
 * 상대 위치는 칼만 필터가 0.1초마다 계산해 준다 (net/CarTracker). 그 사이를 매 프레임 조금씩 따라가게 해서 화면 주사율로 매끈하게 그린다.
 * (시정수 0.1초. 위치·방향만 보간하고 나머지 값은 최신 것을 쓴다)
 * 내 길 위의 차는 좌우 위치를 가장 가까운 차로의 가운데로 맞춘다. road() = 지금 그려진 내 길의 (가운데 자리 m, 차로 수).
 * 차로를 바꿀 때는 옆 차로 가운데로 천천히(시정수 [LANE_CHANGE_SECONDS]) 옮겨 간다.
 */
@Composable
private fun rememberSmoothedCars(cars: List<NearbyCar>, road: () -> Pair<Float, Int>): List<NearbyCar> {
    val target by rememberUpdatedState(cars)
    var shown by remember { mutableStateOf(cars) }
    val laneByPlate = remember { HashMap<String, Int>() }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val k = 1f - exp(-dt / SMOOTH_SECONDS)
                val kLane = 1f - exp(-dt / LANE_CHANGE_SECONDS)
                val (roadCentre, lanes) = road()
                val previous = shown.associateBy { it.plate }
                val next = target.map { t ->
                    val kept = keepLane(t, roadCentre, lanes, laneByPlate)
                    previous[t.plate]?.let { p -> approach(p, kept, k, if (kept !== t) kLane else k) } ?: kept
                }
                if (laneByPlate.size > target.size) laneByPlate.keys.retainAll(target.mapTo(HashSet()) { it.plate })
                if (next != shown) shown = next
            }
        }
    }
    return shown
}

/** 내 길 위를 나란히 달리는 차면 좌우 위치를 그 차로의 가운데로 바꾼 것을, 아니면 그대로 돌려준다 */
private fun keepLane(car: NearbyCar, roadCentre: Float, lanes: Int, laneByPlate: MutableMap<String, Int>): NearbyCar {
    val lateral = car.lateralMeters
    if (lateral == null || car.distanceMeters < 0 || !Lanes.onRoad(lateral, car.relativeHeadingDeg, roadCentre, lanes)) {
        laneByPlate.remove(car.plate)
        return car
    }
    val lane = Lanes.laneOf(lateral, roadCentre, lanes, laneByPlate[car.plate])
    laneByPlate[car.plate] = lane
    return car.copy(lateralMeters = Lanes.centre(roadCentre, lanes, lane))
}

private fun approach(from: NearbyCar, to: NearbyCar, k: Float, kLateral: Float): NearbyCar {
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
    val l = if (abs(l1 - l0) < 0.02f) l1 else l0 + (l1 - l0) * kLateral
    return to.copy(forwardMeters = f, lateralMeters = l, relativeHeadingDeg = heading)
}

private const val UI_LOG_TAG = "RoadTalkUi"
/** 0.1초마다 오는 위치를 따라가는 빠르기. 갱신 간격과 같게 잡아 계단은 없애고 뒤처짐은 작게 (상대 속도 5m/s 에서 0.5m) */
private const val SMOOTH_SECONDS = 0.1f
/** 내 차로를 정할 때 참고하는 주변 차의 앞뒤 범위 (m) */
private const val NEIGHBOUR_RANGE_M = 120f
/** 차로에 맞춰 그리는 차가 옆 차로 가운데로 옮겨 가는 빠르기 (차로 변경이 순간이동처럼 보이지 않게) */
private const val LANE_CHANGE_SECONDS = 0.35f
/**
 * 차를 그릴 (앞뒤, 좌우) m. 벡터를 모르면 칸의 대표 위치, 너무 멀면 보이는 범위 끝으로 당겨서.
 * 화면은 내 차 뒤 [rearM] 까지, 앞은 [FRONT_SHOWN_M] 쯤까지만 보이므로, 그보다 멀리 있는 차는 화면 맨 아래·맨 위에 걸치도록 그린다
 * (보이지 않으면 고를 수도, 그 차가 보낸 말풍선을 볼 수도 없다)
 */
private fun placement(car: NearbyCar, rearM: Float): Pair<Float, Float> {
    val f = car.forwardMeters
    val l = car.lateralMeters
    val (forward, lateral) = if (f != null && l != null && car.distanceMeters >= 0) f to l else nominal(car.position)
    return forward.coerceIn(-(rearM - REAR_EDGE_M), FRONT_SHOWN_M) to lateral.coerceIn(-MAX_SHOWN_SIDE_M, MAX_SHOWN_SIDE_M)
}

/** 화면 위로 벗어나는 먼 앞차를 그리는 자리 (m): 화면 맨 위에 보이는 곳에서 차 한 대쯤 안쪽 */
private val FRONT_SHOWN_M = Camera3D(100f, 100f, REAR_M).frontM - 4f

/** 화면 아래로 벗어나는 뒤차를 화면 끝에서 이만큼 안쪽에 그린다 (m) */
private const val REAR_EDGE_M = 1f

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

/**
 * 차 위에 뜨는 말풍선: 검은 바탕의 둥근 네모에 네온 테두리, 네온 글자. 폭은 글자에 맞춰 좁게 잡는다.
 * strong = 받은 메시지 (더 진하게 빛난다). 화면 폭을 넘는 문구는 줄을 바꾼다
 */
private fun DrawScope.drawBubble(textMeasurer: TextMeasurer, text: String, bottomCenter: Offset, strong: Boolean) {
    val padX = 18.dp.toPx() * BUBBLE_SCALE
    val padY = 11.dp.toPx() * BUBBLE_SCALE
    val edge = 12.dp.toPx()
    val maxTextWidth = (size.width * 0.72f - padX * 2).coerceAtLeast(1f)
    val layout = textMeasurer.measure(
        AnnotatedString(text),
        Neon.text(BUBBLE_TEXT_SP, strong).copy(textAlign = TextAlign.Center),
        constraints = Constraints(maxWidth = maxTextWidth.toInt()),
        maxLines = 3,
    )
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (bottomCenter.x - w / 2).coerceIn(edge, (size.width - w - edge).coerceAtLeast(edge))
    val top = (bottomCenter.y - h).coerceAtLeast(edge)
    neonRoundRect(Offset(left, top), Size(w, h), 16.dp.toPx() * BUBBLE_SCALE, strength = if (strong) 1.6f else 1f, line = BUBBLE_SCALE)
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

/** 말풍선 글자 크기 (처음 34sp 에서 1.5배 줄이고, 다시 3/4 로 줄였다) */
private const val BUBBLE_TEXT_SP = 17
/** 말풍선의 여백·모서리·테두리 굵기에 곱하는 값 (처음 크기의 1/1.5 × 3/4 = 절반) */
private const val BUBBLE_SCALE = 0.5f

/**
 * 차를 누르고 있는 동안 뜨는 방향 메뉴 (네온). 기본 메시지 함의 말들을 위쪽(12시)부터 시계 방향으로 같은 각도 간격으로 놓는다
 * (6개면 정육각형의 꼭짓점: 메시지 함 화면의 6칸과 같은 자리). 손가락이 가리키는 방향의 말이 더 진하게 빛난다.
 */
private fun DrawScope.drawRadialMenu(drag: DragState, messages: List<String>, textMeasurer: TextMeasurer) {
    drawRect(Color.Black.copy(alpha = 0.62f))
    val deadZone = DEAD_ZONE_DP.dp.toPx()
    val radius = min(RADIAL_RADIUS_DP.dp.toPx(), min(size.width, size.height) / 2f - 40.dp.toPx())
    val active = drag.sector(deadZone, messages.size)
    val c = drag.anchor
    val step = 360.0 / messages.size.coerceAtLeast(1)
    fun vertex(i: Int): Offset {
        val rad = Math.toRadians(i * step)
        return Offset(c.x + (radius * sin(rad)).toFloat(), c.y - (radius * cos(rad)).toFloat())
    }
    // 가운데: 누른 자리. 끌기 전에는 '끌어서 선택', 방향이 정해지면 '놓으면 전송'
    neonCircle(c, deadZone, strength = if (active == null) 1.8f else 0.8f)
    drawCenteredText(
        textMeasurer, if (active == null) "끌어서 선택" else "놓으면 전송",
        Offset(c.x, c.y - 9.sp.toPx()),
        Neon.text(13, weight = FontWeight.Medium),
    )
    // 손가락이 끈 방향으로 뻗는 빛줄기
    if (active != null) {
        val to = vertex(active)
        val length = (to - c).getDistance().coerceAtLeast(1f)
        val from = c + (to - c) * (deadZone / length)
        val until = c + (to - c) * ((length - 34.dp.toPx()).coerceAtLeast(deadZone) / length)
        val brush = Neon.brush(minOf(from.x, until.x) - 1f, maxOf(from.x, until.x) + 1f)
        drawLine(brush, from, until, strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round, alpha = 0.25f)
        drawLine(brush, from, until, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    }
    messages.forEachIndexed { i, text ->
        val p = vertex(i)
        val selected = i == active
        val layout = textMeasurer.measure(AnnotatedString(text), Neon.text(if (selected) 19 else 16, strong = selected))
        val padX = 14.dp.toPx()
        val padY = 9.dp.toPx()
        val w = layout.size.width + padX * 2
        val h = layout.size.height + padY * 2
        // 가장자리에서 글자가 잘리지 않게 안쪽으로
        val edge = 8.dp.toPx()
        val left = (p.x - w / 2).coerceIn(edge, (size.width - w - edge).coerceAtLeast(edge))
        val top = (p.y - h / 2).coerceIn(edge, (size.height - h - edge).coerceAtLeast(edge))
        neonRoundRect(Offset(left, top), Size(w, h), 14.dp.toPx(), strength = if (selected) 2.6f else 0.9f, line = 0.8f)
        drawText(layout, topLeft = Offset(left + padX, top + padY))
    }
}

private fun DrawScope.drawCenteredText(textMeasurer: TextMeasurer, text: String, topCenter: Offset, style: TextStyle) {
    val layout = textMeasurer.measure(AnnotatedString(text), style)
    drawText(layout, topLeft = Offset(topCenter.x - layout.size.width / 2, topCenter.y))
}
