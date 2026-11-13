package com.roadtalk.app.vehicle

import android.content.Context

/**
 * 사용자가 직접 등록한 내 차량번호.
 * SDK 의 getCRNInfo 는 deprecated 이고 에뮬레이터에선 "invalid" 를 주기 때문에
 * 실제 서비스에서도 가입 시 번호를 등록받는 방식을 전제로 한다.
 */
class PlateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("roadtalk", Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY, null)

    fun save(plate: String) {
        prefs.edit().putString(KEY, plate).apply()
    }

    companion object {
        private const val KEY = "my_plate"
        private val PLATE_REGEX = Regex("""(\d{2,3})\s*([가-힣])\s*(\d{4})""")

        /** "12가3456" -> "12가 3456". 형식이 아니면 null */
        fun normalize(input: String): String? =
            PLATE_REGEX.matchEntire(input.trim())?.destructured?.let { (a, b, c) -> "$a$b $c" }
    }
}
