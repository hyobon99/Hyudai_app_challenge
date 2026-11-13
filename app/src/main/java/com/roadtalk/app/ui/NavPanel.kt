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
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 가운데 내비게이션 패널.
 * 차량 순정 내비게이션 화면은 다른 앱 안에 넣을 수 없어서 (시스템 권한 필요), 지도는 OpenStreetMap 타일(osmdroid)로 직접 그리고
 * 그 위에 NaviHelper SDK 로 받은 순정 내비 정보(다음 회전, 목적지, 남은 거리·시간, 제한속도)를 얹는다.
 * 지도를 길게 누르면 그 위치로 순정 내비에 경로 안내를 요청한다.
 */
@Composable
fun NavPanel(
    myFix: LocationGateway.Fix?,
    cars: List<NearbyCar>,
    navi: NaviGateway.State,
    onRequestRoute: (latitude: Double, longitude: Double) -> Unit,
    onCancelRoute: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val overlay = remember { CarsOverlay() }
    var pendingDestination by remember { mutableStateOf<GeoPoint?>(null) }
    val naviConnected by rememberUpdatedState(navi.connected)
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
            // 차량 화면에 맞게 어두운 지도 (밝은 타일 색을 반전)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
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
        }
        mapView.invalidate()
    }

    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(RoadColors.Surface)
            .padding(16.dp)
    ) {
        Text("내비게이션", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RoadColors.TextPrimary)
        Text(
            when {
                !navi.connected -> navi.lastError ?: "순정 내비 연결 중…"
                navi.isGuiding -> "순정 내비 경로 안내 중"
                navi.status == NavigationStatus.INITIALIZING -> "순정 내비 준비 중"
                else -> "목적지 없음 · 지도를 길게 누르면 그곳으로 안내를 시작해요"
            },
            fontSize = 14.sp,
            color = RoadColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RoadColors.Background)
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (myFix == null) {
                Text(
                    "위치 확인 중…",
                    color = RoadColors.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(10.dp))
                        .background(RoadColors.SurfaceHigh)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            navi.destination?.let { destination ->
                GuidanceCard(
                    destination = destination,
                    turn = navi.nextTurn,
                    onCancel = onCancelRoute,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
            }
            navi.speedLimit?.let { limit ->
                // 도로 표지판처럼 빨간 테두리 원
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(6.dp, Color(0xFFE53935), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$limit", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
            Text(
                "© OpenStreetMap contributors",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
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

/** 지도 왼쪽 위 안내 카드: 다음 회전 + 목적지·남은 거리/시간 + 안내 종료 */
@Composable
private fun GuidanceCard(
    destination: NaviGateway.Destination,
    turn: NaviGateway.Turn?,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .widthIn(min = 300.dp, max = 460.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RoadColors.Surface.copy(alpha = 0.94f))
            .padding(14.dp)
    ) {
        if (turn != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(turnArrow(turn.type), fontSize = 44.sp, color = RoadColors.Accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(formatDistance(turn.meters), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = RoadColors.TextPrimary)
                    Text(
                        turn.description.ifBlank { turnLabel(turn.type) },
                        fontSize = 15.sp,
                        color = RoadColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "🏁 ${destination.name}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RoadColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "남은 거리 ${formatDistance(destination.remainMeters)} · ${formatDuration(destination.remainSeconds)}",
                    fontSize = 14.sp,
                    color = RoadColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "안내 종료",
                fontSize = 14.sp,
                color = RoadColors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(RoadColors.SurfaceHigh)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private fun formatDistance(meters: Int): String = when {
    meters < 0 -> "-"
    meters < 1000 -> "${meters / 10 * 10}m"
    else -> "%.1fkm".format(meters / 1000f)
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
    TurnType.TURN_ARRIVE -> "🏁"
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

/** 내 차(진행 방향 화살표), 근처 Car-ind CHAT 차(점), 목적지(빨간 핀)를 지도 위에 그린다 */
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
    private val point = Point()
    private val arrow = Path()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val density = mapView.resources.displayMetrics.density
        stroke.strokeWidth = 2f * density
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
            fill.color = 0xFFFFC857.toInt()
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 7f * density, fill)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 7f * density, stroke)
        }
        val my = me ?: return
        mapView.projection.toPixels(my, point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val s = 14f * density
        // 지도가 -heading 만큼 돌아가 있으므로, 화살표를 heading 만큼 돌려 그리면 화면에서는 항상 위를 향한다
        canvas.save()
        canvas.rotate(headingDeg, x, y)
        arrow.rewind()
        arrow.moveTo(x, y - s)
        arrow.lineTo(x + s * 0.7f, y + s * 0.8f)
        arrow.lineTo(x, y + s * 0.4f)
        arrow.lineTo(x - s * 0.7f, y + s * 0.8f)
        arrow.close()
        fill.color = 0xFF3D8BFF.toInt()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
