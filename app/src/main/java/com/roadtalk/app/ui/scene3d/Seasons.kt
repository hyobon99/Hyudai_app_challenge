package com.roadtalk.app.ui.scene3d

/**
 * 계절에 따른 가로수 잎 색. 달은 1~12.
 *   봄(3~5월) 분홍 · 여름(6~8월) 초록 · 가을(9~11월) 주황 · 겨울(12~2월) 흰색
 */
object Seasons {
    private val SPRING = floatArrayOf(0.98f, 0.66f, 0.80f, 1f)
    private val AUTUMN = floatArrayOf(0.95f, 0.52f, 0.14f, 1f)
    private val WINTER = floatArrayOf(0.96f, 0.97f, 0.99f, 1f)

    /** 잎에 입힐 색 (빨강·초록·파랑·불투명도). 여름은 null: 모델의 원래 초록을 그대로 쓴다 */
    fun leafTint(month: Int): FloatArray? = when (month) {
        3, 4, 5 -> SPRING
        6, 7, 8 -> null
        9, 10, 11 -> AUTUMN
        else -> WINTER
    }
}
