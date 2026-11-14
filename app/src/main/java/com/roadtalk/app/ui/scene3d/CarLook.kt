package com.roadtalk.app.ui.scene3d

/**
 * 차가 화면에 어떤 모양·색으로 보일지.
 *  - 모양(승용차 6종)은 차량번호로 정해진다 → 어느 화면에서 보든 같은 차는 같은 모양이다
 *  - 색(스킨 6종)은 운전자가 고르고 서버를 거쳐 다른 차 화면에도 그 색으로 보인다. 고르지 않은 차는 번호로 정한 색
 */
object CarLook {
    const val SKIN_COUNT = 6
    private const val PASSENGER_TYPES = 6

    val SKIN_NAMES = listOf("파랑", "빨강", "노랑", "초록", "보라", "은색")
    /** 고르는 화면에 보여 줄 대표 색 (ARGB). 실제 차 색은 assets/scene3d/skin_N.png 팔레트에서 온다 */
    val SKIN_COLORS = listOf(0xFF649DBC, 0xFFA81600, 0xFFD8C947, 0xFF1C9F23, 0xFF32254D, 0xFF9D9D9D)

    fun typeOf(plate: String): Int = hash(plate) % PASSENGER_TYPES

    fun defaultSkinOf(plate: String): Int = (hash(plate) / PASSENGER_TYPES) % SKIN_COUNT

    /** 서버에서 온 값이 0~5 가 아니면 null (고르지 않은 것으로 본다) */
    fun validSkin(value: Int?): Int? = value?.takeIf { it in 0 until SKIN_COUNT }

    private fun hash(plate: String): Int {
        // 띄어쓰기와 상관없이 같은 번호는 같은 값이 나오게
        var h = 17
        for (c in plate) if (!c.isWhitespace()) h = (h * 31 + c.code) and 0x7FFFFFFF
        return h
    }
}
