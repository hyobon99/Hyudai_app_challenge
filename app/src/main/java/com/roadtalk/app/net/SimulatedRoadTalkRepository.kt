package com.roadtalk.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 에뮬레이터 시연용 가짜 서버.
 * - 근처 차량 3~4대가 거리를 바꿔가며 나타났다 사라진다.
 * - 메시지를 보내면 상대가 가끔 답장한다.
 * - 가끔 먼저 말을 걸어온다 (수신 UI/TTS 확인용).
 */
class SimulatedRoadTalkRepository(
    private val random: Random = Random.Default,
) : RoadTalkRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _nearby = MutableStateFlow(initialCars())
    private val _incoming = MutableSharedFlow<RoadMessage>(extraBufferCapacity = 16)
    private var myPlate = ""
    private var ticker: Job? = null

    override val backendName = "시뮬레이션"
    override val status: StateFlow<String?> = MutableStateFlow(null)
    override val nearbyCars: StateFlow<List<NearbyCar>> = _nearby.asStateFlow()
    override val incoming: SharedFlow<RoadMessage> = _incoming.asSharedFlow()

    init {
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                delay(3_000)
                tick++
                _nearby.value = _nearby.value.map { car ->
                    // 앞뒤로 조금씩 움직이는 것처럼 (차선은 유지)
                    val forward = car.forwardMeters ?: 0f
                    val nextForward = (forward + random.nextInt(-2, 3)).coerceIn(-25f, 28f)
                    val lateral = car.lateralMeters ?: 0f
                    car.copy(
                        forwardMeters = nextForward,
                        distanceMeters = kotlin.math.hypot(nextForward, lateral).toInt(),
                        position = Geo.positionOf(nextForward, lateral),
                    )
                }.sortedBy { it.distanceMeters }
                // 약 30초마다 근처 차가 먼저 인사
                if (tick % 10 == 0) {
                    _nearby.value.randomOrNull(random)?.let { car ->
                        emitFrom(car, UNSOLICITED.random(random))
                    }
                }
            }
        }
    }

    override fun updateMyPresence(myPlate: String, position: MyPosition?) {
        this.myPlate = myPlate
    }

    override suspend fun send(message: RoadMessage) {
        val target = _nearby.value.firstOrNull { it.plate == message.toPlate } ?: return
        scope.launch {
            delay(1_500L + random.nextLong(0, 2_000))
            if (random.nextFloat() < 0.7f) emitFrom(target, replyTo(message.text))
        }
    }

    override fun close() {
        ticker?.cancel()
        scope.cancel()
    }

    private suspend fun emitFrom(car: NearbyCar, text: String) {
        _incoming.emit(
            RoadMessage(
                id = System.nanoTime(),
                fromPlate = car.plate,
                toPlate = myPlate,
                text = text,
                timestampMillis = System.currentTimeMillis(),
                isMine = false,
            )
        )
    }

    private fun replyTo(text: String): String = when {
        "감사" in text || "고마" in text -> listOf("천만에요!", "안전운전 하세요 :)").random(random)
        "죄송" in text || "미안" in text -> listOf("괜찮아요!", "괜찮습니다, 조심히 가세요").random(random)
        "먼저" in text -> "감사합니다! 먼저 갈게요"
        else -> listOf("네~", "좋은 하루 되세요!").random(random)
    }

    private fun initialCars() = listOf(
        NearbyCar("sim-1", "12가 3456", "IONIQ 6", 18, RelativePosition.FRONT, 18f, 0.4f, 0f),
        NearbyCar("sim-2", "34나 7890", "GRANDEUR", 12, RelativePosition.REAR_RIGHT, -11f, 3.6f, 0f),
        NearbyCar("sim-3", "56다 1122", "TUCSON", 4, RelativePosition.LEFT, 1f, -3.4f, 0f),
        NearbyCar("sim-4", "78라 3344", "KONA EV", 22, RelativePosition.FRONT_RIGHT, 21f, 3.5f, 0f),
    ).sortedBy { it.distanceMeters }

    private companion object {
        val UNSOLICITED = listOf(
            "아까 양보해주셔서 감사합니다!",
            "트렁크가 살짝 열려 있는 것 같아요",
            "좋은 하루 되세요 :)",
        )
    }
}
