package com.roadtalk.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadRouteTest {

    @Test
    fun decodesTheStandardPolylineExample() {
        // 'encoded polyline' 표준 문서의 예: (38.5, -120.2) (40.7, -120.95) (43.252, -126.453)
        val points = RoadRoute.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-6)
        assertEquals(-120.2, points[0].longitude, 1e-6)
        assertEquals(40.7, points[1].latitude, 1e-6)
        assertEquals(-120.95, points[1].longitude, 1e-6)
        assertEquals(43.252, points[2].latitude, 1e-6)
        assertEquals(-126.453, points[2].longitude, 1e-6)
    }

    @Test
    fun readsTheRouteLineFromTheServerAnswer() {
        val json = """{"code":"Ok","routes":[{"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","legs":[],"distance":1,"duration":1}],"waypoints":[]}"""
        assertEquals(3, RoadRoute.parse(json)?.size)
        // JSON 안에서 \ 는 \\ 로 적혀 온다
        val escaped = """{"code":"Ok","routes":[{"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`@\\","legs":[]}]}"""
        assertTrue((RoadRoute.parse(escaped)?.size ?: 0) >= 3)
        // 길이 없다는 답
        assertNull(RoadRoute.parse("""{"code":"NoRoute","message":"Impossible route between points"}"""))
    }

    @Test
    fun findsTheNearestPlaceOnTheRoute() {
        // 북쪽으로 약 111m 씩 이어지는 길 세 점
        val route = listOf(RoadRoute.Point(37.500, 127.0), RoadRoute.Point(37.501, 127.0), RoadRoute.Point(37.502, 127.0))
        // 두 번째 구간 가운데에서 동쪽으로 약 20m 떨어진 자리
        val (index, meters) = RoadRoute.nearest(route, 37.5015, 127.000226)!!
        assertEquals(1, index)
        assertEquals(20.0, meters, 1.5)
        // 길 위에 있으면 0m
        assertEquals(0.0, RoadRoute.nearest(route, 37.5005, 127.0)!!.second, 0.5)
        assertNull(RoadRoute.nearest(emptyList(), 37.5, 127.0))
    }

    @Test
    fun measuresWhatIsLeftAndFindsTheNextTurn() {
        // 북쪽으로 약 111m 간 뒤 오른쪽(동쪽)으로 꺾어 약 88m 가는 길
        val route = listOf(RoadRoute.Point(37.500, 127.000), RoadRoute.Point(37.501, 127.000), RoadRoute.Point(37.501, 127.001))
        val start = RoadRoute.progress(route, 37.500, 127.000, totalSeconds = 60.0)!!
        assertEquals(199.6, start.remainMeters.toDouble(), 2.0)
        assertEquals(60.0, start.remainSeconds.toDouble(), 1.5)
        assertEquals(111.0, start.turn!!.meters.toDouble(), 2.0)
        assertTrue(start.turn!!.right)
        assertTrue(!start.turn!!.uTurn)
        // 절반쯤 갔을 때: 남은 거리 · 시간 · 회전까지의 거리가 줄어든다
        val half = RoadRoute.progress(route, 37.5005, 127.000, totalSeconds = 60.0)!!
        assertEquals(144.0, half.remainMeters.toDouble(), 2.0)
        assertEquals(43.0, half.remainSeconds.toDouble(), 2.0)
        assertEquals(55.5, half.turn!!.meters.toDouble(), 2.0)
        // 꺾은 뒤에는 더 꺾을 데가 없다 (이대로 가면 목적지)
        val after = RoadRoute.progress(route, 37.501, 127.0005)!!
        assertNull(after.turn)
        assertEquals(44.0, after.remainMeters.toDouble(), 2.0)
        // 왼쪽으로 꺾는 길
        val left = listOf(RoadRoute.Point(37.500, 127.000), RoadRoute.Point(37.501, 127.000), RoadRoute.Point(37.501, 126.999))
        assertTrue(!RoadRoute.progress(left, 37.500, 127.000)!!.turn!!.right)
        assertNull(RoadRoute.progress(emptyList(), 37.5, 127.0))
        assertEquals(123.4, RoadRoute.parseSeconds("""{"code":"Ok","routes":[{"legs":[{"duration":123.4,"distance":900}],"duration":123.4}]}""")!!, 0.01)
    }
}
