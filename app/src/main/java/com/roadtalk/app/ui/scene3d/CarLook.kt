package com.roadtalk.app.ui.scene3d

/**
 * 차가 화면에 어떤 모양·색으로 보일지.
 *  - 모양(차종)과 색(스킨 6종)은 운전자가 '내 차 고르기' 화면에서 고르고, 서버를 거쳐 다른 차 화면에도 그대로 보인다
 *  - 고르지 않은 차는 차량번호로 정한다 → 어느 화면에서 보든 같은 차는 같은 모양·색이다
 */
object CarLook {
    /** assets/scene3d/car_N.mesh 의 수 (0~5 승용차, 6 경찰차, 7 버스, 8·9 트럭) */
    const val TYPE_COUNT = 10
    const val SKIN_COUNT = 6
    private const val PASSENGER_TYPES = 6

    /** 차종 이름 (한글, 영문). 번호는 car_N.mesh 와 같다 */
    val TYPE_NAMES = listOf(
        "미니" to "MINI", "세단" to "SEDAN", "해치백" to "HATCHBACK", "밴" to "VAN", "픽업" to "PICKUP", "스포츠카" to "ROADSTER",
        "경찰차" to "POLICE", "버스" to "BUS", "트럭" to "TRUCK", "탱크로리" to "TANKER",
    )
    /** '내 차 고르기' 에서 고를 수 있는 차종: 승용차 6종 (트럭 2종 · 버스 · 경찰차는 뺐다) */
    val GARAGE_TYPES = listOf(0, 1, 2, 3, 4, 5)

    val SKIN_NAMES = listOf("파랑", "빨강", "노랑", "초록", "보라", "은색")
    /** 고르는 화면에 보여 줄 대표 색 (ARGB). 실제 차 색은 assets/scene3d/skin_N.png 팔레트에서 온다 */
    val SKIN_COLORS = listOf(0xFF649DBC, 0xFFA81600, 0xFFD8C947, 0xFF1C9F23, 0xFF32254D, 0xFF9D9D9D)

    fun typeOf(plate: String): Int = hash(plate) % PASSENGER_TYPES

    fun defaultSkinOf(plate: String): Int = (hash(plate) / PASSENGER_TYPES) % SKIN_COUNT

    /** 서버에서 온 값이 0~5 가 아니면 null (고르지 않은 것으로 본다) */
    fun validSkin(value: Int?): Int? = value?.takeIf { it in 0 until SKIN_COUNT }

    /** 서버에서 온 차종이 0~9 가 아니면 null */
    fun validType(value: Int?): Int? = value?.takeIf { it in 0 until TYPE_COUNT }

    private fun hash(plate: String): Int {
        // 띄어쓰기와 상관없이 같은 번호는 같은 값이 나오게
        var h = 17
        for (c in plate) if (!c.isWhitespace()) h = (h * 31 + c.code) and 0x7FFFFFFF
        return h
    }
}
