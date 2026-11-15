package com.roadtalk.app.ui

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roadtalk.app.location.LocationGateway
import com.roadtalk.app.map.RoadRoute
import com.roadtalk.app.navi.NaviGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 오른쪽 내비게이션 패널.
 * 차량 순정 내비게이션 화면은 다른 앱 안에 넣을 수 없어서 (시스템 권한 필요), 지도는 앱이 직접 그리고
 * 그 위에 NaviHelper SDK 로 받은 순정 내비 정보를 흰 카드로 얹는다:
 *   왼쪽 위  다음 회전 (거리 + 안내문)
 *   왼쪽 아래 도착 정보 (도착 예정 시각, 남은 거리·시간, 목적지, 안내 종료)
 *   오른쪽 위 현재 도로 제한속도
 * 목적지와 제한속도는 순정 내비의 값이고, 남은 거리·시간과 다음 회전은 지도에 그린 길에서 잰 값이다 (길을 못 받았을 때만 순정 내비의 값)
 * 지도는 OpenStreetMap 자료를 '벡터 타일' 로 받아 MapLibre 로 그린다 → [RouteMap]
 *   내가 가는 방향이 항상 위로 오게 지도를 돌리는데, 가게 이름과 아이콘은 지도와 따로 그려져서 기울지 않고 똑바로 선다
 *   (글자가 박힌 그림 타일을 쓰면 지도를 돌릴 때 글자도 같이 돌아간다)
 * 지도를 길게 누르면 그 위치로 순정 내비에 경로 안내를 요청한다.
 * 지도에는 내 위치만 표시한다 (다른 Car-ind CHAT 차는 왼쪽 도로 화면에서 본다).
 * 안내 중에는 목적지까지 가는 길을 파란 선으로 그린다. 순정 내비가 고른 경로의 선은 SDK 가 주지 않아서
 * OpenStreetMap 도로 자료로 따로 찾은 길이다 → [RoadRoute]
 */
