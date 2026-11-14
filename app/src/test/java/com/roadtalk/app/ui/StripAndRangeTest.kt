package com.roadtalk.app.ui

import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.Perception
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.ui.scene3d.StripBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 교차하는 길을 바닥 띠로 만드는 계산과, '주변 차' 로 볼 범위 */
class StripAndRangeTest {

    private fun heightsUsed(b: StripBuilder): Set<Float> = (0 until b.size / 8).map { b.data[it * 8 + 1] }.toSet()

    private fun area(b: StripBuilder, y: Float): Float {
        var sum = 0f
        for (t in 0 until b.size / 24) {
            val o = t * 24
            if (b.data[o + 1] != y) continue
            val ax = b.data[o]; val az = b.data[o + 2]
            val bx = b.data[o + 8]; val bz = b.data[o + 10]
            val cx = b.data[o + 16]; val cz = b.data[o + 18]
            sum += kotlin.math.abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax)) / 2
        }
        return sum
    }

    @Test
    fun roadCrossingMineIsLaidAtThreeHeights() {
        // 내 길을 직각으로 가로지르는 폭 8m 길 (왼쪽 40m ~ 오른쪽 40m, 앞 30m 지점)
        val b = StripBuilder()
        b.strip(-40f, -30f, 40f, -30f, 4f)
        assertEquals(setOf(StripBuilder.GROUND_Y, StripBuilder.OVER_WALK_Y, StripBuilder.ON_LANES_Y), heightsUsed(b))
        // 차도 위 부분: 내 차도 폭(2 × 5.64m) × 길 폭 8m
        assertEquals(2 * StripBuilder.LANES_HALF * 8f, area(b, StripBuilder.ON_LANES_Y), 0.05f)
        // 인도 위 부분: 양쪽 인도 폭(7.31 − 5.64) × 8m
        assertEquals(2 * (StripBuilder.WALK_HALF - StripBuilder.LANES_HALF) * 8f, area(b, StripBuilder.OVER_WALK_Y), 0.05f)
        assertTrue(b.size % 24 == 0)
    }

    @Test
    fun roadAwayFromMineStaysOnTheGround() {
        val b = StripBuilder()
        b.strip(30f, -10f, 30f, -80f, 3f)      // 오른쪽 30m 의 나란한 길
        assertEquals(setOf(StripBuilder.GROUND_Y), heightsUsed(b))
        // 폭 6m × (길이 70m + 양 끝을 늘인 만큼)
        assertEquals(6f * (70f + 2 * 3f * 0.6f), area(b, StripBuilder.GROUND_Y), 0.05f)
    }

    @Test
    fun resetStartsOver() {
        val b = StripBuilder()
        b.strip(-40f, -30f, 40f, -30f, 4f)
        assertTrue(b.size > 0)
        b.reset()
        assertEquals(0, b.size)
        assertEquals(StripBuilder.ON_LANES_Y, StripBuilder.heightAt(0f), 0f)
        assertEquals(StripBuilder.OVER_WALK_Y, StripBuilder.heightAt(-6.5f), 0f)
        assertEquals(StripBuilder.GROUND_Y, StripBuilder.heightAt(20f), 0f)
    }

    // ---- 주변 차로 볼 범위 (카메라 인식 거리 표)

    @Test
    fun rangeDependsOnTheDirection() {
        assertEquals(250f, Perception.rangeToward(100f, 0f), 0f)        // 정면 (협각)
        assertEquals(150f, Perception.rangeToward(100f, 40f), 0f)       // 정면에서 약 22도 (메인)
        assertEquals(80f, Perception.rangeToward(30f, 30f), 0f)         // 앞 옆 45도 (광각·전측방)
        assertEquals(80f, Perception.rangeToward(0f, -20f), 0f)         // 바로 옆
        assertEquals(100f, Perception.rangeToward(-30f, 30f), 0f)       // 뒤 옆 (후측방)
        assertEquals(50f, Perception.rangeToward(-30f, 0f), 0f)         // 바로 뒤 (후방)
    }

    @Test
    fun onlyCarsInsideTheRangeAreNearby() {
        assertTrue(Perception.sees(240f, 0f))
        assertFalse(Perception.sees(260f, 0f))
        assertTrue(Perception.sees(-45f, 0f))
        assertFalse(Perception.sees(-60f, 0f))
        assertTrue(Perception.sees(20f, 40f))          // 옆길 44m
        assertFalse(Perception.sees(212f, 253f))       // 북동쪽 330m
        assertTrue(Perception.sees(-60f, 60f))         // 뒤 옆 85m
    }

    @Test
    fun carsWithoutAPositionAreKept() {
        val unknown = NearbyCar("k", "12가 3456", "현대차", -1, RelativePosition.FRONT)
        assertTrue(Perception.sees(unknown))
        val far = NearbyCar("k", "12가 3456", "현대차", 300, RelativePosition.FRONT, forwardMeters = 300f, lateralMeters = 0f)
        assertFalse(Perception.sees(far))
        val near = far.copy(distanceMeters = 99, forwardMeters = 99f)
        assertTrue(Perception.sees(near))
    }
}
