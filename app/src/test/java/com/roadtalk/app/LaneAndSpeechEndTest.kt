package com.roadtalk.app

import com.roadtalk.app.map.OsmRoads
import com.roadtalk.app.ui.scene3d.Lanes
import com.roadtalk.app.voice.Endpointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차를 차로 가운데에 놓기, 방향을 모를 때 도로 방향 쓰기, 말이 끝났는지 판단하기 */
class LaneAndSpeechEndTest {

    @Test
    fun myCarAlwaysSitsOnALaneCentre() {
        // 3차로: 길 가운데가 내 차에서 오른쪽 1.2m 라고 측정됐다 → 가운데 차로(1)로 보고, 길 가운데를 내 차 자리(0)에 둔다
        val lane = Lanes.laneOf(0f, 1.2f, 3)
        assertEquals(1, lane)
        assertEquals(0f, Lanes.roadCentreFor(lane, 3), 0.001f)
        // 4차로: 길 가운데가 바로 내 자리라고 측정되면(차선 위) 한쪽 차로로 붙인다 → 내 차는 차선에서 반 차로(1.75m) 떨어진다
        val even = Lanes.laneOf(0f, 0f, 4)
        assertEquals(1.75f, kotlin.math.abs(Lanes.roadCentreFor(even, 4)), 0.001f)
        // 어떤 경우든 내 차 자리(0)가 그 차로의 가운데다
        for (lanes in 1..8) for (k in 0 until lanes) {
            assertEquals(0f, Lanes.centre(Lanes.roadCentreFor(k, lanes), lanes, k), 0.001f)
        }
    }

    @Test
    fun onATwoWayRoadMyCarStaysRightOfTheCentreLine() {
        // 양방향 4차로(중앙선 = 2번째 경계). 측정이 맞은편 차로 쪽으로 틀려도 내 차는 중앙선 오른쪽 차로(2, 3)에 놓인다
        assertEquals(2, Lanes.laneOf(0f, 4f, 4, first = 2))
        assertEquals(3, Lanes.laneOf(0f, -6f, 4, first = 2))
    }

    @Test
    fun otherCarsSnapToTheNearestLaneCentre() {
        // 3차로, 길 가운데 = 0 → 차로 가운데는 -3.5, 0, 3.5
        assertEquals(-3.5f, Lanes.centre(0f, 3, 0), 0.001f)
        assertEquals(3.5f, Lanes.centre(0f, 3, 2), 0.001f)
        assertEquals(2, Lanes.laneOf(2.4f, 0f, 3))     // 차선(1.75)을 넘어 오른쪽 차로 쪽
        assertEquals(1, Lanes.laneOf(1.2f, 0f, 3))
        assertEquals(0, Lanes.laneOf(-9f, 0f, 3))      // 길 밖으로 벗어난 값은 가장 바깥 차로
    }

    @Test
    fun carsBesideMeAlwaysFitOnTheRoad() {
        // 지도는 3차로, 내 자리는 맨 왼쪽 차로로 측정됐는데 내 왼쪽 차로에 차가 있다 → 나는 왼쪽에서 두 번째 차로여야 한다
        val (lanes, low, high) = Lanes.fit(mapLanes = 3, first = 0, left = 1, right = 0, maxLanes = 10)
        assertEquals(3, lanes)
        val mine = Lanes.laneOf(0f, 3.5f, lanes, first = low, last = high)      // 측정만 보면 0번 차로
        assertEquals(1, mine)
        // 그러면 왼쪽 3.5m 의 차는 0번 차로 가운데에 놓인다 (길 안)
        val centre = Lanes.roadCentreFor(mine, lanes)
        assertTrue(Lanes.onRoad(-3.5f, 0f, centre, lanes))
        assertEquals(0, Lanes.laneOf(-3.5f, centre, lanes))
        // 양옆에 차가 있으면 가운데 차로
        assertEquals(Triple(3, 1, 1), Lanes.fit(3, 0, 1, 1, 10))
        // 지도의 차로 수로 모자라면 늘린다: 왼쪽 1 · 오른쪽 2 차로에 차가 있으면 4차로
        assertEquals(Triple(4, 1, 1), Lanes.fit(3, 0, 1, 2, 10))
        // 다만 지도보다 한 차로까지만 늘린다: 한 차로 골목에서 옆 차가 두 차로 옆으로 계산돼도 두 차로
        assertEquals(2, Lanes.fit(1, 0, 2, 0, 10).first)
        assertEquals(Triple(2, 1, 1), Lanes.fit(1, 0, 1, 0, 10))
        // 옆에 차가 없으면 지도 그대로
        assertEquals(Triple(3, 0, 2), Lanes.fit(3, 0, 0, 0, 10))
        // 양방향 4차로(중앙선 오른쪽 = 2번부터)에서 오른쪽에 차가 있으면 2번 차로
        assertEquals(Triple(4, 2, 2), Lanes.fit(4, 2, 0, 1, 10))
    }

    @Test
    fun onlyNearbyLanesCountAsBesideMe() {
        assertEquals(-1, Lanes.beside(-3.3f))
        assertEquals(0, Lanes.beside(1.2f))
        assertEquals(2, Lanes.beside(7.4f))
        assertNull("맞은편 차도처럼 먼 차는 내 길의 차로 보지 않는다", Lanes.beside(18f))
    }