@Composable
fun NavPanel(
    myFix: LocationGateway.Fix?,
    navi: NaviGateway.State,
    onRequestRoute: (latitude: Double, longitude: Double) -> Unit,
    onCancelRoute: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var pendingDestination by remember { mutableStateOf<LatLng?>(null) }
    val naviConnected by rememberUpdatedState(navi.connected)
    val routeMap = remember {
        RouteMap(context) { place ->
            if (naviConnected) pendingDestination = place
            naviConnected
        }
    }
    DisposableEffect(routeMap) {
        routeMap.start()
        onDispose { routeMap.stop() }
    }
    val currentFix by rememberUpdatedState(myFix)
    // 목적지까지 가는 길. 목적지가 바뀌면 새로 찾고, 길에서 벗어나면 지금 위치에서 다시 찾는다
    val destinationLatitude = navi.destination?.latitude
    val destinationLongitude = navi.destination?.longitude
    var route by remember { mutableStateOf<List<RoadRoute.Point>>(emptyList()) }
    /** 길 찾기 서버에서 길을 받지 못했다 (그동안은 순정 내비가 알려 준 값을 그대로 보여 준다) */
    var routeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(destinationLatitude, destinationLongitude) {
        routeFailed = false
        if (destinationLatitude == null || destinationLongitude == null) {
            route = emptyList()
            RoadRoute.forget()
            return@LaunchedEffect
        }
        route = RoadRoute.remembered(destinationLatitude, destinationLongitude)
        while (true) {
            val fix = currentFix
            if (fix != null) {
                val near = RoadRoute.nearest(route, fix.latitude, fix.longitude)
                val away = near?.second
                // 달리는 중인데 경로가 내 뒤쪽으로 뻗어 있다 (길을 지나쳤거나, 처음 찾을 때 방향을 몰랐다)
                // (위치가 한 번에 멀리 튄 직후에는 속도와 방향이 엉터리라 믿지 않는다)
                val speed = fix.speedMps ?: 0f
                val heading = fix.heading.takeIf { fix.headingKnown && speed in 1f..MAX_TRUSTED_MPS }
                val wrongWay = near != null && heading != null && RoadRoute.against(route, fix.latitude, fix.longitude, heading)
                if (away == null || away > OFF_ROUTE_M || wrongWay) {
                    val found = withContext(Dispatchers.IO) {
                        RoadRoute.fetch(fix.latitude, fix.longitude, destinationLatitude, destinationLongitude, heading ?: fix.heading.takeIf { fix.headingKnown && speed <= MAX_TRUSTED_MPS })
                    }
                    if (found != null) route = found
                    routeFailed = found == null && route.isEmpty()
                    // 길 찾기 서버에 너무 자주 묻지 않는다
                    delay(if (found != null) ROUTE_ASK_AGAIN_MS else ROUTE_RETRY_MS)
                }
            }
            delay(ROUTE_CHECK_MS)
        }
    }
    // 카드에 적는 남은 거리 · 시간 · 다음 회전은 지도에 그린 길에서 잰다 (순정 내비의 값은 내비가 아는 내 위치 기준이라,
    // 에뮬레이터처럼 내비의 위치가 실제 위치와 다르면 지도와 맞지 않는다)
    val progress = remember(route, myFix) {
        val fix = myFix
        if (fix == null) null
        else RoadRoute.progress(route, fix.latitude, fix.longitude, RoadRoute.last?.takeIf { it.points === route }?.seconds)
    }
    LaunchedEffect(myFix, route, destinationLatitude, destinationLongitude) {
        routeMap.show(
            fix = myFix,
            destination = if (destinationLatitude != null && destinationLongitude != null) LatLng(destinationLatitude, destinationLongitude) else null,
            route = route,
        )
    }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(NavColors.MapBackdrop)
    ) {
        AndroidView(factory = { routeMap.view }, modifier = Modifier.fillMaxSize())

        // 왼쪽 위: 다음 회전, 또는 안내 중이 아닐 때의 상태
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            val turn = when {
                progress != null -> progress.toTurn()
                routeFailed -> navi.nextTurn
                else -> null
            }
            if (navi.isGuiding && turn != null) {
                TurnCard(turn)
            } else {
                StatusCard(
                    when {
                        !navi.connected -> navi.lastError ?: "순정 내비 연결 중…"
                        navi.isGuiding -> if (routeFailed) "경로 안내 중" else "경로를 찾는 중…"
                        navi.status == NavigationStatus.INITIALIZING -> "순정 내비 준비 중"
                        else -> "지도를 길게 누르면 그곳으로 안내를 시작해요"
                    }
                )
            }
        }

        // 오른쪽 위: 제한속도 표지판
        navi.speedLimit?.let { limit ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .shadow(6.dp, CircleShape)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(6.dp, NavColors.SignRed, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$limit", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NavColors.Text)
            }
        }

        // 왼쪽 아래: 도착 정보
        navi.destination?.let { destination ->
            val shown = when {
                progress != null -> destination.copy(remainMeters = progress.remainMeters, remainSeconds = progress.remainSeconds)
                routeFailed -> destination
                else -> null
            }
            if (shown != null) {
                ArrivalCard(
                    destination = shown,
                    onCancel = onCancelRoute,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                )
            }
        }

        if (myFix == null) {
            Text(
                "위치 확인 중…",
                color = NavColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Text(
            "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap",
            color = NavColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.85f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }


    pendingDestination?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingDestination = null },
            title = { Text("이 위치로 안내할까요?") },
            text = { Text("순정 내비게이션에 경로 안내를 요청해요.\n위도 %.5f, 경도 %.5f".format(point.latitude, point.longitude)) },
            confirmButton = {
                TextButton(onClick = {
                    onRequestRoute(point.latitude, point.longitude)
                    pendingDestination = null
                }) { Text("안내 시작") }
            },
            dismissButton = { TextButton(onClick = { pendingDestination = null }) { Text("취소") } },
        )
    }
}

/** 지도에 그린 길의 다음 회전을 안내 카드에 쓰는 꼴로 바꾼다. 더 꺾을 데가 없으면 목적지까지 남은 거리 */
private fun RoadRoute.Progress.toTurn(): NaviGateway.Turn {
    val next = turn ?: return NaviGateway.Turn(TurnType.TURN_ARRIVE, remainMeters, "")
    val type = when {
        next.uTurn -> TurnType.TURN_UTURN
        next.right -> TurnType.TURN_RIGHT
        else -> TurnType.TURN_LEFT
    }
    return NaviGateway.Turn(type, next.meters, "")
}

