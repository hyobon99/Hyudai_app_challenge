package com.roadtalk.app.ui

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
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
import com.roadtalk.app.navi.NaviGateway
import com.roadtalk.app.net.NearbyCar
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 오른쪽 내비게이션 패널.
 * 차량 순정 내비게이션 화면은 다른 앱 안에 넣을 수 없어서 (시스템 권한 필요), 지도는 OpenStreetMap 타일(osmdroid)로 직접 그리고
 * 그 위에 NaviHelper SDK 로 받은 순정 내비 정보를 흰 카드로 얹는다:
 *   왼쪽 위  다음 회전 (거리 + 안내문)
 *   왼쪽 아래 도착 정보 (도착 예정 시각, 남은 거리·시간, 목적지, 안내 종료)
 *   오른쪽 위 현재 도로 제한속도
 * 지도를 길게 누르면 그 위치로 순정 내비에 경로 안내를 요청한다.
 */
@Composable
fun NavPanel(
    myFix: LocationGateway.Fix?,
    cars: List<NearbyCar>,
    navi: NaviGateway.State,
    onRequestRoute: (latitude: Double, longitude: Double) -> Unit,
    onCancelRoute: () -> Unit,
    onVisibleRadius: (Int) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val overlay = remember { CarsOverlay() }
    var pendingDestination by remember { mutableStateOf<GeoPoint?>(null) }
    val naviConnected by rememberUpdatedState(navi.connected)
    val currentOnVisibleRadius by rememberUpdatedState(onVisibleRadius)
    /** 지도에 지금 보이는 Car-ind CHAT 차 수 */
    var carsOnMap by remember { mutableStateOf(0) }
    val mapView = remember {
        Configuration.getInstance().apply {
            // 타일 캐시를 앱 전용 폴더에 두고, OSM 타일 서버 정책에 따라 앱 식별자를 User-Agent 로 보낸다
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(18.0)
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p == null || !naviConnected) return false
                        pendingDestination = p
                        return true
                    }
                })
            )
            overlays.add(overlay)
            // 지도에 보이는 범위(가운데에서 구석까지)를 알려서, 그 안의 차를 모두 찾게 한다
            fun reportRadius() {
                val box = boundingBox
                if (box.latitudeSpan > 0.0) currentOnVisibleRadius((box.diagonalLengthInMeters / 2).toInt())
            }
            addOnFirstLayoutListener { _, _, _, _, _ -> reportRadius() }
            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = false
                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    reportRadius()
                    return false
                }
            })
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    LaunchedEffect(myFix, cars, navi.destination) {
        overlay.destination = navi.destination?.let { GeoPoint(it.latitude, it.longitude) }
        val fix = myFix
        if (fix != null) {
            val me = GeoPoint(fix.latitude, fix.longitude)
            overlay.me = me
            overlay.headingDeg = fix.heading
            overlay.others = cars.mapNotNull { it.toGeoPoint(fix) }
            mapView.mapOrientation = -fix.heading   // 진행 방향이 위
            mapView.controller.setCenter(me)
            val box = mapView.boundingBox
            carsOnMap = overlay.others.count { box.contains(it) }
        }
        mapView.invalidate()
    }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(NavColors.MapBackdrop)
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 왼쪽 위: 다음 회전, 또는 안내 중이 아닐 때의 상태
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            val turn = navi.nextTurn
            if (navi.isGuiding && turn != null) {
                TurnCard(turn)
            } else {
                StatusCard(
                    when {
                        !navi.connected -> navi.lastError ?: "순정 내비 연결 중…"
                        navi.isGuiding -> "경로 안내 중"
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
            ArrivalCard(
                destination = destination,
                onCancel = onCancelRoute,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
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
        // 지도에 보이는 범위 안의 Car-ind CHAT 차는 모두 점으로 표시된다
        Text(
            "● 지도 안 Car-ind CHAT 차 ${carsOnMap}대",
            color = NavColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 36.dp)
                .shadow(6.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Text(
            "© OpenStreetMap contributors",
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

/** 상대 위치 벡터(앞뒤·좌우 m)를 내 위치·방향 기준 위경도로 되돌린다 */
private fun NearbyCar.toGeoPoint(me: LocationGateway.Fix): GeoPoint? {
    val f = forwardMeters ?: return null
    val l = lateralMeters ?: return null
    if (distanceMeters < 0) return null
    val h = Math.toRadians(me.heading.toDouble())
    val north = f * cos(h) - l * sin(h)
    val east = f * sin(h) + l * cos(h)
    val metersPerDegLat = 111_320.0
    return GeoPoint(
        me.latitude + north / metersPerDegLat,
        me.longitude + east / (metersPerDegLat * cos(Math.toRadians(me.latitude))),
    )
}

/** 내 차(파란 원 안 흰 화살표), 근처 Car-ind CHAT 차(주황 점), 목적지(빨간 핀)를 지도 위에 그린다 */
private class CarsOverlay : Overlay() {
    var me: GeoPoint? = null
    var headingDeg = 0f
    var others: List<GeoPoint> = emptyList()
    var destination: GeoPoint? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x332F6BFF
    }
    private val point = Point()
    private val arrow = Path()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val density = mapView.resources.displayMetrics.density
        stroke.strokeWidth = 2.5f * density
        destination?.let { d ->
            mapView.projection.toPixels(d, point)
            fill.color = 0xFFE53935.toInt()
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 12f * density, fill)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 12f * density, stroke)
            fill.color = android.graphics.Color.WHITE
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 4f * density, fill)
        }
        others.forEach { p ->
            mapView.projection.toPixels(p, point)
            fill.color = 0xFFFF8A3D.toInt()
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 8f * density, fill)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 8f * density, stroke)
        }
        val my = me ?: return
        mapView.projection.toPixels(my, point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val r = 18f * density
        canvas.drawCircle(x, y, r * 1.9f, halo)
        fill.color = 0xFF2F6BFF.toInt()
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, stroke)
        // 지도가 -heading 만큼 돌아가 있으므로, 화살표를 heading 만큼 돌려 그리면 화면에서는 항상 위를 향한다
        canvas.save()
        canvas.rotate(headingDeg, x, y)
        val s = r * 0.62f
        arrow.rewind()
        arrow.moveTo(x, y - s)
        arrow.lineTo(x + s * 0.75f, y + s * 0.75f)
        arrow.lineTo(x, y + s * 0.35f)
        arrow.lineTo(x - s * 0.75f, y + s * 0.75f)
        arrow.close()
        fill.color = android.graphics.Color.WHITE
        canvas.drawPath(arrow, fill)
        canvas.restore()
    }
}
