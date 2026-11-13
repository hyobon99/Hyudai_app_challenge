package com.roadtalk.app.net

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 근처 현대차 발견 + 메시지 송수신 통로.
 *
 * - [SimulatedRoadTalkRepository]: 에뮬레이터 한 대로 시연 (가짜 근처 차량)
 * - [FirestoreRoadTalkRepository]: Firebase Firestore 로 실제 기기끼리 통신
 */
interface RoadTalkRepository {
    /** UI 에 보여줄 서버 이름 (예: "Firebase", "시뮬레이션") */
    val backendName: String

    /** 서버 연결 상태/오류 메시지. 정상이면 null */
    val status: StateFlow<String?>

    val nearbyCars: StateFlow<List<NearbyCar>>
    val incoming: SharedFlow<RoadMessage>

    /** 내 위치/차량번호를 서버에 알린다. 위치가 들어올 때마다 호출되고, 위치를 모르면 position 은 null. */
    fun updateMyPresence(myPlate: String, position: MyPosition?)

    suspend fun send(message: RoadMessage)

    /**
     * 운전자가 한 말(spoken)을 서버의 AI(Jev)로 해석한다: 누구에게, presets 중 일치도가 가장 높은 것, 무시해야 할 말인지.
     * 해석을 쓸 수 없으면(이 서버가 지원하지 않음, 키 없음, 시간 초과 등) null → 앱이 단어 매칭으로 대신한다.
     */
    suspend fun interpret(
        spoken: String,
        presets: List<String>,
        cars: List<NearbyCar>,
        selected: RelativePosition?,
    ): VoiceIntent? = null

    fun close()
}
