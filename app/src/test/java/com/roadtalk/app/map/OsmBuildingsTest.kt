package com.roadtalk.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 지도 건물 윤곽 → 3D 로 세울 모양, 지도 도로·횡단보도 */
class OsmBuildingsTest {

    /** 동쪽으로 20m, 북쪽으로 10m 인 네모. 지도 자료처럼 첫 점으로 돌아와 닫힌다 (반시계 방향) */
    private val closedRectangle = floatArrayOf(5f, 5f, 25f, 5f, 25f, 15f, 5f, 15f, 5f, 5f)

    @Test
    fun outlineDropsTheClosingPointAndKeepsTheShape() {
        val ring = OsmBuildings.outline(closedRectangle)!!
        assertEquals(4, ring.size / 2)
        assertEquals(200f, OsmBuildings.signedArea(ring), 0.01f)
    }

    @Test
    fun clockwiseOutlineIsTurnedAround() {
        val clockwise = floatArrayOf(5f, 5f, 5f, 15f, 25f, 15f, 25f, 5f, 5f, 5f)
        val ring = OsmBuildings.outline(clockwise)!!
        assertTrue("반시계 방향이어야 함", OsmBuildings.signedArea(ring) > 0f)
        assertEquals(200f, OsmBuildings.signedArea(ring), 0.01f)
    }

