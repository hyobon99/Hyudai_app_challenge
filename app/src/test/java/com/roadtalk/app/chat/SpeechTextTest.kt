package com.roadtalk.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun saysWhoSentItThenTheMessage() {
        assertEquals("앞차가 보낸 메시지. 양보 감사해요", SpeechText.incoming("앞차", "양보 감사해요"))
    }

    @Test
    fun leavesOutPictureCharacters() {
        assertEquals("뒷차가 보낸 메시지. 감사합니다", SpeechText.incoming("뒷차", "감사합니다 🙏"))
        assertEquals("왼쪽 차가 보낸 메시지. 괜찮아요", SpeechText.incoming("왼쪽 차", "괜찮아요 👍"))
    }

    @Test
    fun unknownSenderIsCalledNearbyCar() {
        assertEquals("근처 차가 보낸 메시지. 먼저 가세요", SpeechText.incoming(null, "먼저 가세요"))
    }

    @Test
    fun onlyPictureCharactersStillAnnounces() {
        assertEquals("앞차가 메시지를 보냈어요", SpeechText.incoming("앞차", "👋"))
    }
}
