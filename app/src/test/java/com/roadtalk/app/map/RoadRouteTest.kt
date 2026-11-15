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
}