    @Test
    fun tinyAndHugeOutlinesAreLeftOut() {
        assertNull(OsmBuildings.outline(floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f, 0f, 0f)))
        assertNull(OsmBuildings.outline(floatArrayOf(0f, 0f, 300f, 0f, 300f, 20f, 0f, 20f, 0f, 0f)))
        assertNull(OsmBuildings.outline(floatArrayOf(0f, 0f, 10f, 0f, 0f, 0f)))
    }

    @Test
    fun heightComesFromTheMapWhenItIsThere() {
        val ring = OsmBuildings.outline(closedRectangle)!!
        assertEquals(45f, OsmBuildings.heightOf(1, "45", null, ring), 0.01f)
        assertEquals(45.5f, OsmBuildings.heightOf(1, "45.5 m", "3", ring), 0.01f)
        // 층수만 있으면 층당 3.2m + 1m
        assertEquals(10 * 3.2f + 1f, OsmBuildings.heightOf(1, null, "10", ring), 0.01f)
        assertEquals(10 * 3.2f + 1f, OsmBuildings.heightOf(1, "", "10", ring), 0.01f)
    }

    @Test
    fun heightIsGuessedFromTheFootprintWhenTheMapHasNone() {
        val smallRing = floatArrayOf(0f, 0f, 8f, 0f, 8f, 8f, 0f, 8f)
        val small = OsmBuildings.heightOf(7, null, null, smallRing)
        val large = OsmBuildings.heightOf(7, null, null, floatArrayOf(0f, 0f, 50f, 0f, 50f, 40f, 0f, 40f))
        assertTrue("짐작한 높이는 사람이 볼 만한 범위: $small", small in 5f..10f)
        assertTrue("넓은 건물을 더 높게: $small < $large", large > small)
        // 엉뚱한 값은 무시하고 짐작으로
        assertEquals(small, OsmBuildings.heightOf(7, "abc", "-3", smallRing), 0.01f)
    }

    @Test
    fun metresFromTheOriginMatchTheMap() {
        // 위도 0.001도 ≈ 111m 북쪽, 서울 위도에서 경도 0.001도 ≈ 88m 동쪽
        assertEquals(111.3f, OsmBuildings.north(37.500, 37.499), 0.1f)
        assertEquals(88.3f, OsmBuildings.east(127.032, 37.499, 127.031), 0.2f)
        assertEquals(0f, OsmBuildings.east(127.031, 37.499, 127.031), 0.001f)
    }

    @Test
    fun queryAsksForBuildingsRoadsAndCrossingsAroundTheSpot() {
        val q = OsmBuildings.query(37.499, 127.031, 320)
        assertNotNull(q)
        assertTrue(q.contains("around:320,37.499,127.031"))
        assertTrue(q.contains("\"building\""))
        assertTrue(q.contains("\"highway\""))
        assertTrue(q.contains("crossing|traffic_signals"))
    }

    // ---- 건물을 3D 로 세우기

    @Test
    fun rectangleBuildingGetsFourWallsAndARoof() {
        val ring = OsmBuildings.outline(closedRectangle)!!
        val city = CityMesh.build(listOf(OsmBuildings.Footprint(1, ring, 12f)))
        val b = city.buildings.single()
        assertEquals(4 * 6, b.wallCount)        // 벽 4개 × 삼각형 2개
        assertEquals(2 * 3, b.roofCount)        // 지붕 삼각형 2개
        assertEquals(15f, b.east, 0.01f)
        assertEquals(10f, b.north, 0.01f)
        assertEquals((b.wallCount + b.roofCount) * 8, city.vertices.size)
        // 지붕은 건물 높이에, 윤곽 그대로 (동쪽 5~25m, 북쪽 5~15m → z 는 −북쪽)
        val roof = (b.first + b.wallCount until b.first + b.wallCount + b.roofCount).map { it * 8 }
        assertTrue(roof.all { city.vertices[it + 1] == 12f })
        assertEquals(5f, roof.minOf { city.vertices[it] }, 0.01f)
        assertEquals(25f, roof.maxOf { city.vertices[it] }, 0.01f)
        assertEquals(-15f, roof.minOf { city.vertices[it + 2] }, 0.01f)
        assertEquals(-5f, roof.maxOf { city.vertices[it + 2] }, 0.01f)
    }

    @Test
    fun lShapedRoofIsCutIntoTrianglesThatCoverExactlyTheOutline() {
        // ㄴ 자 건물 (오목한 꼭짓점이 하나): 넓이 = 20×10 + 10×10 = 300
        val ring = floatArrayOf(0f, 0f, 20f, 0f, 20f, 10f, 10f, 10f, 10f, 20f, 0f, 20f)
        val triangles = CityMesh.triangulate(ring)
        assertEquals((6 - 2) * 3, triangles.size)
        var area = 0f
        for (t in triangles.indices step 3) {
            val (a, b, c) = Triple(triangles[t], triangles[t + 1], triangles[t + 2])
            val one = ((ring[b * 2] - ring[a * 2]) * (ring[c * 2 + 1] - ring[a * 2 + 1]) - (ring[b * 2 + 1] - ring[a * 2 + 1]) * (ring[c * 2] - ring[a * 2])) / 2
            assertTrue("삼각형이 뒤집히면 안 됨", one > 0f)
            area += one
        }
        assertEquals(300f, area, 0.01f)
    }

    // ---- 도로 · 횡단보도 · 신호등

    @Test
    fun laneCountComesFromTheMapOrTheRoadClass() {
        assertEquals(5, OsmRoads.lanesOf("primary", "5", oneway = true))
        assertEquals(8, OsmRoads.lanesOf("primary", " 8 ", oneway = false))
        // 지도에 차로 수가 없으면 등급으로 어림한다: 큰길 양방향 6 (한쪽만이면 3), 골목 양방향 2 (일방통행이면 1)
        assertEquals(6, OsmRoads.lanesOf("primary", null, oneway = false))
        assertEquals(3, OsmRoads.lanesOf("primary", "", oneway = true))
        assertEquals(2, OsmRoads.lanesOf("residential", null, oneway = false))
        assertEquals(1, OsmRoads.lanesOf("residential", "abc", oneway = true))
        assertEquals(1, OsmRoads.lanesOf("primary_link", null, oneway = true))
        assertEquals(4 * 3.3f, OsmRoads.widthOf(4), 0.01f)
        assertTrue(OsmRoads.isDrivable("residential"))
        assertTrue(!OsmRoads.isDrivable("footway") && !OsmRoads.isDrivable("service") && !OsmRoads.isDrivable(null))
    }

    @Test
    fun crossingTakesTheDirectionAndWidthOfTheNearestRoad() {
        val eastWest = OsmRoads.Road(1, floatArrayOf(-50f, 0f, 50f, 0f), 4, true, 13.2f)        // 동서로 뻗은 큰길
        val northSouth = OsmRoads.Road(2, floatArrayOf(30f, -50f, 30f, 50f), 2, false, 6.6f)   // 남북으로 뻗은 골목
        val onBigRoad = OsmRoads.spotAt(10f, 1f, listOf(eastWest, northSouth))!!
        assertEquals(90f, onBigRoad.bearingDeg, 0.01f)
        assertEquals(13.2f, onBigRoad.roadWidth, 0.01f)
        val onAlley = OsmRoads.spotAt(31f, 20f, listOf(eastWest, northSouth))!!
        assertEquals(0f, onAlley.bearingDeg, 0.01f)
        assertEquals(6.6f, onAlley.roadWidth, 0.01f)
        // 도로에서 멀리 떨어진 것은 그리지 않는다
        assertNull(OsmRoads.spotAt(-40f, 40f, listOf(eastWest, northSouth)))
    }
}
