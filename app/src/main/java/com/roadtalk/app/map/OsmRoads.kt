package com.roadtalk.app.map

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 지도(OpenStreetMap)의 도로 · 횡단보도 · 신호등을 3D 화면에 그릴 모양으로 바꾼다.
 *  - 도로: 차가 다니는 길의 중심선(점들), 차로 수, 일방통행 여부, 폭
 *  - 횡단보도 · 신호등: 위치와, 그것이 놓인 도로의 방향·폭 (가장 가까운 도로에서 가져온다)
 * 화면에서는 내가 달리는 길을 이 차로 수대로 곧게 그리고, 그 길과 만나는 길·주변 길을 지도 위치대로 깐다.
 */
object OsmRoads {

    /**
     * points = (동쪽 m, 북쪽 m) 이 차례로 들어 있는 배열 (기준점 기준).
     * lanes = 차로 수 (양방향 도로면 양쪽을 합친 수), oneway = 일방통행 (큰길은 방향마다 따로 그려져 있어 일방통행이다), width = 폭 (m)
     */
    /**
     * marked = 차선이 그어진 길인지. 골목(이면도로)은 중앙선도 차선도 없어서 false:
     * 화면에서는 선 없이 폭만큼 그리고, 차를 차로에 맞추지 않고 실제 자리에 그린다 (길가에 세워 둔 차, 그 사이로 지나가는 차)
     */
    class Road(val id: Long, val points: FloatArray, val lanes: Int, val oneway: Boolean, val width: Float, val marked: Boolean = true)

    /** 도로 위의 한 점 (횡단보도·신호등). bearingDeg = 그 도로가 뻗은 방위 (0 북, 90 동), roadWidth = 그 도로의 폭 (m) */
    data class Spot(val east: Float, val north: Float, val bearingDeg: Float, val roadWidth: Float)

    class Parsed(val roads: List<Road>, val crossings: List<Spot>, val signals: List<Spot>)

    const val LANE_METERS = 3.3f
    /** 횡단보도·신호등은 이 거리 안에 도로가 있어야 그린다 (m) */
    private const val SNAP_M = 12f
    /** 차의 방향을 모를 때, 이 거리 안의 도로 방향을 차의 방향으로 본다 (m) */
    private const val HEADING_SNAP_M = 35f
    /** 달리는 중에 진행 방향을 도로 방향에 맞출 때: 이 거리 안의 도로, 이 각도 안으로 비슷할 때만 */
    private const val ALIGN_M = 12f
    private const val ALIGN_DEG = 38f

    /** 차가 다니는 길만 (골목 포함). 주차장 통로 같은 service, 보행로·자전거길은 뺀다 */
    private val DRIVABLE = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    fun isDrivable(highway: String?): Boolean = highway in DRIVABLE

    /**
     * 차로 수: 지도의 lanes 값. 없으면(골목은 대부분 없다) 도로 등급으로 어림한다 (골목은 차 두 대 폭 · 차선 없음 → [markedOf]).
     * 일방통행이면 그 방향의 차로 수, 양방향이면 양쪽을 합친 수.
     */
    fun lanesOf(highway: String, lanesTag: String?, oneway: Boolean): Int {
        lanesTag?.trim()?.toIntOrNull()?.takeIf { it in 1..12 }?.let { return it }
        val bothWays = when (highway) {
            "motorway", "trunk", "primary" -> 6
            "secondary" -> 4
            "tertiary", "unclassified" -> 2
            // 골목(이면도로): 차 두 대가 겨우 비켜 갈 폭이고 중앙선은 없다. 길가에 차가 서 있으면 가운데로 한 대만 지나간다
            "residential", "living_street" -> 2
            else -> 2
        }
        return when {
            highway.endsWith("_link") -> 1
            oneway -> (bothWays / 2).coerceAtLeast(1)
            else -> bothWays
        }
    }

    /** 차선이 그어진 길인지: 차로 수가 지도에 적혀 있지 않은 골목(이면도로)은 차선이 없는 길로 본다 */
    fun markedOf(highway: String, lanesTag: String?): Boolean {
        val alley = highway == "residential" || highway == "living_street"
        return !alley || lanesTag?.trim()?.toIntOrNull()?.let { it in 1..12 } == true
    }

    /** 도로 폭 (m): 차로 수 × 3.3m */
    fun widthOf(lanes: Int): Float = lanes * LANE_METERS

