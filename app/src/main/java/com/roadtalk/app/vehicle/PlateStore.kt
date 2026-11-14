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

    /** 고른 차 색 (0~5). 아직 고르지 않았으면 null */
    fun loadSkin(): Int? = prefs.getInt(KEY_SKIN, -1).takeIf { it >= 0 }

    fun saveSkin(skin: Int) {
        prefs.edit().putInt(KEY_SKIN, skin).apply()
    }

    /** 고른 차종 (0~9). 아직 고르지 않았으면 null */
    fun loadType(): Int? = prefs.getInt(KEY_TYPE, -1).takeIf { it >= 0 }

    fun saveType(type: Int) {
        prefs.edit().putInt(KEY_TYPE, type).apply()
    }

    /** 기본 메시지 함에 넣어 둔 말들 (줄바꿈으로 이어 저장). 아직 바꾼 적이 없으면 null */
    fun loadMessages(): List<String>? = prefs.getString(KEY_MESSAGES, null)?.split('\n')

    fun saveMessages(messages: List<String>) {
        prefs.edit().putString(KEY_MESSAGES, messages.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "my_plate"
        private const val KEY_MESSAGES = "my_messages"
        private const val KEY_TYPE = "my_car_type"
        private const val KEY_SKIN = "my_skin"
        private val PLATE_REGEX = Regex("""(\d{2,3})\s*([가-힣])\s*(\d{4})""")

        /** "12가3456" -> "12가 3456". 형식이 아니면 null */
        fun normalize(input: String): String? =
            PLATE_REGEX.matchEntire(input.trim())?.destructured?.let { (a, b, c) -> "$a$b $c" }
    }
}
