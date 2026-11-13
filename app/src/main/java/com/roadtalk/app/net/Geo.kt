package com.roadtalk.app.net

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 서버 구현과 무관하게 클라이언트가 직접 계산하는 거리/상대 위치 */
internal object Geo {

    /**
     * 내 차 기준 상대 위치.
     * forwardMeters(+앞/−뒤), lateralMeters(+오른쪽/−왼쪽)가 상대 위치 벡터이고, position 은 그걸 8칸으로 나눈 것.
     * relativeHeadingDeg: 상대 차가 내 진행 방향 기준 몇 도를 보는지 (0 = 같은 방향, 180 = 맞은편). 모르면 null
     */
    data class Relative(
        val distanceMeters: Int,
        val position: RelativePosition,
        val forwardMeters: Float? = null,
        val lateralMeters: Float? = null,
        val relativeHeadingDeg: Float? = null,
    )

    /**
     * 움직이는 차 한 대. ageMillis = 이 위치가 측정된 뒤 지난 시간.
     * 속도·방향을 알면 그 시간만큼 앞으로 밀어서(추측 항법) '지금' 위치로 맞춘다.
     * 두 차의 위치를 같은 시각 기준으로 맞춰야 상대 위치 벡터가 정확해진다.
     */
    data class Mover(
        val lat: Double,
        val lng: Double,
        val heading: Float? = null,
        val speedMps: Float? = null,
        val ageMillis: Long = 0,
    )

    /** 같은 차선으로 보는 좌우 폭 (차로 폭 3.5m 의 절반) */
    private const val HALF_LANE = 1.75f
    /** 이 앞뒤 거리 안이면 '바로 옆' (차 길이 약 4.5~5m) */
    private const val SIDE_SPAN = 5f
    /** 이보다 오래된 위치는 더 밀지 않는다 (추측이 틀릴 위험이 커짐) */
    private const val MAX_PROJECT_MILLIS = 10_000L
    private const val METERS_PER_DEG_LAT = 111_320.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun relative(
        myLat: Double?, myLng: Double?, myHeading: Float?,
        otherLat: Double?, otherLng: Double?,
    ): Relative = relative(
        if (myLat != null && myLng != null) Mover(myLat, myLng, myHeading) else null,
        if (otherLat != null && otherLng != null) Mover(otherLat, otherLng) else null,
    )

    /** 어느 한쪽 좌표라도 없으면 거리 미확인(-1), 앞차로 간주 */
    fun relative(me: Mover?, other: Mover?): Relative {
        if (me == null || other == null) return Relative(-1, RelativePosition.FRONT)
        val (myLat, myLng) = project(me)
        val (otherLat, otherLng) = project(other)
        val (distance, bearing) = distanceAndBearing(myLat, myLng, otherLat, otherLng)
        // 정차 중이라 방향을 모르면 북쪽을 보고 있다고 가정
        val myHeading = me.heading ?: 0f
        val (forward, lateral) = toVector(bearing, myHeading, distance)
        val relHeading = other.heading?.let { normalizeDeg(it - myHeading) }
        return Relative(distance.toInt(), positionOf(forward, lateral), forward, lateral, relHeading)
    }

    /** 측정 뒤 지난 시간만큼 진행 방향으로 이동시킨 좌표. 속도나 방향을 모르면 그대로 */
    internal fun project(m: Mover): Pair<Double, Double> {
        val speed = m.speedMps ?: return m.lat to m.lng
        val heading = m.heading ?: return m.lat to m.lng
        val meters = speed * (m.ageMillis.coerceIn(0, MAX_PROJECT_MILLIS) / 1000.0)
        if (meters < 0.5) return m.lat to m.lng
        val rad = Math.toRadians(heading.toDouble())
        val dLat = meters * cos(rad) / METERS_PER_DEG_LAT
        val dLng = meters * sin(rad) / (METERS_PER_DEG_LAT * cos(Math.toRadians(m.lat)))
        return (m.lat + dLat) to (m.lng + dLng)
    }

    /**
     * 두 좌표 사이 거리(m)와 1→2 방위각(북쪽 0°, 시계 방향).
     * android.location.Location.distanceBetween 과 같은 결과지만 순수 Kotlin 이라 JVM 단위 테스트가 된다.
     */
    internal fun distanceAndBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Pair<Float, Float> {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = p2 - p1
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLng / 2) * sin(dLng / 2)
        val distance = 2 * EARTH_RADIUS_M * asin(sqrt(h))
        val y = sin(dLng) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return distance.toFloat() to bearing.toFloat()
    }

    /** 방위각·거리를 내 진행 방향 기준 (앞뒤, 좌우) 벡터로 */
    private fun toVector(bearingToOther: Float, myHeading: Float, distance: Float): Pair<Float, Float> {
        val rad = Math.toRadians((bearingToOther - myHeading).toDouble())
        return (distance * cos(rad)).toFloat() to (distance * sin(rad)).toFloat()
    }

    internal fun position(bearingToOther: Float, myHeading: Float, distance: Float): RelativePosition {
        val (forward, lateral) = toVector(bearingToOther, myHeading, distance)
        return positionOf(forward, lateral)
    }

    /**
     * 상대 위치 벡터를 8칸 중 하나로 나눈다.
     *  - 좌우 1.75m 안: 같은 차선 → 앞 / 뒤
     *  - 그 밖: 앞뒤 5m 안이면 바로 옆, 더 앞이면 대각선 앞, 더 뒤면 대각선 뒤
     */
    internal fun positionOf(forward: Float, lateral: Float): RelativePosition {
        if (abs(lateral) <= HALF_LANE) {
            return if (forward >= 0) RelativePosition.FRONT else RelativePosition.REAR
        }
        val right = lateral > 0
        return when {
            abs(forward) <= SIDE_SPAN -> if (right) RelativePosition.RIGHT else RelativePosition.LEFT
            forward > 0 -> if (right) RelativePosition.FRONT_RIGHT else RelativePosition.FRONT_LEFT
            else -> if (right) RelativePosition.REAR_RIGHT else RelativePosition.REAR_LEFT
        }
    }

    /** -180 < 결과 <= 180 */
    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    fun plateKey(plate: String) = plate.filterNot { it.isWhitespace() }
}
