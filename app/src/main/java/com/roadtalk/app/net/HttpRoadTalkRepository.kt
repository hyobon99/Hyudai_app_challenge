package com.roadtalk.app.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 별도 Car-ind CHAT 서버(HTTP/JSON)와 통신하는 클라이언트.
 *
 *  PUT  /v1/presence                         내 위치 (새 위치가 들어오면 바로 · 변화 없어도 5초마다)
 *  GET  /v1/nearby?plate=&lat=&lng=&radius=  근처 차량 (근처에 차가 있으면 0.3초마다, 없으면 1초마다) → 칼만 필터로 추적해 화면에는 0.1초마다 갱신
 *  POST /v1/messages                         메시지 전송 (서버가 2차 욕설 필터, 차단 시 422)
 *  GET  /v1/messages?to=&after=              받은 메시지 (1.5초마다, after=cursor 로 중복 없이)
 *  POST /v1/interpret                        음성 명령 해석 (서버가 Claude API 로: 누구에게 · 정해진 메시지 중 무엇을)
 */
class HttpRoadTalkRepository(
    baseUrl: String,
    private val radiusMeters: Int = 300,
) : RoadTalkRepository {

    private data class Me(val plate: String, val pos: MyPosition?)

    private class HttpError(val code: Int, val body: String) : IOException("HTTP $code $body")

    private val base = baseUrl.trimEnd('/')
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<String?>("서버 연결 중…")
    private val _nearby = MutableStateFlow<List<NearbyCar>>(emptyList())
    private val _incoming = MutableSharedFlow<RoadMessage>(extraBufferCapacity = 32)

    /** 새 위치가 들어오면 동기화 루프를 바로 깨운다 (여러 번 와도 한 번만 쌓임) */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * 서버 시계 − 내 기기 시계 (ms). 요청을 보낸 시각과 응답을 받은 시각의 가운데가 서버의 now 와 같은 순간이라고 보고 계산한다 (NTP 방식).
     * 이 값으로 위치 측정 시각을 서버 시계로 바꿔 보내면, 통신에 걸린 시간이 '위치의 나이' 계산에 섞이지 않는다.
     */
    @Volatile private var clockOffset: Long? = null
    private var bestRoundTrip = Long.MAX_VALUE

    /** 차마다 칼만 필터로 위치·속도를 추적 (서버에서 받는 간격과 상관없이 지금 위치를 계산) */
    private val tracker = CarTracker()
    /** 좌표 없이 접속만 알린 차 */
    @Volatile private var untracked: List<NearbyCar> = emptyList()
    /** 근처에 차가 있는 동안만 서버에 자주 묻는다 */
    @Volatile private var hasNeighbours = false

    @Volatile private var me: Me? = null
    @Volatile private var cursor = 0L
    @Volatile private var cursorPlate: String? = null

    override val backendName = "서버 " + base.substringAfter("://")
    override val status: StateFlow<String?> = _status.asStateFlow()
    override val nearbyCars: StateFlow<List<NearbyCar>> = _nearby.asStateFlow()
    override val incoming: SharedFlow<RoadMessage> = _incoming.asSharedFlow()

    init {
        scope.launch {
            var lastSync = 0L
            var lastPublished: Me? = null
            var lastPublishAt = 0L
            while (isActive) {
                // 새 위치가 오면 즉시 올리고, 그 사이에도 상대 차의 새 위치를 빨리 받도록 짧은 간격으로 다시 묻는다.
                // (상대 위치가 늦게 도착할수록 화면의 차가 실제보다 늦게 움직인다)
                val idle = if (hasNeighbours) NEARBY_FAST_MILLIS else NEARBY_SLOW_MILLIS
                withTimeoutOrNull(idle) { wake.receive() }
                val wait = MIN_SYNC_MILLIS - (System.currentTimeMillis() - lastSync)
                if (wait > 0) delay(wait)
                val current = me ?: continue
                try {
                    val now = System.currentTimeMillis()
                    // 정차 중엔 같은 위치를 매초 올릴 필요가 없어서 KEEPALIVE 마다만
                    if (current != lastPublished || now - lastPublishAt >= KEEPALIVE_MILLIS) {
                        publish(current)
                        lastPublished = current
                        lastPublishAt = now
                    }
                    queryNearby(current)
                    emitNearby()
                    _status.value = null
                } catch (e: Exception) {
                    fail("동기화", e)
                }
                lastSync = System.currentTimeMillis()
            }
        }
        // 서버에서는 1~2초마다 받지만, 화면에는 칼만 필터가 이어 그린 '지금' 위치를 0.1초마다 내보낸다
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                emitNearby()
            }
        }
        scope.launch {
            while (isActive) {
                me?.let { current ->
                    try {
                        pollInbox(current.plate)
                    } catch (e: Exception) {
                        fail("수신", e)
                    }
                }
                delay(1_500)
            }
        }
    }

    override fun updateMyPresence(myPlate: String, position: MyPosition?) {
        me = Me(myPlate, position)
        tracker.onMyFix(position?.let { CarTracker.Fix(it.lat, it.lng, it.heading, it.speedMps, it.fixedAtMillis, it.accuracyMeters) })
        wake.trySend(Unit)
    }

    /** 추적 중인 차들의 지금 위치 + 좌표를 안 보낸 차(거리 미확인)를 화면으로 내보낸다 */
    private fun emitNearby() {
        val now = System.currentTimeMillis()
        val cars = tracker.snapshot(now) + untracked
        _nearby.value = cars
        // 움직임 확인용: adb shell setprop log.tag.RoadTalkTrack VERBOSE 로 켠다
        if (Log.isLoggable(TRACK_TAG, Log.VERBOSE)) {
            cars.forEach { Log.v(TRACK_TAG, "tick $now ${it.id} ${it.forwardMeters} ${it.lateralMeters} ${it.relativeHeadingDeg}") }
        }
    }

    override suspend fun send(message: RoadMessage) = withContext(Dispatchers.IO) {
        try {
            request(
                "POST", "/v1/messages",
                JSONObject()
                    .put("fromPlate", message.fromPlate)
                    .put("toPlate", message.toPlate)
                    .put("text", message.text),
            )
            Unit
        } catch (e: HttpError) {
            _status.value = if (e.code == 422) "서버가 부적절한 표현으로 판단해 전송을 막았어요" else "전송 실패: HTTP ${e.code}"
        } catch (e: Exception) {
            fail("전송", e)
        }
    }

    override suspend fun interpret(
        spoken: String,
        presets: List<String>,
        cars: List<NearbyCar>,
        selected: RelativePosition?,
    ): VoiceIntent? = withContext(Dispatchers.IO) {
        try {
            val res = request(
                "POST", "/v1/interpret",
                JSONObject()
                    .put("text", spoken)
                    .put("messages", JSONArray(presets))
                    .put("cars", JSONArray(cars.filter { it.distanceMeters >= 0 }.map { it.position.name }.distinct()))
                    .put("selected", selected?.name ?: JSONObject.NULL),
                readTimeoutMillis = INTERPRET_TIMEOUT_MILLIS,
            )
            val target = if (res.isNull("target")) null else runCatching { RelativePosition.valueOf(res.getString("target")) }.getOrNull()
            val index = if (res.isNull("messageIndex")) null else res.getInt("messageIndex").takeIf { it in presets.indices }
            Log.d(TAG, "interpret \"$spoken\" -> target=$target message=$index (${res.optString("model")}, ${res.optLong("ms")}ms)")
            VoiceIntent(target, index)
        } catch (e: Exception) {
            // 서버에 AI 가 꺼져 있거나(503) 늦으면 조용히 포기한다 → 앱이 단어 매칭으로 대신
            Log.i(TAG, "interpret unavailable: ${e.message}")
            null
        }
    }

    override fun close() {
        scope.cancel()
    }

    /** 왕복 시간이 짧았던 응답일수록 시계 차이 추정이 정확하므로 그런 것만 반영한다 */
    private fun noteServerTime(sentAt: Long, receivedAt: Long, serverNow: Long) {
        if (serverNow <= 0) return
        val roundTrip = receivedAt - sentAt
        // 가장 짧았던 기록을 조금씩 늘려서, 통신 상태나 시계가 바뀌어도 다시 따라가게 한다
        bestRoundTrip = if (bestRoundTrip == Long.MAX_VALUE) roundTrip else minOf(roundTrip, bestRoundTrip + 3)
        if (clockOffset == null || roundTrip <= bestRoundTrip + 15) {
            clockOffset = serverNow - (sentAt + receivedAt) / 2
        }
    }

    private fun publish(current: Me) {
        val pos = current.pos
        val sentAt = System.currentTimeMillis()
        val res = request(
            "PUT", "/v1/presence",
            JSONObject()
                .put("plate", current.plate)
                .put("lat", pos?.lat ?: JSONObject.NULL)
                .put("lng", pos?.lng ?: JSONObject.NULL)
                .put("heading", pos?.heading?.toDouble() ?: JSONObject.NULL)
                .put("speed", pos?.speedMps?.toDouble() ?: JSONObject.NULL)
                // 절대 시각 대신 '측정 뒤 몇 ms 지났는지' → 기기 시계가 서로 달라도 됨
                .put("fixAge", pos?.let { System.currentTimeMillis() - it.fixedAtMillis } ?: JSONObject.NULL)
                // 위치 측정 시각을 서버 시계로 (시계 차이를 아직 모르면 생략 → 받는 쪽은 fixAge 로 계산)
                .put("fixedAt", clockOffset?.let { offset -> pos?.let { it.fixedAtMillis + offset } } ?: JSONObject.NULL)
                .put("accuracy", pos?.accuracyMeters?.toDouble() ?: JSONObject.NULL),
        )
        noteServerTime(sentAt, System.currentTimeMillis(), res.optLong("now", 0))
    }

    /** 서버에서 근처 차를 받아 추적기에 넣는다. 화면에 내보내는 것은 [emitNearby] 가 한다 */
    private fun queryNearby(current: Me) {
        val pos = current.pos
        val query = buildString {
            append("plate=").append(enc(current.plate)).append("&radius=").append(radiusMeters)
            if (pos != null) append("&lat=").append(pos.lat).append("&lng=").append(pos.lng)
        }
        val sentAt = System.currentTimeMillis()
        val res = request("GET", "/v1/nearby?$query")
        val localNow = System.currentTimeMillis()
        noteServerTime(sentAt, localNow, res.optLong("now", 0))
        // 서버 시각이 있으면 그걸로, 없으면(구버전 서버) 내 시계로 상대 위치의 나이를 계산
        val serverNow = res.optLong("now", localNow)
        // 지금 이 순간을 서버 시계로 나타낸 값
        val nowOnServerClock = clockOffset?.let { localNow + it }
        val cars = res.getJSONArray("cars")
        val tracked = HashSet<String>()
        val unknown = ArrayList<NearbyCar>()
        for (i in 0 until cars.length()) {
            val car = cars.getJSONObject(i)
            val plate = car.getString("plate")
            val key = Geo.plateKey(plate)
            if (key == Geo.plateKey(current.plate)) continue
            val lat = car.optDoubleOrNull("lat")
            val lng = car.optDoubleOrNull("lng")
            if (lat == null || lng == null) {
                // 좌표를 안 보낸 차: 거리 미확인으로만 표시
                unknown += NearbyCar(key, plate, "현대차", -1, RelativePosition.FRONT)
                continue
            }
            val fixedAt = if (car.isNull("fixedAt")) null else car.optLong("fixedAt")
            val age = if (fixedAt != null && nowOnServerClock != null) nowOnServerClock - fixedAt
            else (serverNow - car.optLong("updatedAt", serverNow)) + car.optLong("fixAge", 0)
            // 그 위치가 측정된 시각을 내 기기 시계로 바꿔서 넣는다 → 내 위치와 같은 시계로 비교된다
            tracker.onCarFix(
                plate,
                CarTracker.Fix(
                    lat, lng, car.optDoubleOrNull("heading")?.toFloat(), car.optDoubleOrNull("speed")?.toFloat(),
                    localNow - age, car.optDoubleOrNull("accuracy")?.toFloat(),
                ),
            )
            tracked += key
        }
        tracker.retainCars(tracked)
        untracked = unknown
        hasNeighbours = tracked.isNotEmpty() || unknown.isNotEmpty()
    }

    private suspend fun pollInbox(plate: String) {
        // 번호를 바꾸면 커서를 처음부터 (서버가 최근 60초 메시지만 준다)
        if (cursorPlate != plate) {
            cursorPlate = plate
            cursor = 0
        }
        val res = request("GET", "/v1/messages?to=${enc(plate)}&after=$cursor")
        val list = res.getJSONArray("messages")
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            _incoming.emit(
                RoadMessage(
                    id = SERVER_ID_BASE + m.getLong("id"),
                    fromPlate = m.getString("fromPlate"),
                    toPlate = m.getString("toPlate"),
                    text = m.getString("text"),
                    timestampMillis = m.getLong("createdAt"),
                    isMine = false,
                )
            )
        }
        cursor = maxOf(cursor, res.optLong("cursor", cursor))
    }

    private fun request(method: String, path: String, body: JSONObject? = null, readTimeoutMillis: Int = 6_000): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            conn.readTimeout = readTimeoutMillis
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, text)
            // 응답을 끝까지 읽었으므로 연결은 닫지 않고 둔다 → 다음 요청이 같은 연결을 다시 써서 빨라진다
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun fail(where: String, e: Exception) {
        Log.w(TAG, "$where failed", e)
        _status.value = "서버 $where 오류: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    private companion object {
        const val TAG = "RoadTalkHttp"
        const val TRACK_TAG = "RoadTalkTrack"
        /** 화면으로 내보내는 간격 (10Hz) */
        const val TICK_MILLIS = 100L
        /** 위치가 아무리 자주 와도 서버 동기화는 이 간격 이상 */
        const val MIN_SYNC_MILLIS = 200L
        /** 근처 차 목록을 다시 묻는 간격: 근처에 차가 있을 때 / 없을 때 */
        const val NEARBY_FAST_MILLIS = 300L
        const val NEARBY_SLOW_MILLIS = 1_000L
        /** 위치 변화가 없어도 이 간격마다 presence 갱신 (서버 TTL 30초보다 충분히 짧게) */
        const val KEEPALIVE_MILLIS = 5_000L
        /** 음성 명령 해석을 기다리는 한도 (서버가 Claude 를 6초까지 기다린다) */
        const val INTERPRET_TIMEOUT_MILLIS = 8_000
        // 내가 보낸 메시지(System.nanoTime id)와 겹치지 않게
        const val SERVER_ID_BASE = 7_000_000_000_000_000_000L
    }
}
