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
    private const val EDGE_M = 1.2f
    /** 길과 나란한지: 진행 방향이 약 25도 안 (같은 방향이든 맞은편이든) */
    private const val PARALLEL_SIN = 0.42f

    /** 길 가운데가 roadCentre 에 있고 차로가 lanes 개일 때, index 번째 차로의 가운데 */
    fun centre(roadCentre: Float, lanes: Int, index: Int): Float = roadCentre + (index + 0.5f - lanes / 2f) * WIDTH_M

    /**
     * lateral 자리의 차가 몇 번째 차로에 있는지 ([first] 번째부터 마지막 차로까지 중에서).
     * previous = 지금까지 있던 차로. 그 차로에서 [STICKY_M] 넘게 벗어나지 않았으면 그대로 둔다.
     */
    fun laneOf(lateral: Float, roadCentre: Float, lanes: Int, previous: Int? = null, first: Int = 0): Int {
        val low = first.coerceIn(0, lanes - 1)
        if (previous != null && previous in low until lanes && abs(lateral - centre(roadCentre, lanes, previous)) <= WIDTH_M / 2 + STICKY_M) return previous
        return ((lateral - roadCentre) / WIDTH_M + lanes / 2f - 0.5f).roundToInt().coerceIn(low, lanes - 1)
    }

    /** 내 차(좌우 0)가 lane 번째 차로의 가운데에 오려면 길 가운데가 있어야 하는 자리 */
    fun roadCentreFor(lane: Int, lanes: Int): Float = (lanes / 2f - 0.5f - lane) * WIDTH_M

    /** 이 차를 차로에 맞춰 그릴지: 내 길과 나란히 달리고(방향을 모르면 나란한 것으로 본다), 내 길의 폭 안에 있다 */
    fun onRoad(lateral: Float, relativeHeadingDeg: Float?, roadCentre: Float, lanes: Int): Boolean {
        if (relativeHeadingDeg != null && abs(sin(Math.toRadians(relativeHeadingDeg.toDouble()))) >= PARALLEL_SIN) return false
        return abs(lateral - roadCentre) <= lanes * WIDTH_M / 2 + EDGE_M
    }
}
