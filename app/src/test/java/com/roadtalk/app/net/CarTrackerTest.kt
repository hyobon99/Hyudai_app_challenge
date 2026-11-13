package com.roadtalk.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class CarTrackerTest {

    private val lat0 = 37.5
    private val lng0 = 127.03
    private val metersPerDeg = 111_194.93

    /** 기준점에서 북쪽 north m · 동쪽 east m 인 위치 (북쪽을 보고 달리는 차) */
    private fun fix(north: Double, east: Double, time: Long, speed: Float? = null, heading: Float? = 0f) = CarTracker.Fix(
        lat = lat0 + north / metersPerDeg,
        lng = lng0 + east / (metersPerDeg * Math.cos(Math.toRadians(lat0))),
        heading = heading,
        speedMps = speed,
        timeMillis = time,
        accuracyMeters = 5f,
    )

    private fun CarTracker.forward(now: Long) = snapshot(now).single().forwardMeters!!

    @Test
    fun fillsTheGapBetweenFixesAtTenHertz() {
        // 내 차는 서 있고, 20m 앞의 차가 초속 10m 로 멀어진다. 위치는 1초에 한 번만 들어온다
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        for (s in 0..5) tracker.onCarFix("12가 3690", fix(20.0 + 10.0 * s, 0.0, s * 1000L, speed = 10f))
        // 다음 위치가 오기 전 1초 동안 0.1초마다 물어보면 매번 약 1m 씩 나아가 있어야 한다
        var previous = tracker.forward(5_000)
        assertEquals(70f, previous, 0.3f)
        for (tick in 1..10) {
            val now = tracker.forward(5_000 + tick * 100L)
            assertEquals("tick $tick", 1f, now - previous, 0.1f)
            previous = now
        }
        assertEquals(80f, previous, 0.5f)
    }

    @Test
    fun correctionWhenNextFixArrivesIsSmall() {
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        for (s in 0..5) tracker.onCarFix("12가 3690", fix(20.0 + 10.0 * s, 0.0, s * 1000L, speed = 10f))
        val before = tracker.forward(6_000)
        tracker.onCarFix("12가 3690", fix(80.0, 0.0, 6_000, speed = 10f))
        // 이어 그린 위치가 실제와 거의 같았으므로 새 위치가 와도 차가 튀지 않는다
        assertEquals(before, tracker.forward(6_000), 0.2f)
    }

    @Test
    fun bothCarsAreComparedAtTheSameMoment() {
        // 둘 다 초속 15m, 앞차가 12m 앞. 앞차의 위치는 서버를 거쳐 1.5초 늦게 도착한다 → 그래도 간격은 12m 로 보여야 한다
        val tracker = CarTracker()
        for (s in 0..8) {
            val t = s * 1000L
            tracker.onMyFix(fix(15.0 * s, 0.0, t, speed = 15f))
            if (s >= 2) {
                // 지금 도착한 것은 1.5초 전에 측정된 위치
                val measuredAt = t - 1500
                tracker.onCarFix("12가 3690", fix(12.0 + 15.0 * measuredAt / 1000.0, 0.0, measuredAt, speed = 15f))
            }
        }
        for (tick in 0..10) assertEquals(12f, tracker.forward(8_000 + tick * 100L), 0.5f)
    }

    @Test
    fun steadierThanExtendingTheLastTwoFixes() {
        // 초속 10m 로 달리는 차의 위치에 매번 1.5m 안팎의 흔들림을 섞는다.
        // 예전 방식(직전 두 위치로 속도를 구해 그대로 이어 그리기)과, 위치가 온 지 0.5초 뒤에 그린 위치의 오차를 비교한다
        val random = Random(7)
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        var naiveSq = 0.0
        var filteredSq = 0.0
        var count = 0
        var previousNoisy = 0.0
        for (s in 0..60) {
            val noisy = 10.0 * s + random.nextGaussian() * 1.5
            tracker.onCarFix("12가 3690", fix(noisy, 0.0, s * 1000L, speed = if (s == 0) 10f else null))
            var shown = 0f
            for (tick in 0..5) shown = tracker.forward(s * 1000L + tick * 100L)
            if (s >= 10) {
                val truth = 10.0 * (s + 0.5)
                val naive = noisy + (noisy - previousNoisy) * 0.5
                naiveSq += (naive - truth) * (naive - truth)
                filteredSq += (shown - truth) * (shown - truth)
                count++
            }
            previousNoisy = noisy
        }
        val naive = sqrt(naiveSq / count)
        val filtered = sqrt(filteredSq / count)
        assertTrue("naive=$naive filtered=$filtered", filtered < naive * 0.8)
    }

    @Test
    fun correctionIsSpreadOverSeveralTicks() {
        // 초속 10m 로 가던 앞차가 급히 멈췄다. 다음 위치가 오면 이어 그리던 자리(70m)와 실제(60m)가 10m 나 다르다
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        for (s in 0..4) tracker.onCarFix("12가 3690", fix(20.0 + 10.0 * s, 0.0, s * 1000L, speed = 10f))
        var previous = 0f
        for (tick in 0..10) previous = tracker.forward(4_000 + tick * 100L)
        // 그래도 화면의 차는 한 번에 튀지 않고 0.1초에 조금씩 옮겨 가서, 몇 초 안에 실제 자리에 멈춘다
        var largestStep = 0f
        for (s in 5..9) {
            tracker.onCarFix("12가 3690", fix(60.0, 0.0, s * 1000L, speed = 0f))
            for (tick in 1..10) {
                val now = tracker.forward(s * 1000L + tick * 100L)
                largestStep = maxOf(largestStep, abs(now - previous))
                previous = now
            }
        }
        assertTrue("largestStep=$largestStep", largestStep < 2.5f)
        assertTrue("after 5s=$previous", abs(previous - 60f) < 1.5f)
    }

    @Test
    fun sameFixTwiceIsIgnored() {
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        for (s in 0..3) tracker.onCarFix("12가 3690", fix(20.0 + 10.0 * s, 0.0, s * 1000L, speed = 10f))
        val before = tracker.snapshot(3_500)
        // 서버가 같은 위치를 다시 줌 (시계 보정이 조금 바뀌어 시각이 몇 ms 달라질 수 있다)
        tracker.onCarFix("12가 3690", fix(50.0, 0.0, 3_004, speed = 10f))
        assertEquals(before, tracker.snapshot(3_500))
    }

    @Test
    fun teleportStartsOver() {
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        for (s in 0..3) tracker.onCarFix("12가 3690", fix(20.0 + 10.0 * s, 0.0, s * 1000L, speed = 10f))
        // 시연 스크립트가 차를 다른 곳으로 옮겼을 때처럼 위치가 갑자기 바뀌면 끌려가지 않고 그 자리에서 다시 시작
        tracker.onCarFix("12가 3690", fix(-200.0, 0.0, 4_000, speed = 0f))
        assertEquals(-200f, tracker.forward(4_000), 0.1f)
        assertEquals(-200f, tracker.forward(4_500), 0.1f)
    }

    @Test
    fun cellDoesNotFlickerAtTheLaneEdge() {
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 0f))
        fun cellAt(east: Double, time: Long): RelativePosition {
            tracker.onCarFix("12가 3690", fix(15.0, east, time, speed = 0f))
            return tracker.snapshot(time).single().position
        }
        var t = 0L
        // 같은 차로(좌우 1.75m 안)에 있던 차가 경계(1.75m)를 살짝 넘는 동안은 '앞차' 그대로
        repeat(8) { assertEquals(RelativePosition.FRONT, cellAt(1.5, t)); t += 1000 }
        repeat(8) { t += 1000; cellAt(1.85, t) }
        assertEquals(RelativePosition.FRONT, tracker.snapshot(t).single().position)
        // 확실히 옆 차로로 넘어가면 바뀐다
        repeat(8) { t += 1000; cellAt(3.5, t) }
        assertEquals(RelativePosition.FRONT_RIGHT, tracker.snapshot(t).single().position)
    }

    @Test
    fun unknownMyPositionGivesUnknownDistance() {
        val tracker = CarTracker()
        tracker.onCarFix("12가 3690", fix(20.0, 0.0, 0))
        val car = tracker.snapshot(100).single()
        assertEquals(-1, car.distanceMeters)
        assertEquals("12가 3690", car.plate)
        tracker.retainCars(emptySet())
        assertTrue(tracker.snapshot(200).isEmpty())
    }

    @Test
    fun headingTurnsGraduallyNotAtOnce() {
        // 내 차가 북쪽을 보다가 동쪽(90°)으로 돈 위치가 들어와도, 화면 기준 방향은 0.4초쯤에 걸쳐 따라간다
        val tracker = CarTracker()
        tracker.onMyFix(fix(0.0, 0.0, 0, speed = 5f, heading = 0f))
        tracker.onCarFix("12가 3690", fix(20.0, 0.0, 0, speed = 0f))
        for (tick in 0..10) tracker.snapshot(tick * 100L)   // 화면은 0.1초마다 물어본다
        tracker.onMyFix(fix(5.0, 0.0, 1_000, speed = 5f, heading = 90f))
        val first = tracker.snapshot(1_100).single()
        val later = tracker.snapshot(3_000).single()
        // 직후에는 아직 대부분 앞쪽, 충분히 지나면 왼쪽 (북쪽에 있는 차는 동쪽을 보는 내 왼쪽. 그동안 내 차도 계속 나아가 5m 까지 가까워진다)
        assertTrue("first=$first", first.forwardMeters!! > abs(first.lateralMeters!!))
        assertTrue("later=$later", later.lateralMeters!! < -3f && abs(later.forwardMeters!!) < 2f)
    }
}