/** 경로에서 이만큼(m) 넘게 벗어나면 지금 위치에서 길을 다시 찾는다 */
private const val OFF_ROUTE_M = 30.0
/** 이보다 빠르게 움직인 것으로 계산되면 위치가 튄 것으로 보고, 그때의 진행 방향은 쓰지 않는다 (m/s, 시속 약 200km) */
private const val MAX_TRUSTED_MPS = 55f
/** 경로에서 벗어났는지 살피는 간격, 한 번 물어본 뒤 다시 물을 수 있을 때까지의 시간 (ms) */
private const val ROUTE_CHECK_MS = 2000L
private const val ROUTE_ASK_AGAIN_MS = 5000L
/** 길을 받지 못했을 때 다시 물을 때까지의 시간 (ms) */
private const val ROUTE_RETRY_MS = 15000L

/** 지도 위 흰 카드 색 (지도가 밝아서 패널 안에서는 어두운 글자를 쓴다) */
private object NavColors {
    val MapBackdrop = Color(0xFFE9EDF2)
    val Text = Color(0xFF14171F)
    val TextSecondary = Color(0xFF6F7785)
    val Accent = Color(0xFF2F6BFF)
    val SignRed = Color(0xFFE53935)
    val Divider = Color(0xFFE6E9EF)
}

@Composable
private fun WhiteCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(8.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
    ) { content() }
}

