package com.roadtalk.app.chat

/** 받은 메시지를 음성(TTS)으로 읽어 줄 문장을 만든다. */
object SpeechText {

    /** 그림 문자·기호는 소리로 읽으면 어색해서 뺀다 (예: 🙏 👍) */
    private val SYMBOLS = Regex("[\\p{So}\\p{Sk}\\p{Cs}\\x{FE0F}\\x{200D}]")

    /** 음성으로 보낸 뒤, 누구에게 무엇을 보냈는지 확인해 주는 문장. 예: "뒷차에게 감사합니다 보냈어요" */
    fun sent(toLabel: String?, text: String): String {
        val who = toLabel?.takeIf { it.isNotBlank() } ?: "근처 차"
        val body = SYMBOLS.replace(text, "").replace(Regex("\\s+"), " ").trim()
        return "${who}에게 $body 보냈어요"
    }

    /**
     * 누가 보냈는지(앞차·왼쪽 차 …)를 먼저 말하고 내용을 읽는다.
     * 예: "앞차가 보낸 메시지. 양보 감사해요"
     */
    fun incoming(fromLabel: String?, text: String): String {
        val who = fromLabel?.takeIf { it.isNotBlank() } ?: "근처 차"
        val body = SYMBOLS.replace(text, "").replace(Regex("\\s+"), " ").trim()
        return if (body.isEmpty()) "${who}가 메시지를 보냈어요" else "${who}가 보낸 메시지. $body"
    }
}
