package com.roadtalk.app.net

import com.roadtalk.app.chat.VoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionTest {

    /** 북쪽(0°)을 보고 있을 때, 오른쪽 dx m · 앞쪽 dy m 에 있는 차 */
    private fun at(dx: Double, dy: Double): RelativePosition {
        val distance = Math.hypot(dx, dy).toFloat()
        val bearing = Math.toDegrees(Math.atan2(dx, dy)).toFloat()
        return Geo.position(bearing, 0f, distance)
    }

    @Test
    fun sameLaneIsFrontOrRear() {
        assertEquals(RelativePosition.FRONT, at(0.5, 15.0))
        assertEquals(RelativePosition.REAR, at(-1.0, -10.0))
    }

    @Test
    fun besideMeIsLeftOrRight() {
        assertEquals(RelativePosition.RIGHT, at(3.5, 2.0))
        assertEquals(RelativePosition.LEFT, at(-3.5, -3.0))
    }

    @Test
    fun diagonals() {
        assertEquals(RelativePosition.FRONT_RIGHT, at(3.5, 12.0))
        assertEquals(RelativePosition.FRONT_LEFT, at(-3.5, 12.0))
        assertEquals(RelativePosition.REAR_RIGHT, at(3.5, -12.0))
        assertEquals(RelativePosition.REAR_LEFT, at(-3.5, -12.0))
    }

    @Test
    fun headingRotatesTheGrid() {
        // 동쪽(90°)을 보고 있으면 북쪽에 있는 차는 왼쪽
        assertEquals(RelativePosition.LEFT, Geo.position(0f, 90f, 3.5f))
    }

    @Test
    fun staleFixIsProjectedForward() {
        // 북쪽으로 20m/s 로 달리는 차의 2초 전 위치 → 지금은 40m 앞
        val me = Geo.Mover(37.0, 127.0, heading = 0f, speedMps = 0f, ageMillis = 0)
        val other = Geo.Mover(37.0, 127.0, heading = 0f, speedMps = 20f, ageMillis = 2_000)
        val r = Geo.relative(me, other)
        assertEquals(RelativePosition.FRONT, r.position)
        assertTrue("distance=${r.distanceMeters}", r.distanceMeters in 38..42)
    }

    @Test
    fun bothCarsProjectedToSameMoment() {
        // 둘 다 20m/s 북쪽, 상대 위치는 3초 묵었고 내 위치는 1초 묵음 → 보정 후 같은 지점(앞 0m)
        val me = Geo.Mover(37.0, 127.0, heading = 0f, speedMps = 20f, ageMillis = 1_000)
        val other = Geo.Mover(37.0 - 40 / 111_320.0, 127.0, heading = 0f, speedMps = 20f, ageMillis = 3_000)
        assertTrue(Geo.relative(me, other).distanceMeters <= 1)
    }

    @Test
    fun voiceDiagonalWinsOverPlainRear() {
        val cars = listOf(
            NearbyCar("1", "12가 3456", "현대차", 10, RelativePosition.REAR),
            NearbyCar("2", "34나 7890", "현대차", 12, RelativePosition.REAR_RIGHT),
        )
        val c = VoiceCommandParser.parse("오른쪽 뒤차한테 고마워요", cars)
        assertEquals("34나 7890", c.target?.plate)
        assertEquals("고마워요", c.body)
        assertEquals("12가 3456", VoiceCommandParser.parse("뒷차한테 죄송합니다", cars).target?.plate)
    }
}
