package com.roadtalk.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지도 건물 윤곽 → 3D 화면에 세울 네모 상자 */
class OsmBuildingsTest {

    /** 동쪽으로 20m, 북쪽으로 10m 인 네모 (왼쪽 아래 구석이 (5, 5)). 윤곽은 첫 점으로 돌아와 닫힌다 */
    private val rectangle = listOf(5f to 5f, 25f to 5f, 25f to 15f, 5f to 15f, 5f to 5f)

    @Test
    fun boxSitsInTheMiddleOfTheOutline() {
        val box = OsmBuildings.box(1, rectangle, 12f)!!
        assertEquals(15f, box.east, 0.01f)
        assertEquals(10f, box.north, 0.01f)
        assertEquals(20f, box.width, 0.01f)
        assertEquals(10f, box.depth, 0.01f)
        assertEquals(12f, box.height, 0.01f)
    }

    @Test
    fun widthFollowsTheLongestSide() {
        // 가장 긴 변이 동쪽을 향하므로 가로 변의 방위는 90도(동)
        assertEquals(90f, OsmBuildings.box(1, rectangle, 12f)!!.bearingDeg, 0.01f)
        // 45도 돌아간 네모도 긴 변 방향을 따라간다
        val tilted = listOf(0f to 0f, 14.14f to 14.14f, 7.07f to 21.21f, -7.07f to 7.07f, 0f to 0f)
        val box = OsmBuildings.box(2, tilted, 9f)!!
        assertEquals(45f, box.bearingDeg, 0.5f)
        assertEquals(20f, box.width, 0.1f)
        assertEquals(10f, box.depth, 0.1f)
    }

    @Test
    fun tinyAndHugeOutlinesAreLeftOut() {
        assertNull(OsmBuildings.box(1, listOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f, 0f to 0f), 5f))
        assertNull(OsmBuildings.box(2, listOf(0f to 0f, 300f to 0f, 300f to 20f, 0f to 20f, 0f to 0f), 5f))
    }

    @Test
    fun heightComesFromTheMapWhenItIsThere() {
        assertEquals(45f, OsmBuildings.heightOf(1, "45", null, rectangle), 0.01f)
        assertEquals(45.5f, OsmBuildings.heightOf(1, "45.5 m", "3", rectangle), 0.01f)
        // 층수만 있으면 층당 3.2m + 1m
        assertEquals(10 * 3.2f + 1f, OsmBuildings.heightOf(1, null, "10", rectangle), 0.01f)
        assertEquals(10 * 3.2f + 1f, OsmBuildings.heightOf(1, "", "10", rectangle), 0.01f)
    }

    @Test
    fun heightIsGuessedFromTheFootprintWhenTheMapHasNone() {
        val small = OsmBuildings.heightOf(7, null, null, listOf(0f to 0f, 8f to 0f, 8f to 8f, 0f to 8f, 0f to 0f))
        val large = OsmBuildings.heightOf(7, null, null, listOf(0f to 0f, 50f to 0f, 50f to 40f, 0f to 40f, 0f to 0f))
        assertTrue("짐작한 높이는 사람이 볼 만한 범위: $small", small in 5f..10f)
        assertTrue("넓은 건물을 더 높게: $small < $large", large > small)
        // 엉뚱한 값은 무시하고 짐작으로
        assertEquals(small, OsmBuildings.heightOf(7, "abc", "-3", listOf(0f to 0f, 8f to 0f, 8f to 8f, 0f to 8f, 0f to 0f)), 0.01f)
    }

    @Test
    fun metresFromTheOriginMatchTheMap() {
        // 위도 0.001도 ≈ 111m 북쪽, 서울 위도에서 경도 0.001도 ≈ 88m 동쪽
        assertEquals(111.3f, OsmBuildings.north(37.500, 37.499), 0.1f)
        assertEquals(88.3f, OsmBuildings.east(127.032, 37.499, 127.031), 0.2f)
        assertEquals(0f, OsmBuildings.east(127.031, 37.499, 127.031), 0.001f)
    }

    @Test
    fun queryAsksForBuildingsAroundTheSpot() {
        val q = OsmBuildings.query(37.499, 127.031, 320)
        assertNotNull(q)
        assertTrue(q.contains("around:320,37.499,127.031"))
        assertTrue(q.contains("\"building\""))
    }
}
