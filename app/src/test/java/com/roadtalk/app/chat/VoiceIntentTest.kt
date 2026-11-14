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

    // 기본 메시지 함에서의 번호
    private val THANKS = MessageCatalog.DEFAULT_SLOTS.indexOf("감사합니다")
    private val SORRY = MessageCatalog.DEFAULT_SLOTS.indexOf("죄송합니다")
    private val GO_AHEAD = MessageCatalog.DEFAULT_SLOTS.indexOf("먼저 가세요")

    // ---- AI(Claude)가 해석해 준 경우

    @Test
    fun aiResultPicksCarAtThatPositionAndPresetMessage() {
        // "뒷차한테 땡큐 보내줘!" → 뒷차, 감사합니다
        val result = VoiceIntentResolver.resolve("뒷차한테 땡큐 보내줘!", cars, front.plate, VoiceIntent(RelativePosition.REAR, THANKS))
        assertEquals(Result.Send(rear, THANKS, byAi = true), result)
        assertEquals("감사합니다", MessageCatalog.DEFAULT_SLOTS[(result as Result.Send).messageIndex])
    }

    @Test
    fun aiResultWithoutTargetGoesToSelectedCar() {
        val result = VoiceIntentResolver.resolve("미안하다고 해줘", cars, rear.plate, VoiceIntent(null, SORRY))
        assertEquals(Result.Send(rear, SORRY, byAi = true), result)
    }

    @Test
    fun aiSaysNoMatchingMessage() {
        val result = VoiceIntentResolver.resolve("뒷차한테 배고프다고 해줘", cars, front.plate, VoiceIntent(RelativePosition.REAR, null))
        assertEquals(Result.NoMessage, result)
    }

    @Test
    fun aiTargetWithNoCarThere() {
        val result = VoiceIntentResolver.resolve("왼쪽 차한테 고맙다고 해줘", cars, front.plate, VoiceIntent(RelativePosition.LEFT, THANKS))
        assertEquals(Result.NoCarAt(RelativePosition.LEFT), result)
    }

    @Test
    fun spokenPlateWinsOverAiTarget() {
        val result = VoiceIntentResolver.resolve("88사 8801한테 먼저 가라고 해줘", cars, front.plate, VoiceIntent(null, GO_AHEAD))
        assertEquals(Result.Send(farRear, GO_AHEAD, byAi = true), result)
    }

    // ---- AI 를 쓸 수 없는 경우 (단어 매칭)

    @Test
    fun withoutAiFallsBackToWordMatching() {
        assertEquals(
            Result.Send(rear, THANKS, byAi = false),
            VoiceIntentResolver.resolve("뒷차한테 땡큐 보내줘!", cars, front.plate, null),
        )
        assertEquals(
            Result.Send(rear, SORRY, byAi = false),
            VoiceIntentResolver.resolve("뒷차한테 미안하다고 보내줘", cars, front.plate, null),
        )
        // 대상을 말하지 않으면 선택된 차
        assertEquals(
            Result.Send(front, THANKS, byAi = false),
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
        assertEquals(Result.NoCar, VoiceIntentResolver.resolve("감사합니다", emptyList(), null, VoiceIntent(null, THANKS)))
    }

    // ---- 단어 매칭

    @Test
    fun wordMatchingCoversCommonPhrases() {
        val slots = MessageCatalog.DEFAULT_SLOTS
        fun said(text: String) = MessageCatalog.match(text, slots)?.let { slots[it] }
        assertEquals("감사합니다", said("감사합니다"))
        assertEquals("감사합니다", said("땡큐 보내줘"))
        assertEquals("먼저 가세요", said("먼저 가세요"))
        assertEquals("먼저 가세요", said("제가 양보할게요"))
        assertEquals("죄송합니다", said("미안하다고 보내줘"))
        assertEquals("좋은 하루 보내세요", said("좋은 하루 되세요"))
        assertEquals("라이트 꺼져 있어요", said("앞차 라이트가 꺼져 있다고 알려줘"))
        assertEquals("추월 가능합니다", said("추월하셔도 돼요"))
        // 기본 메시지 함에 없는 말은 보내지 않는다 (목록에는 있어도)
        assertNull(said("조심히 가세요"))
        assertNull(said("배고프다"))
    }

    @Test
    fun wordMatchingFollowsWhateverIsInTheSlots() {
        // 기본 메시지 함을 바꾸면 그 말들이 맞는다
        val slots = listOf("조심히 가세요", "조심하세요", "브레이크 등이 안 들어와요", "주유구가 열려 있어요", "네", "제가 비켜 드릴게요")
        fun said(text: String) = MessageCatalog.match(text, slots)?.let { slots[it] }
        assertEquals("조심히 가세요", said("조심히 가시라고 해줘"))
        assertEquals("조심하세요", said("조심하라고 해"))
        assertEquals("브레이크 등이 안 들어와요", said("브레이크 등 나갔다고 알려줘"))
        assertEquals("주유구가 열려 있어요", said("주유구 열렸어요"))
        assertEquals("제가 비켜 드릴게요", said("비켜 드린다고 해"))
        assertEquals("네", said("네"))
        assertEquals("네", said("알겠습니다"))
        assertNull(said("네비 켜줘"))
        assertNull(said("감사합니다"))
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
        val ai = VoiceIntent(RelativePosition.FRONT, GO_AHEAD, blocked = true, score = 0.6f)
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
    fun catalogHasTwentyThreeMessagesAndSixDefaults() {
        assertEquals(23, MessageCatalog.ALL.size)
        assertEquals(23, MessageCatalog.ALL.distinct().size)
        assertEquals(6, MessageCatalog.DEFAULT_SLOTS.size)
        org.junit.Assert.assertTrue(MessageCatalog.ALL.containsAll(MessageCatalog.DEFAULT_SLOTS))
    }

    @Test
    fun savedSlotsAreRepaired() {
        assertEquals(MessageCatalog.DEFAULT_SLOTS, MessageCatalog.sanitize(null))
        // 목록에 없는 말과 겹친 말은 빼고, 모자라면 기본값으로 채워 6개
        val repaired = MessageCatalog.sanitize(listOf("네", "없는 말", "네", "조심하세요"))
        assertEquals(6, repaired.size)
        assertEquals(6, repaired.distinct().size)
        assertEquals(listOf("네", "조심하세요"), repaired.take(2))
        org.junit.Assert.assertTrue(MessageCatalog.ALL.containsAll(repaired))
    }

    @Test
    fun droppingAMessageOnASlotReplacesOrSwaps() {
        val slots = MessageCatalog.DEFAULT_SLOTS
        // 함에 없던 말을 놓으면 그 칸만 바뀐다
        val replaced = MessageCatalog.assign(slots, 1, "조심히 가세요")
        assertEquals("조심히 가세요", replaced[1])
        assertEquals(slots.filterIndexed { i, _ -> i != 1 }, replaced.filterIndexed { i, _ -> i != 1 })
        // 이미 다른 칸에 있는 말을 놓으면 두 칸이 맞바뀐다 (같은 말이 두 칸에 생기지 않는다)
        val swapped = MessageCatalog.assign(slots, 0, slots[3])
        assertEquals(slots[3], swapped[0])
        assertEquals(slots[0], swapped[3])
        assertEquals(6, swapped.distinct().size)
        // 목록에 없는 말이나 없는 칸은 무시
        assertEquals(slots, MessageCatalog.assign(slots, 0, "없는 말"))
        assertEquals(slots, MessageCatalog.assign(slots, 9, "네"))
    }

    @Test
    fun confirmationSentenceLeavesOutPictureCharacters() {
        assertEquals("뒷차에게 감사합니다 보냈어요", SpeechText.sent("뒷차", "감사합니다 🙏"))
    }
}
