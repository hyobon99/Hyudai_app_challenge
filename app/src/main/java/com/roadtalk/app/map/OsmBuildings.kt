package com.roadtalk.app.map

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 지도(OpenStreetMap)의 건물 자료를 3D 화면에 세울 '네모 상자'로 바꾼다.
 * 자료는 Overpass API 에서 받는다: 건물마다 바닥 윤곽(위도·경도 점들)과, 있으면 높이(height)·층수(building:levels).
 * 화면에는 윤곽을 감싸는 네모 상자 하나로 단순화해서 세운다 (가장 긴 변의 방향에 맞춘 상자).
 */
object OsmBuildings {

    /** east/north = 기준점에서 동쪽/북쪽으로 몇 m (상자 가운데), bearingDeg = 가로(width) 변의 방위 (0 북, 90 동) */
    data class Box(val id: Long, val east: Float, val north: Float, val width: Float, val depth: Float, val bearingDeg: Float, val height: Float)

    /** 한 번 받아 온 구역: 기준점과 그 둘레의 건물들 */
    data class Area(val originLat: Double, val originLng: Double, val boxes: List<Box>)

    private const val METERS_PER_DEG_LAT = 111_320.0
    private const val LEVEL_METERS = 3.2f
    /** 너무 작은 것(창고·부속 건물)과 한 변이 너무 긴 것(지하상가·역사 등 윤곽이 건물 모양이 아닌 것)은 뺀다 */
    private const val MIN_SIDE_M = 4f
    private const val MAX_SIDE_M = 140f

    /** 이 위치 둘레 radius m 안의 건물을 달라는 Overpass 질의문 */
    fun query(lat: Double, lng: Double, radiusMeters: Int): String =
        "[out:json][timeout:15];way[\"building\"](around:$radiusMeters,$lat,$lng);out tags geom;"

    fun east(lng: Double, originLat: Double, originLng: Double): Float =
        ((lng - originLng) * METERS_PER_DEG_LAT * cos(Math.toRadians(originLat))).toFloat()

    fun north(lat: Double, originLat: Double): Float = ((lat - originLat) * METERS_PER_DEG_LAT).toFloat()

    /** Overpass 응답(JSON) → 상자 목록. 형식이 맞지 않는 항목은 건너뛴다 */
    fun parse(json: String, originLat: Double, originLng: Double): List<Box> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Box>(elements.length())
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (geometry.length() < 4) continue
            val points = ArrayList<Pair<Float, Float>>(geometry.length())
            for (k in 0 until geometry.length()) {
                val p = geometry.optJSONObject(k) ?: continue
                points += east(p.optDouble("lon"), originLat, originLng) to north(p.optDouble("lat"), originLat)
            }
            val id = element.optLong("id")
            val tags = element.optJSONObject("tags")
            box(id, points, heightOf(id, tags?.optString("height"), tags?.optString("building:levels"), points))?.let { out += it }
        }
        return out
    }

    /**
     * 높이 (m): height 태그 → 층수 × 3.2m → 둘 다 없으면 바닥 넓이로 짐작 (넓을수록 높게, 건물마다 조금씩 다르게).
     * 지도에 높이가 적힌 건물은 일부뿐이라 나머지는 짐작값이다.
     */
    fun heightOf(id: Long, heightTag: String?, levelsTag: String?, points: List<Pair<Float, Float>>): Float {
        heightTag?.trim()?.removeSuffix("m")?.trim()?.toFloatOrNull()?.takeIf { it in 2f..600f }?.let { return it }
        levelsTag?.trim()?.toFloatOrNull()?.takeIf { it in 1f..150f }?.let { return it * LEVEL_METERS + 1f }
        val area = area(points)
        val base = when {
            area < 120f -> 7f
            area < 400f -> 11f
            area < 1200f -> 16f
            else -> 22f
        }
        return base * (0.8f + ((id and 0x7FFFFFFF) % 5) * 0.1f)
    }

    /** 윤곽을 감싸는 네모 상자. 가장 긴 변의 방향을 상자의 가로로 삼는다 */
    fun box(id: Long, points: List<Pair<Float, Float>>, height: Float): Box? {
        var ue = 0f
        var un = 1f
        var longest = 0f
        for (i in 0 until points.size - 1) {
            val de = points[i + 1].first - points[i].first
            val dn = points[i + 1].second - points[i].second
            val length = hypot(de, dn)
            if (length > longest) {
                longest = length
                ue = de / length
                un = dn / length
            }
        }
        if (longest <= 0f) return null
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for ((e, n) in points) {
            val u = e * ue + n * un
            val v = -e * un + n * ue
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        val width = maxU - minU
        val depth = maxV - minV
        if (width < MIN_SIDE_M || depth < MIN_SIDE_M || width > MAX_SIDE_M || depth > MAX_SIDE_M) return null
        val cu = (minU + maxU) / 2
        val cv = (minV + maxV) / 2
        return Box(
            id = id,
            east = cu * ue - cv * un,
            north = cu * un + cv * ue,
            width = width,
            depth = depth,
            bearingDeg = Math.toDegrees(atan2(ue.toDouble(), un.toDouble())).toFloat(),
            height = height,
        )
    }

    /** 다각형 넓이 (m²) */
    private fun area(points: List<Pair<Float, Float>>): Float {
        var sum = 0f
        for (i in 0 until points.size - 1) sum += points[i].first * points[i + 1].second - points[i + 1].first * points[i].second
        return kotlin.math.abs(sum) / 2
    }
}