    @Test
    fun laneDoesNotFlipNearTheLine() {
        // 가운데 차로에 있던 차가 차선(1.75)을 조금 넘어도 그대로, 0.5m 넘게 넘어가면 옆 차로
        assertEquals(1, Lanes.laneOf(2.0f, 0f, 3, previous = 1))
        assertEquals(2, Lanes.laneOf(2.4f, 0f, 3, previous = 1))
    }

    @Test
    fun onlyCarsAlongMyRoadAreSnapped() {
        assertTrue(Lanes.onRoad(3f, 4f, 0f, 3))
        assertTrue("맞은편에서 오는 차도 나란하다", Lanes.onRoad(-3f, 178f, 0f, 3))
        assertTrue("방향을 모르면 나란한 것으로 본다", Lanes.onRoad(3f, null, 0f, 3))
        assertFalse("가로지르는 차", Lanes.onRoad(3f, 90f, 0f, 3))
        assertFalse("옆길의 차", Lanes.onRoad(14f, 0f, 0f, 3))
        // 길 가장자리에서 조금 벗어나게 계산된 차는 길 위의 차로 보고 가장 바깥 차로에 놓는다 (인도 위에 그리지 않는다)
        assertTrue(Lanes.onRoad(-4.5f, 180f, 0f, 1))
        assertEquals(0, Lanes.laneOf(-4.5f, 0f, 1))
        // 멀리 있는 차는 좌우 위치가 더 틀릴 수 있어 여유를 더 준다
        assertFalse(Lanes.onRoad(7f, 0f, 0f, 1))
        assertTrue(Lanes.onRoad(7f, 0f, 0f, 1, forward = 50f))
    }

    @Test
    fun unknownHeadingFollowsTheNearestRoad() {
        // 동북동(약 70도)으로 뻗은 길과, 멀리 떨어진 남북 길
        val along = OsmRoads.Road(1, floatArrayOf(-94f, -34.2f, 94f, 34.2f), 5, true, 16.5f)
        val far = OsmRoads.Road(2, floatArrayOf(300f, -100f, 300f, 100f), 2, false, 6.6f)
        assertEquals(70f, OsmRoads.bearingAt(1f, 2f, listOf(far, along))!!, 0.5f)
        // 거꾸로 그려진 길이면 그 방향 (0~360 으로)
        val reversed = OsmRoads.Road(3, floatArrayOf(94f, 34.2f, -94f, -34.2f), 5, true, 16.5f)
        assertEquals(250f, OsmRoads.bearingAt(1f, 2f, listOf(reversed))!!, 0.5f)
        assertNull("가까운 길이 없으면 모른다", OsmRoads.bearingAt(1f, 2f, listOf(far)))
    }

    @Test
    fun headingFollowsTheRoadWhileChangingLanes() {
        // 동북동(약 70도)으로 뻗은 길 위에서
        val road = OsmRoads.Road(1, floatArrayOf(-94f, -34.2f, 94f, 34.2f), 5, true, 16.5f)
        val roads = listOf(road)
        // 차로를 바꾸느라 이동 방향이 25도 틀어져도 길의 방향
        assertEquals(70f, OsmRoads.alignedHeading(95f, 1f, 2f, roads), 0.5f)
        assertEquals(70f, OsmRoads.alignedHeading(48f, 1f, 2f, roads), 0.5f)
        // 길이 그려진 방향과 반대로 달리면 반대 방향 (250도)
        assertEquals(250f, OsmRoads.alignedHeading(262f, 1f, 2f, roads), 0.5f)
        // 길을 가로지르는 중(90도쯤 다름)이면 이동 방향 그대로
        assertEquals(160f, OsmRoads.alignedHeading(160f, 1f, 2f, roads), 0.01f)
        // 가까운 길이 없으면 이동 방향 그대로
        assertEquals(95f, OsmRoads.alignedHeading(95f, 200f, 300f, roads), 0.01f)
        assertEquals(10f, OsmRoads.angleBetween(355f, 5f), 0.01f)
    }

    @Test
    fun listeningEndsAfterAPauseInSpeech() {
        val end = Endpointer(silenceMillis = 1_500, noSpeechMillis = 6_000)
        end.begin(0)
        assertFalse(end.isOver(3_000))               // 아직 아무 말도 안 했다: 6초까지 기다린다
        end.heard("뒷차한테", 3_200)
        end.heard("뒷차한테 고맙다고", 3_900)
        assertFalse("말하는 중", end.isOver(4_500))
        end.heard("뒷차한테 고맙다고 ", 5_000)        // 공백만 달라진 것은 새 말이 아니다
        assertFalse(end.isOver(5_399))
        assertTrue("1.5초 동안 더 말이 없으면 끝", end.isOver(5_400))
    }

    @Test
    fun listeningEndsWhenNothingIsSaid() {
        val end = Endpointer(silenceMillis = 1_500, noSpeechMillis = 6_000)
        end.begin(1_000)
        assertFalse(end.isOver(6_900))
        assertTrue(end.isOver(7_000))
        // 다시 시작하면 처음부터
        end.begin(10_000)
        assertFalse(end.isOver(11_000))
    }
}
