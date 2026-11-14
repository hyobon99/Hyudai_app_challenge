package com.roadtalk.app.map

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos

/**
 * 지도(OpenStreetMap)의 건물 자료를 3D 화면에 세울 모양으로 바꾼다.
 * 자료는 Overpass API 에서 받는다: 건물마다 바닥 윤곽(위도·경도 점들)과, 있으면 높이(height)·층수(building:levels).
 * 화면에는 그 윤곽 모양 그대로 벽을 세우고 지붕을 덮는다 ([CityMesh]).
 */
object OsmBuildings {

    /** points = 바닥 윤곽의 꼭짓점 (동쪽 m, 북쪽 m 이 차례로, 기준점 기준, 반시계 방향, 끝점은 첫 점을 되풀이하지 않는다) */
    class Footprint(val id: Long, val points: FloatArray, val height: Float)

    /** 한 번 받아 온 구역: 기준점과 그 둘레의 건물 · 도로 · 횡단보도. city = 건물들을 3D 로 세운 모양 */
    class Area(
        val originLat: Double,
        val originLng: Double,
        val buildings: List<Footprint>,
        val roads: List<OsmRoads.Road> = emptyList(),
        val crossings: List<OsmRoads.Crossing> = emptyList(),
    ) {
        val city: CityMesh by lazy { CityMesh.build(buildings) }
    }

    private const val METERS_PER_DEG_LAT = 111_320.0
    private const val LEVEL_METERS = 3.2f
    /** 너무 작은 것(창고·부속 건물)과 너무 큰 것(지하상가·역사 등 윤곽이 건물 모양이 아닌 것)은 뺀다 */
    private const val MIN_SIDE_M = 4f
    private const val MAX_SIDE_M = 180f
    private const val ROADS =
        "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    /** 이 위치 둘레 radius m 안의 건물 · 차가 다니는 길 · 횡단보도를 달라는 Overpass 질의문 */
    fun query(lat: Double, lng: Double, radiusMeters: Int): String {
        val around = "(around:$radiusMeters,$lat,$lng)"
        return "[out:json][timeout:20];(way[\"building\"]$around;way[\"highway\"~\"^($ROADS)$\"]$around;node[\"highway\"=\"crossing\"]$around;);out tags geom;"
    }

    fun east(lng: Double, originLat: Double, originLng: Double): Float =
        ((lng - originLng) * METERS_PER_DEG_LAT * cos(Math.toRadians(originLat))).toFloat()

    fun north(lat: Double, originLat: Double): Float = ((lat - originLat) * METERS_PER_DEG_LAT).toFloat()

    /** Overpass 응답(JSON) → 구역. 형식이 맞지 않는 항목은 건너뛴다 */
    fun parseArea(json: String, originLat: Double, originLng: Double): Area {
        val (roads, crossings) = OsmRoads.parse(json, originLat, originLng)
        return Area(originLat, originLng, parse(json, originLat, originLng), roads, crossings)
    }

    /** Overpass 응답(JSON) → 건물 윤곽 목록 (도로·횡단보도 항목은 건너뛴다) */
    fun parse(json: String, originLat: Double, originLng: Double): List<Footprint> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Footprint>(elements.length())
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") == "node") continue
            val tags = element.optJSONObject("tags")
            if (tags != null && tags.has("highway")) continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (geometry.length() < 4) continue
            val points = FloatArray(geometry.length() * 2)
            for (k in 0 until geometry.length()) {
                val p = geometry.optJSONObject(k) ?: continue
                points[k * 2] = east(p.optDouble("lon"), originLat, originLng)
                points[k * 2 + 1] = north(p.optDouble("lat"), originLat)
            }
            val id = element.optLong("id")
            val ring = outline(points) ?: continue
            out += Footprint(id, ring, heightOf(id, tags?.optString("height"), tags?.optString("building:levels"), ring))
        }
        return out
    }

    /**
     * 높이 (m): height 태그 → 층수 × 3.2m → 둘 다 없으면 바닥 넓이로 짐작 (넓을수록 높게, 건물마다 조금씩 다르게).
     * 지도에 높이가 적힌 건물은 일부뿐이라 나머지는 짐작값이다.
     */
    fun heightOf(id: Long, heightTag: String?, levelsTag: String?, ring: FloatArray): Float {
        heightTag?.trim()?.removeSuffix("m")?.trim()?.toFloatOrNull()?.takeIf { it in 2f..600f }?.let { return it }
        levelsTag?.trim()?.toFloatOrNull()?.takeIf { it in 1f..150f }?.let { return it * LEVEL_METERS + 1f }
        val area = abs(signedArea(ring))
        val base = when {
            area < 120f -> 7f
            area < 400f -> 11f
            area < 1200f -> 16f
            else -> 22f
        }
        return base * (0.8f + ((id and 0x7FFFFFFF) % 5) * 0.1f)
    }

    /**
     * 지도 윤곽(첫 점으로 돌아와 닫힌 점들) → 그리기 좋은 윤곽: 닫는 점과 겹친 점을 빼고, 반시계 방향으로 맞춘다.
     * 꼭짓점이 3개 미만이거나 너무 작거나 큰 것은 null
     */
    fun outline(points: FloatArray): FloatArray? {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until points.size / 2) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            if (xs.isNotEmpty() && abs(x - xs.last()) < 0.05f && abs(y - ys.last()) < 0.05f) continue
            xs += x
            ys += y
        }
        if (xs.size > 1 && abs(xs.first() - xs.last()) < 0.05f && abs(ys.first() - ys.last()) < 0.05f) {
            xs.removeAt(xs.size - 1)
            ys.removeAt(ys.size - 1)
        }
        if (xs.size < 3) return null
        val width = xs.max() - xs.min()
        val depth = ys.max() - ys.min()
        if (maxOf(width, depth) < MIN_SIDE_M || maxOf(width, depth) > MAX_SIDE_M) return null
        val ring = FloatArray(xs.size * 2) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
        if (abs(signedArea(ring)) < MIN_SIDE_M * MIN_SIDE_M) return null
        if (signedArea(ring) < 0f) {
            // 시계 방향이면 뒤집는다
            for (i in 0 until xs.size) {
                ring[i * 2] = xs[xs.size - 1 - i]
                ring[i * 2 + 1] = ys[xs.size - 1 - i]
            }
        }
        return ring
    }

    /** 다각형 넓이 (m²). 반시계 방향이면 +, 시계 방향이면 − */
    fun signedArea(ring: FloatArray): Float {
        val n = ring.size / 2
        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += ring[i * 2] * ring[j * 2 + 1] - ring[j * 2] * ring[i * 2 + 1]
        }
        return sum / 2
    }
}
