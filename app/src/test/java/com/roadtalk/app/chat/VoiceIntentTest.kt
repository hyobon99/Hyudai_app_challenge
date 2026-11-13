package com.roadtalk.app.chat

import com.roadtalk.app.chat.VoiceIntentResolver.Result
import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceIntentTest {

    private val front = NearbyCar("f", "55모 5501", "현대차", 10, RelativePosition.FRONT)
    private val rear = NearbyCar("r", "77바 7701", "현대차", 9, RelativePosition.REAR)
    private val farRear = NearbyCar("r2", "88사 8801", "현대차", 25, RelativePosition.REAR)
    private val cars = listOf(front, rear, farRear)

    // ---- AI(Claude)가 해석해 준 경우

    @Test
    fun aiResultPicksCarAtThatPositionAndPresetMessage() {
        // "뒷차한테 땡큐 보내줘!" → 뒷차, 감사합니다
        val result = VoiceIntentResolver.resolve("뒷차한테 땡큐 보내줘!", cars, front.plate, VoiceIntent(RelativePosition.REAR, PresetMessages.THANKS))
        assertEquals(Result.Send(rear, PresetMessages.THANKS, byAi = true), result)
        assertEquals("감사합니다 🙏", PresetMessages.ALL[(result as Result.Send).messageIndex])
    }

    @Test
    fun aiResultWithoutTargetGoesToSelectedCar() {
        val result = VoiceIntentResolver.resolve("미안하다고 해줘", cars, rear.plate, VoiceIntent(null, PresetMessages.SORRY))
        assertEquals(Result.Send(rear, PresetMessages.SORRY, byAi = true), result)
    }

    @Test
    fun aiSaysNoMatchingMessage() {
        val result = VoiceIntentResolver.resolve("뒷차한테 배고프다고 해줘", cars, front.plate, VoiceIntent(RelativePosition.REAR, null))
        assertEquals(Result.NoMessage, result)
    }

    @Test
    fun aiTargetWithNoCarThere() {
        val result = VoiceIntentResolver.resolve("왼쪽 차한테 고맙다고 해줘", cars, front.plate, VoiceIntent(RelativePosition.LEFT, PresetMessages.THANKS))
        assertEquals(Result.NoCarAt(RelativePosition.LEFT), result)
    }

    @Test
    fun spokenPlateWinsOverAiTarget() {
        val result = VoiceIntentResolver.resolve("88사 8801한테 먼저 가라고 해줘", cars, front.plate, VoiceIntent(null, PresetMessages.GO_AHEAD))
        assertEquals(Result.Send(farRear, PresetMessages.GO_AHEAD, byAi = true), result)
    }

    // ---- AI 를 쓸 수 없는 경우 (단어 매칭)

    @Test
    fun withoutAiFallsBackToWordMatching() {
        assertEquals(
            Result.Send(rear, PresetMessages.THANKS, byAi = false),
            VoiceIntentResolver.resolve("뒷차한테 땡큐 보내줘!", cars, front.plate, null),
        )
        assertEquals(
            Result.Send(rear, PresetMessages.SORRY, byAi = false),
            VoiceIntentResolver.resolve("뒷차한테 미안하다고 보내줘", cars, front.plate, null),
        )
        // 대상을 말하지 않으면 선택된 차
        assertEquals(
            Result.Send(front, PresetMessages.THANKS_FOR_YIELDING, byAi = false),
            VoiceIntentResolver.resolve("양보해 줘서 고마워요", cars, front.plate, null),
        )
    }

    @Test
    fun withoutAiUnknownWordsSendNothing() {
        assertEquals(Result.NoMessage, VoiceIntentResolver.resolve("뒷차한테 배고프다고 해줘", cars, front.plate, null))
    }

    @Test
    fun nobodyToSendTo() {
        assertEquals(Result.NoCar, VoiceIntentResolver.resolve("감사합니다", emptyList(), null, null))
        assertEquals(Result.NoCar, VoiceIntentResolver.resolve("감사합니다", emptyList(), null, VoiceIntent(null, PresetMessages.THANKS)))
    }

    // ---- 단어 매칭

    @Test
    fun wordMatchingCoversCommonPhrases() {
        assertEquals(PresetMessages.THANKS, PresetMatcher.match("감사합니다"))
        assertEquals(PresetMessages.THANKS, PresetMatcher.match("땡큐 보내줘"))
        assertEquals(PresetMessages.THANKS_FOR_YIELDING, PresetMatcher.match("양보 감사해요"))
        assertEquals(PresetMessages.GO_AHEAD, PresetMatcher.match("먼저 가세요"))
        assertEquals(PresetMessages.GO_AHEAD, PresetMatcher.match("제가 양보할게요"))
        assertEquals(PresetMessages.TAKE_CARE, PresetMatcher.match("조심히 가세요"))
        assertEquals(PresetMessages.SORRY, PresetMatcher.match("미안하다고 보내줘"))
        assertEquals(PresetMessages.ITS_OKAY, PresetMatcher.match("괜찮아요"))
        assertEquals(PresetMessages.HELLO, PresetMatcher.match("안녕하세요"))
        // 목록에서 뺀 말은 더 이상 보내지 않는다
        assertNull(PresetMatcher.match("상향등 켜져 있다고 알려줘"))
        assertNull(PresetMatcher.match("배고프다"))
    }

    // ---- 비속어·위험한 말은 무시

    @Test
    fun profanityIsIgnoredEvenWhenItAlsoContainsAGreeting() {
        // 앱의 금칙어 사전에 걸린 경우: AI 에 묻지 않아도 무시
        assertEquals(Result.Blocked, VoiceIntentResolver.resolve("야 이 새끼야 고맙다", cars, front.plate, null, hasProfanity = true))
        assertEquals(true, ProfanityFilter().check("야 이 새끼야 고맙다").hasProfanity)
        assertEquals(false, ProfanityFilter().check("뒷차한테 고맙다고 해줘").hasProfanity)
    }

    @Test
    fun aiBlockedResultIsIgnored() {
        // AI 가 위험한 말이라고 판단한 경우: 고른 문구가 있어도 보내지 않는다
        val ai = VoiceIntent(RelativePosition.FRONT, PresetMessages.GO_AHEAD, blocked = true, score = 0.6f)
        assertEquals(Result.Blocked, VoiceIntentResolver.resolve("앞차 확 밀어버려", cars, front.plate, ai))
    }

    @Test
    fun chimeIsAShortFadingTone() {
        val samples = com.roadtalk.app.voice.Chime.samples()
        assertEquals((com.roadtalk.app.voice.Chime.SAMPLE_RATE * com.roadtalk.app.voice.Chime.DURATION_SECONDS).toInt(), samples.size)
        val loudStart = samples.take(4000).maxOf { kotlin.math.abs(it.toInt()) }
        val quietEnd = samples.takeLast(4000).maxOf { kotlin.math.abs(it.toInt()) }
        org.junit.Assert.assertTrue("start=$loudStart end=$quietEnd", loudStart > 8000 && quietEnd < loudStart / 10)
    }

    @Test
    fun thereAreSevenMessagesAndNoHighBeamOne() {
        assertEquals(7, PresetMessages.ALL.size)
        assertEquals(0, PresetMessages.ALL.count { it.contains("상향등") })
        assertEquals("안녕하세요 👋", PresetMessages.ALL[PresetMessages.HELLO])
    }

    @Test
    fun confirmationSentenceLeavesOutPictureCharacters() {
        assertEquals("뒷차에게 감사합니다 보냈어요", SpeechText.sent("뒷차", "감사합니다 🙏"))
    }
}
