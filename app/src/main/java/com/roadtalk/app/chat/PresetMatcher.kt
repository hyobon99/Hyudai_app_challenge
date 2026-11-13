package com.roadtalk.app.chat

/**
 * 운전자가 한 말에서 단어를 찾아 [PresetMessages] 중 하나를 고른다.
 * AI(서버의 Jev 해석)를 쓸 수 없을 때의 대비책이라, 흔한 표현만 알아듣는다.
 */
object PresetMatcher {

    private fun String.hasAny(vararg words: String) = words.any { contains(it) }

    private val thanks = arrayOf("감사", "고마", "고맙", "땡큐", "땡스", "쌩큐", "생큐", "thank")

    /** 맞는 메시지의 번호 ([PresetMessages.ALL] 기준). 없으면 null */
    fun match(spoken: String): Int? {
        val text = spoken.lowercase()
        return when {
            // "양보 감사해요" 는 고마움, "양보할게요" 는 먼저 가라는 뜻
            text.contains("양보") && text.hasAny(*thanks) -> PresetMessages.THANKS_FOR_YIELDING
            text.hasAny("먼저 가", "먼저가", "먼저 지나", "먼저 들어", "양보할", "양보해 드", "양보해드") -> PresetMessages.GO_AHEAD
            text.hasAny("죄송", "미안", "쏘리", "sorry") -> PresetMessages.SORRY
            text.hasAny("괜찮", "괜춘", "신경 쓰지") -> PresetMessages.ITS_OKAY
            text.hasAny("조심히", "조심해", "안전운전", "안전 운전", "살펴 가", "살펴가") -> PresetMessages.TAKE_CARE
            text.hasAny(*thanks) -> PresetMessages.THANKS
            text.hasAny("안녕", "반가") -> PresetMessages.HELLO
            else -> null
        }
    }
}
