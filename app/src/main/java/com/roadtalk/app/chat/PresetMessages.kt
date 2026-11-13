package com.roadtalk.app.chat

/**
 * 주변 차에게 보낼 수 있는 말 (7개). 화면의 방향 메뉴와 음성 명령이 모두 이 목록만 쓴다.
 * 순서는 방향 메뉴에 놓이는 순서와 같다: 0번이 위쪽(12시)이고 시계 방향으로 고르게 (7개면 정칠각형, 약 51.4° 씩).
 * 문구나 개수를 바꾸려면 여기만 고치면 된다 (메뉴는 개수에 맞춰 정다각형으로 배치된다).
 * 음성 명령의 단순 매칭 규칙은 [PresetMatcher] 에 있다.
 */
object PresetMessages {
    const val THANKS = 0
    const val THANKS_FOR_YIELDING = 1
    const val GO_AHEAD = 2
    const val TAKE_CARE = 3
    const val SORRY = 4
    const val ITS_OKAY = 5
    const val HELLO = 6

    val ALL = listOf(
        "감사합니다 🙏",        // 12시 (위)
        "양보 감사해요",        // 약 2시
        "먼저 가세요",          // 약 3시 반
        "조심히 가세요",        // 약 5시
        "죄송합니다 🙇",        // 약 7시
        "괜찮아요 👍",          // 약 8시 반
        "안녕하세요 👋",        // 약 10시
    )
}
