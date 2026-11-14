package com.roadtalk.app.ui

import com.roadtalk.app.ui.scene3d.Camera3D
import com.roadtalk.app.ui.scene3d.CarLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3D 화면의 카메라 계산과 차 모양·색 정하기 (화면에 그리는 부분은 에뮬레이터에서 확인한다) */
class Scene3DTest {

    private val camera = Camera3D(700f, 900f, rearM = 7f)

    @Test
    fun myCarIsOnTheCentreLineBelowTheMiddle() {
        val me = camera.project(0f, 0f, 0f)!!
        assertEquals(350f, me.first, 0.5f)
        assertTrue("내 차는 화면 가운데보다 아래", me.second > 450f && me.second < 900f)
    }

    @Test
    fun carAheadIsHigherAndCarOnTheRightIsFurtherRight() {
        val me = camera.project(0f, 0f, 0f)!!
        val ahead = camera.project(0f, 0f, -10f)!!
        val right = camera.project(3.5f, 0f, 0f)!!
        assertTrue(ahead.second < me.second)
        assertTrue(right.first > me.first)
    }

    @Test
    fun carSevenMetresBehindIsStillOnScreen() {
        val behind = camera.project(0f, 0f, 7f)!!
        assertTrue("뒤 7m 차가 화면 안에 있어야 함: $behind", behind.second < 900f)
    }

    @Test
    fun cameraBacksOffWhenCarsAreFurtherBehind() {
        val wide = Camera3D(700f, 900f, rearM = 22f)
        val behind = wide.project(0f, 0f, 22f)!!
        assertTrue("뒤 22m 차가 화면 안에 있어야 함: $behind", behind.second < 900f)
        assertTrue(wide.eye[1] > camera.eye[1])
    }

    @Test
    fun tappingWhereACarIsDrawnGivesBackItsPosition() {
        // 앞 12m · 오른쪽 3.5m 인 차가 그려진 자리를 누르면 같은 위치가 나온다
        val at = camera.project(3.5f, 0f, -12f)!!
        val (forward, lateral) = camera.ground(at.first, at.second)!!
        assertEquals(12f, forward, 0.05f)
        assertEquals(3.5f, lateral, 0.05f)
    }

    @Test
    fun pointBehindTheCameraIsNotDrawn() {
        assertNull(camera.project(0f, 0f, 100f))
        assertNotNull(camera.project(0f, 0f, -100f))
    }

    @Test
    fun sameCarLooksTheSameEverywhere() {
        // 띄어쓰기가 달라도 같은 번호면 같은 모양·색
        assertEquals(CarLook.typeOf("12가 3690"), CarLook.typeOf("12가3690"))
        assertEquals(CarLook.defaultSkinOf("12가 3690"), CarLook.defaultSkinOf("12가3690"))
        for (plate in listOf("12가 3690", "99허 0000", "55모 5501", "77바 7701")) {
            assertTrue(CarLook.typeOf(plate) in 0..5)
            assertTrue(CarLook.defaultSkinOf(plate) in 0..5)
        }
    }

    @Test
    fun skinFromServerMustBeOneOfTheSix() {
        assertEquals(3, CarLook.validSkin(3))
        assertNull(CarLook.validSkin(6))
        assertNull(CarLook.validSkin(-1))
        assertNull(CarLook.validSkin(null))
        assertEquals(CarLook.SKIN_COUNT, CarLook.SKIN_NAMES.size)
        assertEquals(CarLook.SKIN_COUNT, CarLook.SKIN_COLORS.size)
    }

    @Test
    fun onlyPassengerCarsCanBePicked() {
        // 고를 수 있는 차종은 승용차 6종 (경찰차 6 · 버스 7 · 트럭 8, 9 는 없다)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), CarLook.GARAGE_TYPES)
        assertEquals(CarLook.TYPE_COUNT, CarLook.TYPE_NAMES.size)
    }

    @Test
    fun treeLeavesFollowTheSeason() {
        val spring = com.roadtalk.app.ui.scene3d.Seasons.leafTint(4)!!
        val autumn = com.roadtalk.app.ui.scene3d.Seasons.leafTint(11)!!
        val winter = com.roadtalk.app.ui.scene3d.Seasons.leafTint(1)!!
        assertNull("여름은 모델의 원래 초록", com.roadtalk.app.ui.scene3d.Seasons.leafTint(7))
        assertTrue("봄은 분홍: 빨강이 초록보다 크고 파랑도 많다", spring[0] > spring[1] && spring[2] > spring[1])
        assertTrue("가을은 주황: 빨강 > 초록 > 파랑", autumn[0] > autumn[1] && autumn[1] > autumn[2])
        assertTrue("겨울은 흰색", winter.take(3).all { it > 0.9f })
        // 달마다 어느 계절인지
        assertTrue((3..5).all { com.roadtalk.app.ui.scene3d.Seasons.leafTint(it) === spring })
        assertTrue((9..11).all { com.roadtalk.app.ui.scene3d.Seasons.leafTint(it) === autumn })
        assertTrue(listOf(12, 1, 2).all { com.roadtalk.app.ui.scene3d.Seasons.leafTint(it) === winter })
    }
}
