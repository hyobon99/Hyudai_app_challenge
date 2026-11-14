package com.roadtalk.app.map

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 지도(OpenStreetMap)의 도로와 횡단보도를 3D 화면에 그릴 모양으로 바꾼다.
 *  - 도로: 차가 다니는 길의 중심선(점들)과 폭. 폭은 차로 수(lanes)가 있으면 그걸로, 없으면 도로 등급으로 정한다
 *  - 횡단보도: 위치와, 그 횡단보도가 건너는 도로의 방향·폭 (가장 가까운 도로에서 가져온다)
 * 화면에서는 내가 달리는 길은 곧은 3차선 그대로 두고, 그 길과 만나는 길·주변 길과 횡단보도만 이 자료로 그린다.
 */
object OsmRoads {

    /** points = (동쪽 m, 북쪽 m) 이 차례로 들어 있는 배열 (기준점 기준), width = 도로 폭 (m) */
    class Road(val id: Long, val points: FloatArray, val width: Float)

    /** bearingDeg = 건너는 도로가 뻗은 방위 (0 북, 90 동), roadWidth = 그 도로의 폭 (m) */
    data class Crossing(val east: Float, val north: Float, val bearingDeg: Float, val roadWidth: Float)

    private const val LANE_METERS = 3.3f
    /** 횡단보도는 이 거리 안에 도로가 있어야 그린다 (m) */
    private const val CROSSING_SNAP_M = 12f

    /** 차가 다니는 길만 (골목 포함). 주차장 통로 같은 service, 보행로·자전거길은 뺀다 */
    private val DRIVABLE = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    fun isDrivable(highway: String?): Boolean = highway in DRIVABLE

    /** 도로 폭 (m): 차로 수 × 3.3m, 차로 수가 없으면 등급별 어림값 */
    fun widthOf(highway: String, lanesTag: String?): Float {
        lanesTag?.trim()?.toIntOrNull()?.takeIf { it in 1..12 }?.let { return it * LANE_METERS }
        return when (highway) {
            "motorway", "trunk", "primary" -> 13f
            "secondary" -> 10f
            "tertiary" -> 8f
            "living_street" -> 5f
            else -> if (highway.endsWith("_link")) 5f else 6.5f
        }
    }

    /** Overpass 응답(JSON)에서 도로와 횡단보도를 꺼낸다 */
    fun parse(json: String, originLat: Double, originLng: Double): Pair<List<Road>, List<Crossing>> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList<Road>() to emptyList()
        val roads = ArrayList<Road>()
        val crossingPoints = ArrayList<Pair<Float, Float>>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags")
            val highway = tags?.optString("highway").orEmpty()
            if (element.optString("type") == "node") {
                if (highway == "crossing") {
                    crossingPoints += OsmBuildings.east(element.optDouble("lon"), originLat, originLng) to OsmBuildings.north(element.optDouble("lat"), originLat)
                }
                continue
            }
            if (!isDrivable(highway)) continue
            val geometry = element.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue
            val points = FloatArray(geometry.length() * 2)
            for (k in 0 until geometry.length()) {
                val p = geometry.optJSONObject(k) ?: continue
                points[k * 2] = OsmBuildings.east(p.optDouble("lon"), originLat, originLng)
                points[k * 2 + 1] = OsmBuildings.north(p.optDouble("lat"), originLat)
            }
            roads += Road(element.optLong("id"), points, widthOf(highway, tags?.optString("lanes")))
        }
        return roads to crossingPoints.mapNotNull { (e, n) -> crossingAt(e, n, roads) }
    }

    /** 횡단보도 하나: 가장 가까운 도로 구간의 방향과 폭을 붙인다. 가까운 도로가 없으면 null */
    fun crossingAt(east: Float, north: Float, roads: List<Road>): Crossing? {
        var best = CROSSING_SNAP_M
        var bearing = 0f
        var width = 0f
        var found = false
        for (road in roads) {
            val p = road.points
            for (i in 0 until p.size / 2 - 1) {
                val ax = p[i * 2]
                val ay = p[i * 2 + 1]
                val bx = p[i * 2 + 2]
                val by = p[i * 2 + 3]
                val d = distanceToSegment(east, north, ax, ay, bx, by)
                if (d < best) {
                    best = d
                    bearing = Math.toDegrees(atan2((bx - ax).toDouble(), (by - ay).toDouble())).toFloat()
                    width = road.width
                    found = true
                }
            }
        }
        return if (found) Crossing(east, north, bearing, width) else null
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }
}
