package com.roadtalk.app.map

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 내 위치에서 목적지까지 도로를 따라가는 길 (지도에 선으로 그릴 좌표 목록).
 * NaviHelper SDK 는 순정 내비가 고른 경로의 선을 주지 않는다 (목적지 · 남은 거리 · 다음 회전만 준다).
 * 그래서 OpenStreetMap 도로 자료로 길을 찾아 주는 공개 서버(OSRM)에 물어서 받는다.
 * 순정 내비가 실제로 안내하는 길과 다를 수 있다 (참고용 선).
 */
object RoadRoute {

    data class Point(val latitude: Double, val longitude: Double)

    /** 찾아 둔 길: 어느 목적지로 가는 길인지와 함께 기억한다 (지도 화면을 닫았다 열어도 다시 묻지 않게) */
    data class Found(val toLatitude: Double, val toLongitude: Double, val points: List<Point>, val seconds: Double?)

    /** 다음에 꺾는 곳: 거기까지 남은 거리, 오른쪽인지, 유턴인지 */
    data class Turn(val meters: Int, val right: Boolean, val uTurn: Boolean)

    /** 경로 위에서 지금 어디쯤인지: 목적지까지 남은 거리 · 시간, 다음에 꺾는 곳 (더 꺾을 데가 없으면 null = 이대로 가면 목적지) */
    data class Progress(val remainMeters: Int, val remainSeconds: Int, val turn: Turn?)

    @Volatile var last: Found? = null
        private set

    /** 이 목적지로 가는, 이미 찾아 둔 길 (없으면 빈 목록) */
    fun remembered(toLatitude: Double, toLongitude: Double): List<Point> =
        last?.takeIf { it.toLatitude == toLatitude && it.toLongitude == toLongitude }?.points.orEmpty()

    fun forget() {
        last = null
    }