    /** Overpass 응답(JSON)에서 도로 · 횡단보도 · 신호등을 꺼낸다 */
    fun parse(json: String, originLat: Double, originLng: Double): Parsed {
        val elements = JSONObject(json).optJSONArray("elements") ?: return Parsed(emptyList(), emptyList(), emptyList())
        val roads = ArrayList<Road>()
        val crossingPoints = ArrayList<Pair<Float, Float>>()
        val signalPoints = ArrayList<Pair<Float, Float>>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags")
            val highway = tags?.optString("highway").orEmpty()
            if (element.optString("type") == "node") {
                val at = OsmBuildings.east(element.optDouble("lon"), originLat, originLng) to OsmBuildings.north(element.optDouble("lat"), originLat)
                if (highway == "crossing") crossingPoints += at
                if (highway == "traffic_signals") signalPoints += at
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
            val oneway = tags?.optString("oneway") == "yes"
            val lanes = lanesOf(highway, tags?.optString("lanes"), oneway)
            roads += Road(element.optLong("id"), points, lanes, oneway, widthOf(lanes), markedOf(highway, tags?.optString("lanes")))
        }
        return Parsed(roads, crossingPoints.mapNotNull { (e, n) -> spotAt(e, n, roads) }, signalPoints.mapNotNull { (e, n) -> spotAt(e, n, roads) })
    }

    /** 도로 위의 한 점에 가장 가까운 도로 구간의 방향과 폭을 붙인다. 가까운 도로가 없으면 null */
    fun spotAt(east: Float, north: Float, roads: List<Road>): Spot? {
        var best = SNAP_M
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
        return if (found) Spot(east, north, bearing, width) else null
    }

    /**
     * 그 자리에서 가장 가까운 도로가 뻗은 방위 (0 북, 90 동, 0~360). [within] m 안에 도로가 없으면 null.
     * 차의 방향을 아직 모를 때(앱을 켜고 한 번도 움직이지 않았을 때) 차가 그 길을 따라 서 있다고 보는 데 쓴다.
     * 지도에 길이 그려진 방향을 따른다 (일방통행 길은 그것이 곧 차가 달리는 방향이다).
     */
    fun bearingAt(east: Float, north: Float, roads: List<Road>, within: Float = HEADING_SNAP_M): Float? {
        var best = within
        var bearing: Float? = null
        for (road in roads) {
            val p = road.points
            for (i in 0 until p.size / 2 - 1) {
                val ax = p[i * 2]
                val ay = p[i * 2 + 1]
                val bx = p[i * 2 + 2]
                val by = p[i * 2 + 3]
                if (hypot(bx - ax, by - ay) < 0.5f) continue
                val d = distanceToSegment(east, north, ax, ay, bx, by)
                if (d < best) {
                    best = d
                    bearing = (Math.toDegrees(atan2((bx - ax).toDouble(), (by - ay).toDouble())).toFloat() + 360f) % 360f
                }
            }
        }
        return bearing
    }

    /** 두 방위의 차이 (0~180도) */
    fun angleBetween(a: Float, b: Float): Float = abs(((a - b) % 360f + 540f) % 360f - 180f)

    /**
     * 차의 진행 방향을 지금 달리는 길의 방향에 맞춘다.
     * 이동 방향(GPS 로 계산)은 차로를 바꾸거나 옆으로 비켜 줄 때 20~30도씩 틀어진다. 그 방향을 그대로 쓰면 화면 전체가 같이 돌아
     * 내 길이 '가로지르는 길' 로 잘못 그려지고, 옆 차가 두세 차로 옆에 있는 것처럼 계산된다.
     * 그래서 [within] m 안에 도로가 있고 그 도로의 방향(어느 쪽으로든)이 이동 방향과 [maxDeg] 도 안으로 비슷하면 도로의 방향을 쓴다.
     * 교차로에서 도는 중처럼 어느 길과도 비슷하지 않으면 이동 방향을 그대로 쓴다.
     */
    fun alignedHeading(heading: Float, east: Float, north: Float, roads: List<Road>, within: Float = ALIGN_M, maxDeg: Float = ALIGN_DEG): Float {
        val bearing = bearingAt(east, north, roads, within) ?: return heading
        val reverse = (bearing + 180f) % 360f
        val along = if (angleBetween(bearing, heading) <= angleBetween(reverse, heading)) bearing else reverse
        return if (angleBetween(along, heading) <= maxDeg) along else heading
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }
}
