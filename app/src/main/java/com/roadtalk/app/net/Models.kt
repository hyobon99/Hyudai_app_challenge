package com.roadtalk.app.net

/**
 * 내 차 기준 상대 위치 (8방향). 받는 사람을 고르는 기준이 된다.
 * label: 화면/음성 안내용, cell: 3x3 칸 이름, row/col: 3x3 칸 좌표 (가운데 1,1 = 내 차)
 */
enum class RelativePosition(val label: String, val cell: String, val row: Int, val col: Int) {
    FRONT_LEFT("왼쪽 앞 차", "왼쪽 앞", 0, 0),
    FRONT("앞차", "앞", 0, 1),
    FRONT_RIGHT("오른쪽 앞 차", "오른쪽 앞", 0, 2),
    LEFT("왼쪽 차", "왼쪽", 1, 0),
    RIGHT("오른쪽 차", "오른쪽", 1, 2),
    REAR_LEFT("왼쪽 뒤 차", "왼쪽 뒤", 2, 0),
    REAR("뒷차", "뒤", 2, 1),
    REAR_RIGHT("오른쪽 뒤 차", "오른쪽 뒤", 2, 2),
}

/**
 * 서버에 올리는 내 위치. FusedLocation 이 준 값 + 직전 위치와 비교해 계산한 속도.
 * fixedAtMillis 는 위치가 측정된 시각(내 기기 시계). 서버에는 절대 시각 대신 '몇 ms 지났는지'를 보낸다.
 */
data class MyPosition(
    val lat: Double,
    val lng: Double,
    val heading: Float,
    val speedMps: Float?,
    val fixedAtMillis: Long,
    val accuracyMeters: Float?,
)

data class NearbyCar(
    val id: String,
    val plate: String,
    val model: String,
    val distanceMeters: Int,
    val position: RelativePosition,
    /** 상대 위치 벡터 (m). +앞/−뒤, +오른쪽/−왼쪽. 거리를 모르면 null → 화면은 position 칸의 대표 위치에 그린다 */
    val forwardMeters: Float? = null,
    val lateralMeters: Float? = null,
    /** 상대 차가 내 진행 방향 기준 몇 도를 보는지 (0 = 같은 방향, ±180 = 맞은편). 모르면 null */
    val relativeHeadingDeg: Float? = null,
)

/**
 * 음성 명령을 서버(Claude)가 해석한 결과.
 * target = 운전자가 말한 방향 (대상을 말하지 않았으면 null), messageIndex = 정해진 메시지의 번호 (맞는 말이 없으면 null)
 */
data class VoiceIntent(val target: RelativePosition?, val messageIndex: Int?)

data class RoadMessage(
    val id: Long,
    val fromPlate: String,
    val toPlate: String,
    val text: String,
    val timestampMillis: Long,
    val isMine: Boolean,
    /** 욕설 필터로 일부가 가려졌는지 여부 */
    val wasFiltered: Boolean = false,
    /** 상대 차가 내 기준 어느 방향이었는지 (보낼 때/받을 때 기록, 화면에 번호 대신 표시) */
    val peerPosition: RelativePosition? = null,
)
