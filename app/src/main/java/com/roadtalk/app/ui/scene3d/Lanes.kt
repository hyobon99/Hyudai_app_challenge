package com.roadtalk.app.ui.scene3d

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 차를 차로 가운데에 놓기. GPS 로 계산한 좌우 위치는 1~2m 씩 틀릴 수 있어서 그대로 그리면 차가 차선을 밟고 달리는 것처럼 보인다.
 * 그래서 내가 달리는 길 위의 차는 가장 가까운 차로의 가운데에 그린다 (내 차도, 주변 차도).
 * 좌표는 내 차 기준 오른쪽 m. 차로 번호는 왼쪽부터 0, 1, 2 …
 */
object Lanes {
    const val WIDTH_M = 3.5f
    /** 옆 차로로 넘어갔다고 보려면 차로 경계를 이만큼 더 넘어가야 한다 (m). 경계 근처에서 왔다 갔다 하지 않게 */
    private const val STICKY_M = 0.5f
    /** 길 가장자리에서 이만큼 벗어난 차까지는 길 위의 차로 본다 (m) */
    private const val EDGE_M = 3.5f
    /** 앞뒤로 1m 멀어질 때마다 더 주는 여유 (m) */
    private const val EDGE_PER_M = 0.06f
    /** 길과 나란한지: 진행 방향이 약 25도 안 (같은 방향이든 맞은편이든) */
    private const val PARALLEL_SIN = 0.42f
    /** 내 길의 차로 보는 좌우 범위 (차로 수) */
    private const val BESIDE_LANES = 2

    /** 길 가운데가 roadCentre 에 있고 차로가 lanes 개일 때, index 번째 차로의 가운데 */
    fun centre(roadCentre: Float, lanes: Int, index: Int): Float = roadCentre + (index + 0.5f - lanes / 2f) * WIDTH_M

    /**
     * lateral 자리의 차가 몇 번째 차로에 있는지 ([first] 번째부터 [last] 번째 차로까지 중에서).
     * previous = 지금까지 있던 차로. 그 차로에서 [STICKY_M] 넘게 벗어나지 않았으면 그대로 둔다.
     */
    fun laneOf(lateral: Float, roadCentre: Float, lanes: Int, previous: Int? = null, first: Int = 0, last: Int = lanes - 1): Int {
        val low = first.coerceIn(0, lanes - 1)
        val high = last.coerceIn(low, lanes - 1)
        if (previous != null && previous in low..high && abs(lateral - centre(roadCentre, lanes, previous)) <= WIDTH_M / 2 + STICKY_M) return previous
        return ((lateral - roadCentre) / WIDTH_M + lanes / 2f - 0.5f).roundToInt().coerceIn(low, high)
    }

    /**
     * 내 옆을 나란히 달리는 차가 나보다 몇 차로 옆에 있는지 (왼쪽 −, 오른쪽 +, 같은 차로 0).
     * [BESIDE_LANES] 차로보다 멀면 내 길의 차가 아닐 수 있어(맞은편 차도, 나란한 옆길) null
     */
    fun beside(lateral: Float): Int? = (lateral / WIDTH_M).roundToInt().takeIf { abs(it) <= BESIDE_LANES }

    /**
     * 주변 차가 길 밖(인도)에 그려지지 않게 내 차로의 범위를 정한다.
     * 지도의 차로 수는 없거나 틀릴 수 있고, 내 차가 그중 어느 차로인지도 GPS 오차만큼 틀릴 수 있다.
     * 반면 '내 왼쪽에 차가 있다' 는 것은 확실하므로, 내 왼쪽으로 [left] 차로 · 오른쪽으로 [right] 차로에 차가 있으면
     * 내 차로는 왼쪽에서 [left] 번째 이상, 오른쪽 끝에서 [right] 번째 이하여야 한다. 지도의 차로 수로 모자라면 차로를 늘린다.
     * first = 내가 달릴 수 있는 가장 왼쪽 차로 (양방향 도로의 중앙선 오른쪽 첫 차로).
     * 돌려주는 값: (그릴 차로 수, 내 차로의 최소, 최대)
     */
    fun fit(mapLanes: Int, first: Int, left: Int, right: Int, maxLanes: Int): Triple<Int, Int, Int> {
        val wanted = maxOf(first, left) + right + 1
        // 지도보다 늘리는 것은 한 차로까지만. 차가 비스듬히 움직일 때(비켜 주기, 차로 변경)는 방향이 틀어져
        // 옆 차가 두세 차로 옆에 있는 것처럼 계산될 수 있는데, 그때마다 길이 넓어졌다 좁아졌다 하지 않게 한다
        val lanes = maxOf(mapLanes, wanted.coerceAtMost(mapLanes + 1)).coerceAtMost(maxLanes)
        val high = (lanes - 1 - right).coerceAtLeast(0)
        val low = maxOf(first, left).coerceAtMost(lanes - 1)
        return Triple(lanes, low.coerceAtMost(maxOf(high, low)), maxOf(high, low))
    }

    /** 내 차(좌우 0)가 lane 번째 차로의 가운데에 오려면 길 가운데가 있어야 하는 자리 */
    fun roadCentreFor(lane: Int, lanes: Int): Float = (lanes / 2f - 0.5f - lane) * WIDTH_M

    /**
     * 이 차를 차로에 맞춰 그릴지: 내 길과 나란히 달리고(방향을 모르면 나란한 것으로 본다), 내 길 위나 바로 곁에 있다.
     * 길 가장자리에서 조금 벗어나게 계산된 차는 가장 바깥 차로에 그린다 (인도 위를 달리는 것처럼 보이지 않게).
     * 멀리 있는 차일수록 좌우 위치가 크게 틀릴 수 있어서(방향이 2도만 틀려도 50m 앞에서는 1.7m) forward 에 따라 여유를 더 준다
     */
    fun onRoad(lateral: Float, relativeHeadingDeg: Float?, roadCentre: Float, lanes: Int, forward: Float = 0f): Boolean {
        if (relativeHeadingDeg != null && abs(sin(Math.toRadians(relativeHeadingDeg.toDouble()))) >= PARALLEL_SIN) return false
        val edge = EDGE_M + abs(forward).coerceAtMost(100f) * EDGE_PER_M
        return abs(lateral - roadCentre) <= lanes * WIDTH_M / 2 + edge
    }
}
