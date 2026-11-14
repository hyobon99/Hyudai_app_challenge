package com.roadtalk.app.voice

/**
 * 말이 끝났는지 판단한다 (버튼을 한 번 누르고 말하면, 말을 멈춘 뒤 알아서 듣기를 끝내기 위해).
 * 음성 인식 SDK 는 듣는 동안 인식된 글자를 계속 고쳐서 알려 준다. 글자가 [silenceMillis] 동안 더 바뀌지 않으면 말을 멈춘 것으로 본다.
 * 아무 말도 인식되지 않은 채 [noSpeechMillis] 가 지나도 끝낸다. 시각은 밀리초 (어느 시계든 같은 시계면 된다).
 */
class Endpointer(
    private val silenceMillis: Long = SILENCE_MILLIS,
    private val noSpeechMillis: Long = NO_SPEECH_MILLIS,
) {
    private var startedAt = 0L
    private var changedAt = 0L
    private var text = ""

    /** 듣기 시작 */
    @Synchronized
    fun begin(now: Long) {
        startedAt = now
        changedAt = now
        text = ""
    }

    /** 인식된 글자가 왔다 (중간 결과). 앞뒤 공백만 다른 것은 같은 글자로 본다 */
    @Synchronized
    fun heard(heard: String, now: Long) {
        val trimmed = heard.trim()
        if (trimmed == text) return
        text = trimmed
        changedAt = now
    }

    /** 이제 듣기를 끝내도 되는지 */
    @Synchronized
    fun isOver(now: Long): Boolean =
        if (text.isEmpty()) now - startedAt >= noSpeechMillis else now - changedAt >= silenceMillis

    companion object {
        /**
         * 말을 멈춘 뒤 기다리는 시간. 음성 인식 서비스들이 소리 기준으로 흔히 쓰는 값은 0.5~1초인데,
         * 여기서는 소리가 아니라 '인식된 글자가 더 안 바뀌는지' 로 판단하고 글자는 소리보다 조금 늦게 오므로 1.5초로 잡았다
         */
        const val SILENCE_MILLIS = 1_500L
        /** 아무 말도 안 하면 이만큼 기다렸다가 끝낸다 */
        const val NO_SPEECH_MILLIS = 6_000L
    }
}