    /**
     * 길을 찾는다 (네트워크를 쓰므로 화면 스레드에서 부르지 말 것). 못 찾으면 null.
     * 서버에는 출발 · 도착 좌표만 보낸다 (차량번호 같은 정보는 보내지 않는다)
     */
    fun fetch(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): List<Point>? {
        val url = String.format(
            Locale.US, "%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline&steps=false",
            ENDPOINT, fromLongitude, fromLatitude, toLongitude, toLatitude,
        )
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "CarIndChat/1.0 (Android)")
                if (connection.responseCode != 200) {
                    Log.w(TAG, "route server answered ${connection.responseCode}")
                    return null
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val points = parse(json)
                if (points != null) last = Found(toLatitude, toLongitude, points, parseSeconds(json))
                points
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "route not found", e)
            null
        }
    }

    /** 서버의 답(JSON)에서 첫 번째 경로의 선을 꺼낸다. 길이 없으면 null */
    fun parse(json: String): List<Point>? {
        if (!json.contains("\"code\":\"Ok\"")) return null
        val encoded = GEOMETRY.find(json)?.groupValues?.get(1) ?: return null
        // JSON 문자열 안에서 \ 는 \\ 로 적혀 있다
        return decodePolyline(encoded.replace("\\\\", "\\")).takeIf { it.size >= 2 }
    }

    /** 서버의 답에서 그 길을 가는 데 걸리는 시간(초)을 꺼낸다 */
    fun parseSeconds(json: String): Double? = DURATION.find(json)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }

    /**
     * 경로 위에서 지금 어디쯤인지. 남은 거리는 내 자리에서 경로를 따라 잰 길이이고, 남은 시간은 길 전체에 걸리는 시간을 남은 길이만큼 나눈 것
     * (totalSeconds 를 모르면 시속 약 25km 로 어림한다). 다음 회전은 경로의 선이 [TURN_DEG] 도 넘게 꺾이는 첫 자리.
     * 순정 내비의 안내값 대신 지도에 그린 길과 맞는 값을 보여 주려고 쓴다. 경로가 없으면 null
     */
    fun progress(route: List<Point>, latitude: Double, longitude: Double, totalSeconds: Double? = null): Progress? {
        val start = nearest(route, latitude, longitude)?.first ?: return null
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * cos(Math.toRadians(latitude))
        val xs = DoubleArray(route.size) { (route[it].longitude - longitude) * mPerLng }
        val ys = DoubleArray(route.size) { (route[it].latitude - latitude) * mPerLat }
        // 내 자리를 경로의 start 번째 구간 위로 옮긴 점
        val dx = xs[start + 1] - xs[start]
        val dy = ys[start + 1] - ys[start]
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (-(xs[start] * dx + ys[start] * dy) / length2).coerceIn(0.0, 1.0)
        // 앞으로 갈 길: 내 자리 → start 다음 점 → … → 끝
        val px = ArrayList<Double>(route.size - start + 1)
        val py = ArrayList<Double>(route.size - start + 1)
        px.add(xs[start] + dx * t)
        py.add(ys[start] + dy * t)
        for (i in start + 1 until route.size) {
            px.add(xs[i])
            py.add(ys[i])
        }
        val along = DoubleArray(px.size)
        for (i in 1 until px.size) along[i] = along[i - 1] + hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        val remain = along.last()

        fun at(distance: Double): Pair<Double, Double> {
            val d = distance.coerceIn(0.0, remain)
            var i = 1
            while (i < along.size - 1 && along[i] < d) i++
            val span = along[i] - along[i - 1]
            val u = if (span == 0.0) 0.0 else (d - along[i - 1]) / span
            return (px[i - 1] + (px[i] - px[i - 1]) * u) to (py[i - 1] + (py[i] - py[i - 1]) * u)
        }

        var turn: Turn? = null
        for (i in 1 until px.size - 1) {
            if (along[i] < 1.0 || remain - along[i] < 1.0) continue
            // 꺾이는 점 앞뒤로 TURN_SPAN_M 떨어진 자리끼리 방향을 견준다 (점이 촘촘한 굽은 길도 한 번의 회전으로 본다)
            val (ax, ay) = at(along[i] - TURN_SPAN_M)
            val (bx, by) = at(along[i] + TURN_SPAN_M)
            val into = Math.toDegrees(atan2(px[i] - ax, py[i] - ay))
            val out = Math.toDegrees(atan2(bx - px[i], by - py[i]))
            var bend = (out - into) % 360.0
            if (bend > 180.0) bend -= 360.0
            if (bend < -180.0) bend += 360.0
            if (abs(bend) >= TURN_DEG) {
                turn = Turn(along[i].toInt(), right = bend > 0, uTurn = abs(bend) >= UTURN_DEG)
                break
            }
        }
        var whole = 0.0
        for (i in 1 until route.size) whole += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val seconds = if (totalSeconds != null && whole > 1.0) totalSeconds * remain / whole else remain / DEFAULT_MPS
        return Progress(remain.toInt(), seconds.toInt(), turn)
    }

    /** 'encoded polyline' (좌표를 글자로 줄여 적는 표준 방식) 을 좌표 목록으로 푼다 */
    fun decodePolyline(encoded: String, precision: Int = 5): List<Point> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<Point>()
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length) {
            val values = LongArray(2)
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    if (index >= encoded.length) return points
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                values[k] = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            latitude += values[0]
            longitude += values[1]
            points.add(Point(latitude / factor, longitude / factor))
        }
        return points
    }

    /**
     * 이 위치에서 가장 가까운 경로 위의 자리: (그 구간이 시작하는 점의 번호, 떨어진 거리 m).
     * 경로가 비어 있으면 null
     */
    fun nearest(route: List<Point>, latitude: Double, longitude: Double): Pair<Int, Double>? {
        if (route.size < 2) return null
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * cos(Math.toRadians(latitude))
        var bestIndex = 0
        var best = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val ax = (route[i].longitude - longitude) * mPerLng
            val ay = (route[i].latitude - latitude) * mPerLat
            val bx = (route[i + 1].longitude - longitude) * mPerLng
            val by = (route[i + 1].latitude - latitude) * mPerLat
            val dx = bx - ax
            val dy = by - ay
            val length2 = dx * dx + dy * dy
            val t = if (length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * t, ay + dy * t)
            if (d < best) {
                best = d
                bestIndex = i
            }
        }
        return bestIndex to best
    }

    private val GEOMETRY = Regex("\"geometry\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val DURATION = Regex("\"duration\"\\s*:\\s*([0-9.]+)")
    /** 경로의 선이 이만큼(도) 넘게 꺾이면 회전, 이만큼 넘으면 유턴으로 본다. 앞뒤 이 거리(m)의 방향을 견준다 */
    private const val TURN_DEG = 40.0
    private const val UTURN_DEG = 150.0
    private const val TURN_SPAN_M = 12.0
    /** 걸리는 시간을 모를 때 어림하는 빠르기 (m/s, 시속 약 25km) */
    private const val DEFAULT_MPS = 7.0
    private const val ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
    private const val TIMEOUT_MS = 8000
    private const val TAG = "RoadTalkRoute"
}
