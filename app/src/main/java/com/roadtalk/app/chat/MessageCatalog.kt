package com.roadtalk.app.chat

/**
 * 주변 차에게 보낼 수 있는 말.
 *  - [ALL]: 고를 수 있는 전체 목록 (23개)
 *  - 기본 메시지 함: 그중 운전자가 자주 쓰는 [SLOT_COUNT] 개. 음성으로 말하면 이 6개 중 뜻이 가장 가까운 것이 전송된다.
 *    처음에는 [DEFAULT_SLOTS] 이고, '메시지 함' 화면에서 목록의 말을 끌어다 놓아 바꾼다
 * 음성 해석(서버의 Jev)을 쓸 수 없을 때는 [match] 의 단어 매칭으로 대신한다.
 */
object MessageCatalog {
    const val SLOT_COUNT = 6

    val ALL = listOf(
        "감사합니다",
        "조심히 가세요",
        "좋은 하루 보내세요",
        "죄송합니다",
        "제가 뒤로 갈게요",
        "제가 비켜 드릴게요",
        "끼어들어도 될까요?",
        "주유구가 열려 있어요",
        "라이트 꺼져 있어요",
        "브레이크 등이 안 들어와요",
        "짐이 떨어질 것 같아요",
        "차에서 연기가 나요",
        "조심하세요",
        "추월 가능합니다",
        "전방에 위험이 있습니다",
        "차량에 이상이 있습니다",
        "전방 정체 중입니다",
        "정차하겠습니다",
        "보행자가 있습니다",
        "승객이 탑승 중입니다",
        "승객이 하차 중입니다",
        "먼저 가세요",
        "네",
    )

    val DEFAULT_SLOTS = listOf("감사합니다", "좋은 하루 보내세요", "죄송합니다", "먼저 가세요", "라이트 꺼져 있어요", "추월 가능합니다")

    /** 말에 이 단어 중 하나가 들어 있으면 그 메시지로 본다 (위에서부터 먼저 맞는 것) */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "브레이크 등이 안 들어와요" to listOf("브레이크"),
        "라이트 꺼져 있어요" to listOf("라이트", "전조등", "헤드라이트"),
        "주유구가 열려 있어요" to listOf("주유구"),
        "짐이 떨어질 것 같아요" to listOf("짐이", "짐 떨어", "적재물"),
        "차에서 연기가 나요" to listOf("연기"),
        "차량에 이상이 있습니다" to listOf("이상이", "이상 있", "고장"),
        "전방에 위험이 있습니다" to listOf("위험"),
        "전방 정체 중입니다" to listOf("정체", "막혀", "막히"),
        "보행자가 있습니다" to listOf("보행자", "사람이 있", "사람 있"),
        "승객이 탑승 중입니다" to listOf("탑승", "타고 있", "타는 중"),
        "승객이 하차 중입니다" to listOf("하차", "내리는 중", "내리고 있"),
        "정차하겠습니다" to listOf("정차", "멈출게", "세울게"),
        "추월 가능합니다" to listOf("추월"),
        "끼어들어도 될까요?" to listOf("끼어들", "끼워"),
        "제가 뒤로 갈게요" to listOf("뒤로 갈", "뒤로 뺄", "후진"),
        "제가 비켜 드릴게요" to listOf("비켜", "비킬"),
        "먼저 가세요" to listOf("먼저 가", "먼저가", "먼저 지나", "먼저 들어", "양보할", "양보해 드", "양보해드"),
        "죄송합니다" to listOf("죄송", "미안", "쏘리", "sorry"),
        "좋은 하루 보내세요" to listOf("좋은 하루", "좋은하루"),
        "조심히 가세요" to listOf("조심히", "조심해서", "안전운전", "안전 운전", "살펴 가", "살펴가"),
        "조심하세요" to listOf("조심하", "조심해"),
        "감사합니다" to listOf("감사", "고마", "고맙", "땡큐", "땡스", "쌩큐", "생큐", "thank"),
        "네" to listOf("알겠", "알았"),
    )

    /**
     * 운전자가 한 말에서 단어를 찾아 candidates(기본 메시지 함) 중 하나를 고른다. 돌려주는 값은 candidates 의 번호, 없으면 null.
     * AI 해석을 쓸 수 없을 때의 대비책이라 흔한 표현만 알아듣는다.
     */
    fun match(spoken: String, candidates: List<String>): Int? {
        val text = spoken.lowercase().trim()
        for ((message, words) in KEYWORDS) {
            val index = candidates.indexOf(message)
            if (index >= 0 && words.any { text.contains(it) }) return index
        }
        // "네" 는 다른 말 속에도 흔히 들어 있어서, 그 말만 했을 때만 맞는 것으로 본다
        if (text.trimEnd('.', '!', '요', ' ') in setOf("네", "넵", "예")) return candidates.indexOf("네").takeIf { it >= 0 }
        return null
    }

    /** 저장돼 있던 기본 메시지 함을 쓸 수 있는 모양으로 고친다: 목록에 있는 말만, 겹치지 않게, 꼭 6개 (모자라면 기본값으로 채움) */
    fun sanitize(saved: List<String>?): List<String> {
        val slots = saved.orEmpty().filter { it in ALL }.distinct().take(SLOT_COUNT).toMutableList()
        for (fallback in DEFAULT_SLOTS + ALL) {
            if (slots.size >= SLOT_COUNT) break
            if (fallback !in slots) slots += fallback
        }
        return slots
    }

    /**
     * slot 번 칸에 message 를 놓는다. 그 말이 이미 다른 칸에 있으면 두 칸을 맞바꾼다 (같은 말이 두 칸에 들어가지 않게).
     * 목록에 없는 말이거나 칸 번호가 틀리면 그대로 돌려준다
     */
    fun assign(slots: List<String>, slot: Int, message: String): List<String> {
        if (slot !in slots.indices || message !in ALL) return slots
        val out = slots.toMutableList()
        val existing = out.indexOf(message)
        if (existing >= 0) out[existing] = out[slot]
        out[slot] = message
        return out
    }
}
