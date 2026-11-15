package com.roadtalk.app.map

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    data class Found(val toLatitude: Double, val toLongitude: Double, val points: List<Point>)

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
                val points = parse(connection.inputStream.bufferedReader().use { it.readText() })
                if (points != null) last = Found(toLatitude, toLongitude, points)
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
    private const val ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
    private const val TIMEOUT_MS = 8000
    private const val TAG = "RoadTalkRoute"
}
