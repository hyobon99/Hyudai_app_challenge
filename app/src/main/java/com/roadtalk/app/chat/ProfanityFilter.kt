package com.roadtalk.app.chat

/**
 * 게임 채팅 방식의 욕설 필터.
 *
 * 1) 정규화: 공백/특수문자/숫자 끼워넣기("시1발", "시 발", "ㅅ.ㅂ")를 제거하고 비교한다.
 * 2) 금칙어 사전 매칭: 정규화된 문자열에서 찾은 구간을 원문 위치로 되돌려 `*` 로 가린다.
 * 3) 차단 판단: 가려진 비율이 너무 높으면 아예 전송을 막는다.
 *
 * 실제 서비스에서는 사전을 서버에서 내려받아 갱신하고, 서버 쪽에서도 한 번 더 검사하는 것이 좋다.
 */
class ProfanityFilter(
    bannedWords: Collection<String> = DEFAULT_BANNED,
    private val blockRatio: Double = 0.5,
    allowedPhrases: Collection<String> = DEFAULT_ALLOWED,
) {
    private val banned = bannedWords.map(::normalizeWord).filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }

    /** 금칙어가 들어 있어도 욕설이 아닌 표현 (이 표현 안의 금칙어는 건드리지 않는다) */
    private val allowed = allowedPhrases.map(::normalizeWord).filter { it.isNotEmpty() }.distinct()

    data class Result(
        val original: String,
        val masked: String,
        val hasProfanity: Boolean,
        /** 메시지 대부분이 욕설이면 전송 자체를 막는다. */
        val blocked: Boolean,
    )

    fun check(text: String): Result {
        // 정규화된 글자 -> 원문 인덱스 매핑
        val kept = StringBuilder()
        val indexMap = ArrayList<Int>()
        text.forEachIndexed { i, c ->
            if (isMeaningful(c)) {
                kept.append(c.lowercaseChar())
                indexMap.add(i)
            }
        }
        val normalized = kept.toString()
        // 허용 표현이 차지하는 자리 (예: '라이트 꺼져 있다' 의 '꺼져' 는 욕이 아니다)
        val allowedPositions = BooleanArray(normalized.length)
        for (phrase in allowed) {
            var from = 0
            while (true) {
                val hit = normalized.indexOf(phrase, from)
                if (hit < 0) break
                for (k in hit until hit + phrase.length) allowedPositions[k] = true
                from = hit + 1
            }
        }
        val maskedPositions = BooleanArray(text.length)
        var maskedCount = 0
        for (word in banned) {
            var from = 0
            while (true) {
                val hit = normalized.indexOf(word, from)
                if (hit < 0) break
                if ((hit until hit + word.length).all { allowedPositions[it] }) {
                    from = hit + 1
                    continue
                }
                for (k in hit until hit + word.length) {
                    val orig = indexMap[k]
                    if (!maskedPositions[orig]) {
                        maskedPositions[orig] = true
                        maskedCount++
                    }
                }
                from = hit + 1
            }
        }
        if (maskedCount == 0) return Result(text, text, hasProfanity = false, blocked = false)

        val masked = buildString {
            text.forEachIndexed { i, c -> append(if (maskedPositions[i]) '*' else c) }
        }
        val ratio = maskedCount.toDouble() / normalized.length.coerceAtLeast(1)
        return Result(text, masked, hasProfanity = true, blocked = ratio >= blockRatio)
    }

    private fun normalizeWord(word: String) =
        word.filter(::isMeaningful).lowercase()

    /** 한글(완성형+자모)과 영문만 의미 있는 글자로 본다. 숫자/공백/기호는 우회용으로 간주해 무시. */
    private fun isMeaningful(c: Char): Boolean =
        c in '가'..'힣' || c in 'ㄱ'..'ㅣ' || c in 'a'..'z' || c in 'A'..'Z'

    companion object {
        // 시연용 최소 사전. 운영 시엔 서버에서 관리되는 사전으로 교체.
        val DEFAULT_BANNED = listOf(
            "시발", "씨발", "씨바", "시바", "ㅅㅂ", "ㅆㅂ", "십새", "씹",
            "병신", "ㅂㅅ", "븅신", "개새", "개색", "새끼", "ㅅㄲ",
            "좆", "존나", "졸라", "ㅈㄴ", "미친놈", "미친년", "닥쳐", "꺼져",
            "fuck", "shit", "bitch",
        )

        /**
         * 금칙어가 들어 있지만 욕설이 아닌 표현. '꺼져' 는 욕이지만 '라이트 꺼져 있어요' (전조등이 꺼졌다고 알려 주는 말)는 보낼 수 있어야 한다.
         * 이 표현만 허용한다: '꺼져' 만 따로 말하거나 다른 말에 붙이면 그대로 걸린다
         */
        val DEFAULT_ALLOWED = listOf("라이트 꺼져 있", "라이트가 꺼져 있")
    }
}