/** 다음 회전: 파란 네모 안 화살표 + 남은 거리 + 안내문 */
@Composable
private fun TurnCard(turn: NaviGateway.Turn) {
    WhiteCard(Modifier.widthIn(min = 300.dp, max = 440.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(NavColors.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(turnArrow(turn.type), fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(formatDistance(turn.meters), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = NavColors.Text)
                Text(
                    turn.description.ifBlank { turnLabel(turn.type) }.let { desc ->
                        val label = turnLabel(turn.type)
                        if (desc == label || desc.contains(label)) desc else "$label · $desc"
                    },
                    fontSize = 16.sp,
                    color = NavColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 도착 정보: 도착 예정 시각, 남은 거리·시간, 목적지 이름, 안내 종료 */
@Composable
private fun ArrivalCard(destination: NaviGateway.Destination, onCancel: () -> Unit, modifier: Modifier) {
    WhiteCard(modifier.widthIn(min = 320.dp, max = 460.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚑", fontSize = 22.sp, color = NavColors.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (destination.remainSeconds > 0) "${formatClock(destination.remainSeconds)} 도착" else "곧 도착",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavColors.Text,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDistance(destination.remainMeters)} · ${formatDuration(destination.remainSeconds)} 남음",
                    fontSize = 17.sp,
                    color = NavColors.Text,
                )
                Text(
                    "목적지 · ${destination.name}",
                    fontSize = 14.sp,
                    color = NavColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "안내 종료",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = NavColors.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(NavColors.Divider)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** 안내 중이 아닐 때 왼쪽 위에 짧게 보여 주는 상태 */
@Composable
private fun StatusCard(text: String) {
    WhiteCard {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(NavColors.Accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 15.sp, color = NavColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 지금부터 seconds 뒤의 시각을 "오후 4:25" 꼴로 */
private fun formatClock(seconds: Int): String =
    SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + seconds * 1000L))

private fun formatDistance(meters: Int): String = when {
    meters < 0 -> "-"
    meters < 1000 -> "${meters / 10 * 10} m"
    else -> "%.1f km".format(meters / 1000f)
}

private fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return when {
        seconds <= 0 -> "곧 도착"
        minutes < 1 -> "1분 미만"
        minutes < 60 -> "${minutes}분"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
}

private fun turnArrow(type: TurnType): String = when (type) {
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> "↱"
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> "↰"
    TurnType.TURN_KEEP_R, TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R,
    TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_AVOID_UNDER_R, TurnType.TURN_ENTER_OVER_R, TurnType.TURN_AVOID_OVER_R -> "↗"
    TurnType.TURN_KEEP_L, TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L,
    TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_AVOID_UNDER_L, TurnType.TURN_ENTER_OVER_L, TurnType.TURN_AVOID_OVER_L -> "↖"
    TurnType.TURN_UTURN -> "↶"
    TurnType.TURN_ARRIVE -> "⚑"
    TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
    TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
    TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12, TurnType.TURN_EXIT_ROUNDABOUT -> "⟳"
    else -> "↑"
}

private fun turnLabel(type: TurnType): String = when (type) {
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> "우회전"
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> "좌회전"
    TurnType.TURN_UTURN -> "유턴"
    TurnType.TURN_ARRIVE -> "목적지 도착"
    TurnType.TURN_TUNNEL -> "터널"
    TurnType.TURN_TOLL -> "요금소"
    TurnType.TURN_STRAIGHT -> "직진"
    else -> "경로 따라 주행"
}

/**
 * 지도 (MapLibre + OpenFreeMap 의 벡터 타일. 자료는 OpenStreetMap).
 * 내 위치를 가운데에 두고 내가 가는 방향이 위로 오게 돌린다. 그 위에 목적지까지 남은 길(파란 선), 목적지(빨간 점),
 * 내 차(파란 원 안 흰 화살표)를 그린다. 지도와 스타일은 조금 뒤에 준비되므로, 그 전에 받은 값은 기억해 두었다가 준비되면 그린다
 */
private class RouteMap(context: Context, onLongPress: (LatLng) -> Boolean) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var ready = false
    private var fix: LocationGateway.Fix? = null
    private var destination: LatLng? = null
    private var route: List<RoadRoute.Point> = emptyList()
    /** 처음 한 번은 움직이는 효과 없이 바로 내 위치로 간다 */
    private var centred = false

    init {
        // 지도에 쓰는 것(아래의 source 포함)을 만들기 전에 먼저 불러야 한다
        MapLibre.getInstance(context.applicationContext)
    }

    private val routeSource = GeoJsonSource(ROUTE)
    private val destinationSource = GeoJsonSource(DESTINATION)
    private val meSource = GeoJsonSource(ME)

    init {
        // textureMode: 패널의 둥근 모서리에 맞춰 지도를 잘라낼 수 있게 한다
        view = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
        view.onCreate(null)
        val density = context.resources.displayMetrics.density
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false   // 출처는 패널 오른쪽 아래에 직접 적는다
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            m.addOnMapLongClickListener { place -> onLongPress(place) }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                prepare(style, density)
                ready = true
                draw()
            }
        }
    }

    fun start() {
        view.onStart()
        view.onResume()
    }

    fun stop() {
        view.onPause()
        view.onStop()
        view.onDestroy()
    }

    fun show(fix: LocationGateway.Fix?, destination: LatLng?, route: List<RoadRoute.Point>) {
        this.fix = fix
        this.destination = destination
        this.route = route
        draw()
    }

    private fun prepare(style: Style, density: Float) {
        // 이름은 그곳 글자 그대로 쓴다 (한국에서는 한글). 기본 스타일은 로마자 표기를 함께 적어서 글자가 길어진다
        style.layers.forEach { layer ->
            if (layer is SymbolLayer && NAMED_LAYERS.any { layer.id.startsWith(it) }) {
                layer.setProperties(PropertyFactory.textField(Expression.get("name")))
            }
        }
        // 가게·시설 이름은 조금 크게, 작은 가게도 한 단계 일찍 보이게 한다
        style.layers.forEach { layer ->
            if (layer is SymbolLayer && layer.id.startsWith("poi")) {
                layer.setProperties(PropertyFactory.textSize(14f), PropertyFactory.iconSize(1.2f))
                if (layer.id == "poi_r20") layer.minZoom = 16f
            }
        }
        // 건물은 위에서 본 평면으로 그린다 (기본 스타일의 입체 건물은 위에서 보면 벽이 비스듬히 보여 지저분하다)
        if (style.getLayer("building-3d") != null) {
            val flat = FillLayer("building-flat", "openmaptiles").apply {
                sourceLayer = "building"
                minZoom = 14f
                setProperties(
                    PropertyFactory.fillColor(BUILDING),
                    PropertyFactory.fillOutlineColor(BUILDING_EDGE),
                )
            }
            style.addLayerAbove(flat, "building-3d")
            style.removeLayer("building-3d")
        }
        style.addImage(ARROW, arrowBitmap(density))
        style.addSource(routeSource)
        style.addSource(destinationSource)
        style.addSource(meSource)
        // 길은 도로 위 · 글자 아래에 그린다: 흰 테두리 위에 파란 선
        val casing = LineLayer("route-casing", ROUTE).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.WHITE),
            PropertyFactory.lineWidth(13f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val line = LineLayer("route-line", ROUTE).withProperties(
            PropertyFactory.lineColor(BLUE),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer && NAMED_LAYERS.any { name -> it.id.startsWith(name) } }?.id
        if (firstLabel != null) {
            style.addLayerBelow(casing, firstLabel)
            style.addLayerBelow(line, firstLabel)
        } else {
            style.addLayer(casing)
            style.addLayer(line)
        }
        // 목적지: 빨간 원 + 흰 테두리 + 가운데 흰 점
        style.addLayer(
            CircleLayer("destination-pin", DESTINATION).withProperties(
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleColor(RED),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
            )
        )
        style.addLayer(
            CircleLayer("destination-dot", DESTINATION).withProperties(
                PropertyFactory.circleRadius(4f),
                PropertyFactory.circleColor(android.graphics.Color.WHITE),
            )
        )
        // 내 차: 옅은 파란 원 + 파란 원 + 내가 가는 쪽을 가리키는 흰 화살표
        style.addLayer(
            CircleLayer("me-halo", ME).withProperties(
                PropertyFactory.circleRadius(34f),
                PropertyFactory.circleColor(BLUE),
                PropertyFactory.circleOpacity(0.2f),
            )
        )
        style.addLayer(
            CircleLayer("me-circle", ME).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(BLUE),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
            )
        )
        style.addLayer(
            SymbolLayer("me-arrow", ME).withProperties(
                PropertyFactory.iconImage(ARROW),
                PropertyFactory.iconRotate(Expression.get("heading")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        )
    }

    private fun draw() {
        val m = map ?: return
        if (!ready) return
        val me = fix
        val to = destination
        destinationSource.setGeoJson(
            if (to != null) FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(to.longitude, to.latitude)))
            else FeatureCollection.fromFeatures(emptyList())
        )
        if (me == null) return
        meSource.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(me.longitude, me.latitude)).apply { addNumberProperty("heading", me.heading) }
        )
        // 내 위치에서 목적지까지 남은 길만 그린다 (지나온 길은 그리지 않는다)
        val start = RoadRoute.nearest(route, me.latitude, me.longitude)?.first
        if (start != null && to != null) {
            val points = ArrayList<Point>(route.size - start + 1)
            points.add(Point.fromLngLat(me.longitude, me.latitude))
            for (i in start + 1 until route.size) points.add(Point.fromLngLat(route[i].longitude, route[i].latitude))
            points.add(Point.fromLngLat(to.longitude, to.latitude))
            routeSource.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points))))
        } else {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
        // 내 위치가 가운데, 내가 가는 방향이 위. 확대 정도는 사용자가 바꾼 대로 둔다
        val camera = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(me.latitude, me.longitude))
                .bearing(me.heading.toDouble())
                .zoom(if (centred) m.cameraPosition.zoom else START_ZOOM)
                .tilt(0.0)
                .build()
        )
        if (centred) m.easeCamera(camera, FOLLOW_MS, false) else m.moveCamera(camera)
        centred = true
    }

    /** 위를 향한 흰 화살표 (지도가 도는 만큼 같이 돌아서, 지도 위에서 내가 가는 쪽을 가리킨다) */
    private fun arrowBitmap(density: Float): Bitmap {
        val size = (26f * density).toInt().coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = size / 2f
        val s = size * 0.42f
        val path = Path().apply {
            moveTo(c, c - s)
            lineTo(c + s * 0.75f, c + s * 0.75f)
            lineTo(c, c + s * 0.35f)
            lineTo(c - s * 0.75f, c + s * 0.75f)
            close()
        }
        Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        return bitmap
    }

    private companion object {
        /** OpenFreeMap 의 무료 벡터 지도 스타일 (키 없이 쓴다. 자료: OpenStreetMap · OpenMapTiles) */
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val ROUTE = "route"
        const val DESTINATION = "destination"
        const val ME = "me"
        const val ARROW = "me-arrow-image"
        const val START_ZOOM = 17.0
        /** 위치는 1초에 한 번 오므로, 그 사이를 이 시간 동안 부드럽게 따라간다 (ms) */
        const val FOLLOW_MS = 950
        val BLUE = 0xFF2F6BFF.toInt()
        val RED = 0xFFE53935.toInt()
        val BUILDING = 0xFFD9D0C9.toInt()
        val BUILDING_EDGE = 0xFFC4B6AB.toInt()
        /** 장소·도로 이름을 적는 층들 (도로 번호 표지와 일방통행 화살표는 뺀다) */
        val NAMED_LAYERS = listOf("poi", "highway-name", "label_", "water", "airport")
    }
}
