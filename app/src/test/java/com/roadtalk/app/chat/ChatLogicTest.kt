package com.roadtalk.app.chat

import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {

    private val filter = ProfanityFilter()

    @Test
    fun cleanTextPassesUntouched() {
        val r = filter.check("양보해주셔서 감사합니다")
        assertFalse(r.hasProfanity)
        assertEquals("양보해주셔서 감사합니다", r.masked)
    }

    @Test
    fun obfuscatedProfanityIsMasked() {
        val r = filter.check("아 시1발 진짜 감사하네")
        assertTrue(r.hasProfanity)
        assertFalse(r.blocked)
        assertEquals("아 *1* 진짜 감사하네", r.masked)
    }

    @Test
    fun mostlyProfanityIsBlocked() {
        assertTrue(filter.check("ㅅ ㅂ 병 신").blocked)
    }

    private val cars = listOf(
        NearbyCar("1", "12가 3456", "IONIQ 6", 18, RelativePosition.FRONT),
        NearbyCar("2", "34나 7890", "GRANDEUR", 25, RelativePosition.REAR),
    )

    @Test
    fun parsesPositionTarget() {
        val c = VoiceCommandParser.parse("앞차에게 감사합니다", cars)
        assertEquals("12가 3456", c.target?.plate)
        assertEquals("감사합니다", c.body)
    }

    @Test
    fun parsesPlateTarget() {
        val c = VoiceCommandParser.parse("34나7890한테 먼저 가세요", cars)
        assertEquals("34나 7890", c.target?.plate)
        assertEquals("먼저 가세요", c.body)
    }

    @Test
    fun plateNormalization() {
        assertEquals("12가 3456", com.roadtalk.app.vehicle.PlateStore.normalize(" 12가3456 "))
        assertEquals("123나 4567", com.roadtalk.app.vehicle.PlateStore.normalize("123 나 4567"))
        assertNull(com.roadtalk.app.vehicle.PlateStore.normalize("invalid"))
    }

    @Test
    fun noTargetKeepsWholeSentence() {
        val c = VoiceCommandParser.parse("함께 조심히 가요", cars)
        assertNull(c.target)
        assertEquals("함께 조심히 가요", c.body)
    }
}
