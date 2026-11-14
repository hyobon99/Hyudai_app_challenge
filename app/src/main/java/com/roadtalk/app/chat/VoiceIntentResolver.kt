package com.roadtalk.app.chat

import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.VoiceIntent

/**
 * 음성 명령을 '어느 차에게 / 기본 메시지 함([MessageCatalog])의 6개 중 무엇을' 보낼지로 확정한다.
 *
 *  - 비속어·위험한 말이면 보내지 않는다 (앱의 금칙어 사전에 걸리거나, AI 가 그렇게 판단한 경우)
 *  - AI(Jev)의 해석([VoiceIntent])이 있으면 그것을 따른다 ("뒷차한테 땡큐 보내줘" → 뒷차, 감사합니다)
 *  - 없으면(서버·키 없음, 시간 초과) 단어 매칭으로 대신한다 ([VoiceCommandParser], [MessageCatalog.match])
 *  - 번호판을 말했으면 ("12가 3456한테 …") 해석과 상관없이 그 차에게 보낸다
 *  - 대상을 말하지 않았으면 지금 선택된 차에게 보낸다
 */
object VoiceIntentResolver {

    sealed interface Result {
        /** car 에게 기본 메시지 함의 messageIndex 번을 보낸다. byAi = AI 가 해석했는지 */
        data class Send(val car: NearbyCar, val messageIndex: Int, val byAi: Boolean) : Result
        /** 비속어·위험한 말이라 무시한다 (띵 소리만 낸다) */
        data object Blocked : Result
        /** 정해진 메시지 중에 맞는 말이 없다 */
        data object NoMessage : Result
        /** 말한 방향에 차가 없다 */
        data class NoCarAt(val position: RelativePosition) : Result
        /** 받을 차가 없다 (근처에 차가 없거나, 대상을 말하지 않았는데 선택된 차도 없음) */
        data object NoCar : Result
    }

    /** presets = 지금 기본 메시지 함의 6개, hasProfanity = 앱의 금칙어 사전([ProfanityFilter])에 걸렸는지 */
    fun resolve(
        spoken: String,
        cars: List<NearbyCar>,
        selectedPlate: String?,
        ai: VoiceIntent?,
        hasProfanity: Boolean = false,
        presets: List<String> = MessageCatalog.DEFAULT_SLOTS,
    ): Result {
        if (hasProfanity || ai?.blocked == true) return Result.Blocked
        val ruled = VoiceCommandParser.parse(spoken, cars)
        val messageIndex = (if (ai != null) ai.messageIndex else MessageCatalog.match(spoken, presets))?.takeIf { it in presets.indices }
        messageIndex ?: return Result.NoMessage

        val byPlate = VoiceCommandParser.findByPlate(spoken, cars)
        val selected = cars.firstOrNull { it.plate == selectedPlate }
        val car = when {
            byPlate != null -> byPlate
            ai != null -> when (val position = ai.target) {
                null -> selected
                else -> cars.filter { it.position == position }.minByOrNull { it.distanceMeters }
                    ?: return Result.NoCarAt(position)
            }
            else -> ruled.target ?: selected
        }
        car ?: return Result.NoCar
        return Result.Send(car, messageIndex, byAi = ai != null)
    }
}
