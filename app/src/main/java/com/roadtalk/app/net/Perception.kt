package com.roadtalk.app.net

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * '주변 차' 로 볼 범위. 차의 카메라가 방향마다 알아보는 거리와 같게 잡았다 (운전자가 화면에서 기대하는 '내 주변'):
 *   전방 협각 250m · 전방 메인 150m · 전방 광각 60m · 전측방 80m · 후측방 100m · 후방 50m
 * 실제 카메라를 쓰는 것은 아니고, 서버에서 받은 차 가운데 이 범위 안의 차만 주변 차로 보여 주고 메시지 대상으로 삼는다.
 */
object Perception {
    private const val NARROW_HALF_DEG = 17.5f     // 전방 협각 카메라가 보는 폭의 절반
    private const val MAIN_HALF_DEG = 25f         // 전방 메인 카메라
    private const val FRONT_SIDE_UNTIL_DEG = 100f // 여기까지는 전방 광각·전측방 카메라
    private const val REAR_SIDE_UNTIL_DEG = 160f  // 여기까지는 후측방 카메라, 그 뒤는 후방 카메라

    /** 내 차에서 (앞 forward m, 오른쪽 lateral m) 방향으로 몇 m 까지를 주변으로 보는지 */
    fun rangeToward(forward: Float, lateral: Float): Float {
        val angle = Math.toDegrees(atan2(abs(lateral).toDouble(), forward.toDouble())).toFloat()   // 0 = 정면, 180 = 바로 뒤
        return when {
            angle <= NARROW_HALF_DEG -> 250f
            angle <= MAIN_HALF_DEG -> 150f
            angle <= FRONT_SIDE_UNTIL_DEG -> 80f
            angle <= REAR_SIDE_UNTIL_DEG -> 100f
            else -> 50f
        }
    }

    fun sees(forward: Float, lateral: Float): Boolean = hypot(forward, lateral) <= rangeToward(forward, lateral)

    /** 위치를 모르는 차(좌표를 안 보낸 차)는 걸러 내지 않는다 */
    fun sees(car: NearbyCar): Boolean {
        val f = car.forwardMeters ?: return true
        val l = car.lateralMeters ?: return true
        return car.distanceMeters < 0 || sees(f, l)
    }
}
