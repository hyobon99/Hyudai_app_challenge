package com.roadtalk.app.chat

import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition

/**
 * 음성 인식 결과에서 "누구에게" 와 "무슨 말을" 을 분리한다.
 *
 *  "앞차에게 감사합니다"           -> FRONT 차량, "감사합니다"
 *  "오른쪽 뒤차한테 고마워요"       -> REAR_RIGHT 차량, "고마워요"
 *  "12가 3456한테 먼저 가세요"     -> 번호판 일치 차량, "먼저 가세요"
 *  "감사합니다"                    -> 대상 없음 (현재 선택된 차량에 보냄)
 */
object VoiceCommandParser {

    data class Command(val target: NearbyCar?, val body: String)

    private val particles = listOf("에게", "한테", "께", "보고")

    // 대각선("왼쪽 뒤차")을 먼저 검사해야 "뒤차"로 잘못 잡히지 않는다
    private val positionWords = listOf(
        RelativePosition.FRONT_LEFT to sideWords("왼쪽", "좌측", front = true),
        RelativePosition.FRONT_RIGHT to sideWords("오른쪽", "우측", front = true),
        RelativePosition.REAR_LEFT to sideWords("왼쪽", "좌측", front = false),
        RelativePosition.REAR_RIGHT to sideWords("오른쪽", "우측", front = false),
        RelativePosition.FRONT to listOf("앞차", "앞 차", "앞에 차", "앞에 있는 차"),
        RelativePosition.REAR to listOf("뒷차", "뒤차", "뒤 차", "뒤에 차", "뒤에 있는 차"),
        RelativePosition.LEFT to listOf("왼쪽 차", "왼쪽차", "좌측 차", "왼쪽 옆 차", "왼쪽 옆차"),
        RelativePosition.RIGHT to listOf("오른쪽 차", "오른쪽차", "우측 차", "오른쪽 옆 차", "오른쪽 옆차"),
    )

    private fun sideWords(vararg sides: String, front: Boolean): List<String> {
        val tails = if (front) listOf("앞차", "앞 차") else listOf("뒤차", "뒷차", "뒤 차")
        return sides.flatMap { side -> tails.flatMap { listOf("$side $it", "$side$it", "$side 대각선 $it") } }
    }

    // 예: 12가3456, 123나 4567
    private val plateRegex = Regex("""(\d{2,3})\s*([가-힣])\s*(\d{4})""")

    fun parse(spoken: String, nearby: List<NearbyCar>): Command {
        val text = spoken.trim()
        for (particle in particles) {
            val idx = text.indexOf(particle)
            if (idx <= 0) continue
            val head = text.substring(0, idx).trim()
            val body = text.substring(idx + particle.length).trim()
            if (body.isEmpty()) continue
            val target = resolveTarget(head, nearby) ?: continue
            return Command(target, body)
        }
        return Command(null, text)
    }

    /** 말 속에 근처 차의 번호판이 들어 있으면 그 차 ("12가 3456한테 …") */
    fun findByPlate(spoken: String, nearby: List<NearbyCar>): NearbyCar? {
        for (m in plateRegex.findAll(spoken)) {
            val spokenPlate = normalizePlate(m.value)
            nearby.firstOrNull { normalizePlate(it.plate) == spokenPlate }?.let { return it }
        }
        return null
    }

    private fun resolveTarget(head: String, nearby: List<NearbyCar>): NearbyCar? {
        plateRegex.find(head)?.let { m ->
            val spokenPlate = normalizePlate(m.value)
            nearby.firstOrNull { normalizePlate(it.plate) == spokenPlate }?.let { return it }
        }
        for ((pos, words) in positionWords) {
            if (words.any { head.endsWith(it) || head == it }) {
                return nearby.filter { it.position == pos }.minByOrNull { it.distanceMeters }
            }
        }
        return null
    }

    fun normalizePlate(plate: String) = plate.filterNot { it.isWhitespace() }
}
