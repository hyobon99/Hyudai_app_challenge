package com.roadtalk.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 욕설 사전에 '꺼져' 가 있어도 '라이트 꺼져 있다' 는 보낼 수 있어야 한다 (그 표현만) */
class ProfanityFilterTest {

    private val filter = ProfanityFilter()

    @Test
    fun lightsAreOffIsNotProfanity() {
        for (said in listOf(
            "라이트 꺼져 있어요",
            "라이트 꺼져있어요",
            "앞차 앞차한테 라이트 꺼져 있다고 해줘",
            "뒷차 라이트가 꺼져 있다고 알려줘",
        )) {
            val result = filter.check(said)
            assertFalse("욕설로 보면 안 됨: $said", result.hasProfanity)
            assertEquals(said, result.masked)
        }
    }

    @Test
    fun getLostIsStillProfanity() {
        assertTrue(filter.check("야 꺼져").hasProfanity)
        assertTrue(filter.check("뒷차한테 꺼져 라고 해").hasProfanity)
        assertTrue("라이트 이야기가 아니면 그대로 걸린다", filter.check("꺼져 있어").hasProfanity)
        assertTrue("'라이트 꺼져' 만으로는 허용하지 않는다", filter.check("라이트 꺼져").hasProfanity)
    }

    @Test
    fun otherProfanityInTheSameSentenceIsStillMasked() {
        val result = filter.check("라이트 꺼져 있다고 이 새끼야")
        assertTrue(result.hasProfanity)
        assertTrue("허용 표현 속 '꺼져' 는 가리지 않는다", result.masked.startsWith("라이트 꺼져 있다고"))
        assertFalse(result.masked.contains("새끼"))
    }
}
