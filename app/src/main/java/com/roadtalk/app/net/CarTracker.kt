package com.roadtalk.app.net

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 내 차와 주변 차의 위치를 칼만 필터로 추적해서, 위치가 1~2초마다 드문드문 들어와도 '지금 이 순간'의 상대 위치를 언제든 계산해 준다.
 *
 *  - 차마다 (동쪽 위치, 동쪽 속도) · (북쪽 위치, 북쪽 속도) 를 추정한다 (등속 모델, 가속은 잡음으로 취급)
 *  - 새 위치가 오면: 그 위치가 측정된 시각까지 예측 → 측정값과 예측값을 오차 크기에 따라 섞는다
 *  - [snapshot]: 마지막 측정 뒤 지난 시간만큼 추정 속도로 이어 그린 위치. 0.1초마다 불러도 매번 조금씩 움직인 값이 나온다
 *  - 보정으로 위치가 바뀔 때는 한 번에 튀지 않고 약 0.3초에 걸쳐 옮겨 간다
 *
 * 시각은 모두 같은 시계(내 기기 시계, ms)로 맞춰서 넣어야 한다. Android 에 의존하지 않아 JVM 단위 테스트가 된다.
 */
internal class CarTracker {

    /** 위치 측정 한 번. timeMillis = 측정된 시각 (내 기기 시계) */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val heading: Float?,
        val speedMps: Float?,
        val timeMillis: Long,
        val accuracyMeters: Float?,
    )

    /** 한 축(동쪽 또는 북쪽)의 위치 p · 속도 v 와 그 불확실성(공분산 pp, pv, vv) */
    private class Axis(var p: Double, var v: Double, var pp: Double, var vv: Double) {
        var pv = 0.0

        /** dt 초 뒤로 예측. 그동안 가속했을 수 있으므로 불확실성이 커진다 (q = 가속도 분산) */
        fun predict(dt: Double, q: Double) {
            p += v * dt
            val dt2 = dt * dt
            pp += 2 * dt * pv + dt2 * vv + q * dt2 * dt2 / 4
            pv += dt * vv + q * dt2 * dt / 2
            vv += q * dt2
        }

        /** 측정된 위치 z (분산 r) 로 보정. 예측이 불확실할수록 측정값 쪽으로 많이 끌려간다 */
        fun update(z: Double, r: Double) {
            val s = pp + r
            val kp = pp / s
            val kv = pv / s
            val innovation = z - p
            p += kp * innovation
            v += kv * innovation
            val pv0 = pv
            pp *= 1 - kp
            pv *= 1 - kp
            vv -= kv * pv0
        }

        fun at(dt: Double) = p + v * dt
    }

    private class Track(var plate: String, val east: Axis, val north: Axis, var time: Long, var lat: Double, var lng: Double) {
        var reportedHeading: Float? = null
        var shownHeading: Float? = null
        var shownAt = 0L
        var cell: RelativePosition? = null
        /** 보정 직전의 상태 (동쪽 위치·속도, 북쪽 위치·속도). 다음 [place] 에서 '보정으로 얼마나 튀었는지' 계산하는 데 쓴다 */
        var before: DoubleArray? = null
        var beforeTime = 0L
        /** 아직 화면에 반영하지 않고 남겨 둔 보정량 (m). 시간이 지나며 0 으로 줄어든다 */
        var offsetEast = 0.0
        var offsetNorth = 0.0
        var offsetAt = 0L
    }

    private var originLat = Double.NaN
    private var originLng = Double.NaN
    private var me: Track? = null
    private val cars = LinkedHashMap<String, Track>()

    /** 내 위치. 모르면 null (그러면 주변 차는 거리 미확인으로 나간다) */
    @Synchronized
    fun onMyFix(fix: Fix?) {
        if (fix == null) {
            me = null
            return
        }
        if (isFarFromOrigin(fix)) reset()
        me = feed(me, "", fix)
    }

    /** 주변 차 한 대의 위치 (서버에서 받은 것). 같은 위치를 다시 받으면 무시한다 */
    @Synchronized
    fun onCarFix(plate: String, fix: Fix) {
        val key = Geo.plateKey(plate)
        cars[key] = feed(cars[key], plate, fix)
    }

    /** 더 이상 근처에 없는 차를 지운다. keys = 지금 근처에 있는 차 (공백 없는 번호) */
    @Synchronized
    fun retainCars(keys: Set<String>) {
        cars.keys.retainAll(keys)
    }

    /** nowMillis 시점의 주변 차 상대 위치. 가까운 순 */
    @Synchronized
    fun snapshot(nowMillis: Long): List<NearbyCar> {
        val mine = me
        if (mine == null) {
            return cars.map { (key, t) -> NearbyCar(key, t.plate, MODEL, -1, RelativePosition.FRONT) }
        }
        val (myEast, myNorth) = place(mine, nowMillis)
        // 정차 중이라 방향을 모르면 북쪽을 보고 있다고 가정
        val myHeading = heading(mine, nowMillis) ?: 0f
        val rad = Math.toRadians(myHeading.toDouble())
        val sinH = sin(rad)
        val cosH = cos(rad)
        return cars.map { (key, t) ->
            val (east, north) = place(t, nowMillis)
            val de = east - myEast
            val dn = north - myNorth
            val forward = (de * sinH + dn * cosH).toFloat()
            val lateral = (de * cosH - dn * sinH).toFloat()
            val cell = steadyCell(t.cell, forward, lateral)
            t.cell = cell
            val theirHeading = heading(t, nowMillis)
            NearbyCar(
                key, t.plate, MODEL, hypot(de, dn).toInt(), cell, forward, lateral,
                theirHeading?.let { normalizeDeg(it - myHeading) },
                latitude = originLat + north / METERS_PER_DEG,
                longitude = originLng + east / (METERS_PER_DEG * cos(Math.toRadians(originLat))),
                headingDeg = theirHeading,
            )
        }.sortedBy { it.distanceMeters }
    }

    /**
     * nowMillis 시점에 그릴 위치 (동쪽, 북쪽 m) = 마지막 측정 뒤 지난 시간만큼 추정 속도로 이어 그린 위치.
     * 새 측정값으로 보정된 직후에는 그 차이만큼 한 번에 튀지 않도록, 차이를 남겨 두었다가 약 0.3초에 걸쳐 줄여 간다.
     */
    private fun place(t: Track, nowMillis: Long): Pair<Double, Double> {
        val ahead = aheadSeconds(t, nowMillis)
        val east = t.east.at(ahead)
        val north = t.north.at(ahead)
        val sinceShown = nowMillis - t.offsetAt
        if (sinceShown > WATCHED_MILLIS) {
            // 한동안 화면에 그린 적이 없으면 이어 붙일 '직전 모습'이 없다 → 보정된 위치를 바로 쓴다
            t.before = null
            t.offsetEast = 0.0
            t.offsetNorth = 0.0
        }
        val keep = exp(-sinceShown.coerceAtLeast(0) / 1000.0 / BLEND_SECONDS)
        t.offsetEast *= keep
        t.offsetNorth *= keep
        t.before?.let { old ->
            // 보정이 없었다면 지금 그렸을 위치 − 보정된 위치
            val oldAhead = (nowMillis - t.beforeTime).coerceIn(0, MAX_AHEAD_MILLIS) / 1000.0
            t.offsetEast += old[0] + old[1] * oldAhead - east
            t.offsetNorth += old[2] + old[3] * oldAhead - north
            t.before = null
        }
        t.offsetAt = nowMillis
        return east + t.offsetEast to north + t.offsetNorth
    }

    private fun feed(existing: Track?, plate: String, fix: Fix): Track {
        val (e, n) = toLocal(fix.lat, fix.lng)
        val sigma = ((fix.accuracyMeters ?: DEFAULT_ACCURACY_M) * JITTER_SHARE).toDouble().coerceIn(MIN_SIGMA_M, MAX_SIGMA_M)
        val r = sigma * sigma
        if (existing == null) return newTrack(plate, fix, e, n, r)
        val gap = fix.timeMillis - existing.time
        if (gap > RESET_GAP_MILLIS) return newTrack(plate, fix, e, n, r)
        existing.plate = plate
        // 이미 반영한 위치(서버가 같은 값을 다시 준 것)거나 더 옛날 위치면 버린다
        val sameSpot = existing.lat == fix.lat && existing.lng == fix.lng
        if (gap <= 0 || (sameSpot && gap < DUPLICATE_MILLIS)) return existing
        // 예측과 너무 다르면(위치가 순간 이동) 이어 붙이지 않고 새로 시작한다
        val dt = gap / 1000.0
        if (hypot(e - existing.east.at(dt), n - existing.north.at(dt)) > JUMP_METERS) return newTrack(plate, fix, e, n, r)
        if (existing.before == null) {
            existing.before = doubleArrayOf(existing.east.p, existing.east.v, existing.north.p, existing.north.v)
            existing.beforeTime = existing.time
        }
        existing.east.predict(dt, ACCEL_VARIANCE)
        existing.north.predict(dt, ACCEL_VARIANCE)
        existing.east.update(e, r)
        existing.north.update(n, r)
        existing.time = fix.timeMillis
        existing.lat = fix.lat
        existing.lng = fix.lng
        existing.reportedHeading = fix.heading ?: existing.reportedHeading
        return existing
    }

    /** 첫 위치: 차가 알려 준 속도·방향이 있으면 그걸로 출발 속도를 잡아서 곧바로 따라붙게 한다 */
    private fun newTrack(plate: String, fix: Fix, e: Double, n: Double, r: Double): Track {
        val speed = fix.speedMps
        val heading = fix.heading
        val known = speed != null && heading != null
        val rad = Math.toRadians((heading ?: 0f).toDouble())
        val ve = if (speed != null && known) speed * sin(rad) else 0.0
        val vn = if (speed != null && known) speed * cos(rad) else 0.0
        val vv = if (known) KNOWN_SPEED_VARIANCE else UNKNOWN_SPEED_VARIANCE
        return Track(plate, Axis(e, ve, r, vv), Axis(n, vn, r, vv), fix.timeMillis, fix.lat, fix.lng).apply {
            reportedHeading = heading
        }
    }

    /** 마지막 측정 뒤 몇 초를 이어 그릴지. 너무 오래 소식이 없으면 더 밀지 않는다 (추측이 틀릴 위험이 커짐) */
    private fun aheadSeconds(t: Track, nowMillis: Long) = (nowMillis - t.time).coerceIn(0, MAX_AHEAD_MILLIS) / 1000.0

    /**
     * 화면에 쓸 진행 방향. 차가 알려 준 방향(FusedLocation heading)을 우선하고, 없으면 추정 속도의 방향을 쓴다.
     * 방향이 한 번에 꺾이면 화면 전체가 툭 돌아가므로 약 0.4초에 걸쳐 따라가게 한다.
     */
    private fun heading(t: Track, nowMillis: Long): Float? {
        val speed = hypot(t.east.v, t.north.v)
        val target = t.reportedHeading
            ?: if (speed >= MIN_SPEED_FOR_HEADING) ((Math.toDegrees(atan2(t.east.v, t.north.v)) + 360) % 360).toFloat() else null
        val shown = t.shownHeading
        val next = when {
            target == null -> shown
            shown == null -> target
            else -> {
                val dt = ((nowMillis - t.shownAt) / 1000f).coerceIn(0f, 1f)
                (shown + normalizeDeg(target - shown) * (1f - exp(-dt / HEADING_SECONDS)) + 360f) % 360f
            }
        }
        t.shownHeading = next
        t.shownAt = nowMillis
        return next
    }

    /** 칸의 경계에 걸친 차가 '앞차 ↔ 왼쪽 앞 차' 로 깜빡이지 않게, 직전 칸에서 0.4m 이상 벗어나야 칸을 바꾼다 */
    private fun steadyCell(previous: RelativePosition?, forward: Float, lateral: Float): RelativePosition {
        val now = Geo.positionOf(forward, lateral)
        if (previous == null || now == previous) return now
        for (df in floatArrayOf(-CELL_MARGIN_M, 0f, CELL_MARGIN_M)) {
            for (dl in floatArrayOf(-CELL_MARGIN_M, 0f, CELL_MARGIN_M)) {
                if (Geo.positionOf(forward + df, lateral + dl) == previous) return previous
            }
        }
        return now
    }

    /** 위·경도를 기준점에서 동쪽·북쪽으로 몇 m 인지로 바꾼다 (수 km 안에서는 평면으로 봐도 오차가 cm 수준) */
    private fun toLocal(lat: Double, lng: Double): Pair<Double, Double> {
        if (originLat.isNaN()) {
            originLat = lat
            originLng = lng
        }
        return (lng - originLng) * METERS_PER_DEG * cos(Math.toRadians(originLat)) to (lat - originLat) * METERS_PER_DEG
    }

    private fun isFarFromOrigin(fix: Fix): Boolean {
        if (originLat.isNaN()) return false
        return abs(fix.lat - originLat) * METERS_PER_DEG > REORIGIN_METERS ||
            abs(fix.lng - originLng) * METERS_PER_DEG * cos(Math.toRadians(originLat)) > REORIGIN_METERS
    }

    /** 기준점에서 너무 멀어지면 평면 근사가 틀어지므로 처음부터 다시 (몇 초 안에 다시 따라붙는다) */
    private fun reset() {
        originLat = Double.NaN
        originLng = Double.NaN
        me = null
        cars.clear()
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    private companion object {
        const val MODEL = "현대차"
        const val METERS_PER_DEG = 111_194.93
        /** 운전 중 가속·감속 크기 (m/s², 급제동 3~4 까지 따라가도록 2.5) 의 제곱. 클수록 새 측정값을 빨리 따라가고, 작을수록 매끈하지만 굼뜨다 */
        const val ACCEL_VARIANCE = 2.5 * 2.5
        /**
         * accuracy(오차 반경) 중 '매번 달라지는 흔들림'으로 보는 비율.
         * GPS 오차의 대부분은 수십 초에 걸쳐 천천히 변하는 치우침이라 필터로는 못 없앤다 → 흔들림만 걸러낸다
         */
        const val JITTER_SHARE = 0.3f
        const val DEFAULT_ACCURACY_M = 5f
        const val MIN_SIGMA_M = 1.0
        const val MAX_SIGMA_M = 5.0
        const val KNOWN_SPEED_VARIANCE = 3.0 * 3.0
        const val UNKNOWN_SPEED_VARIANCE = 15.0 * 15.0
        /** 이만큼 소식이 끊겼다 다시 오면 이전 속도를 믿지 않고 새로 시작 */
        const val RESET_GAP_MILLIS = 10_000L
        const val DUPLICATE_MILLIS = 400L
        const val JUMP_METERS = 40.0
        const val MAX_AHEAD_MILLIS = 5_000L
        const val MIN_SPEED_FOR_HEADING = 2.0
        const val HEADING_SECONDS = 0.4f
        /** 새 측정값으로 생긴 보정을 화면에 나눠 반영하는 시간 */
        const val BLEND_SECONDS = 0.3
        /** 이 안에 [snapshot] 이 불렸어야 '화면에 그리던 중'으로 본다 */
        const val WATCHED_MILLIS = 1_000L
        const val CELL_MARGIN_M = 0.4f
        const val REORIGIN_METERS = 20_000.0
    }
}
