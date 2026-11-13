package com.roadtalk.app.net

import android.util.Log
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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

/**
 * Firestore 구조
 *
 *  presence/{plateKey}                    내 차가 위치가 바뀔 때마다 갱신 (최소 2초 간격, 변화 없으면 5초마다)
 *  inboxes/{plateKey}/messages/{auto-id}  나에게 온 메시지
 *
 * 근처 차량: geohash 범위 질의로 반경 [radiusMeters] 안 + 최근 [presenceTtlMillis] 안에 갱신된 차만.
 * 메시지: [messageTtlMillis] 보다 오래된 건 버린다 (지나간 상황에 대한 감사 인사는 의미가 없으니까).
 */
class FirestoreRoadTalkRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val radiusMeters: Double = 300.0,
    private val presenceTtlMillis: Long = 30_000,
    private val messageTtlMillis: Long = 60_000,
) : RoadTalkRepository {

    private data class Me(val plate: String, val pos: MyPosition?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<String?>("Firebase 연결 중…")
    private val _nearby = MutableStateFlow<List<NearbyCar>>(emptyList())
    private val _incoming = MutableSharedFlow<RoadMessage>(extraBufferCapacity = 32)

    /** 새 위치가 들어오면 동기화 루프를 바로 깨운다 (여러 번 와도 한 번만 쌓임) */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** 차마다 칼만 필터로 위치·속도를 추적 (받는 간격과 상관없이 지금 위치를 계산) */
    private val tracker = CarTracker()
    /** 좌표 없이 접속만 알린 차 */
    @Volatile private var untracked: List<NearbyCar> = emptyList()

    @Volatile private var me: Me? = null
    private var inbox: ListenerRegistration? = null
    private var inboxPlate: String? = null

    override val backendName = "Firebase"
    override val status: StateFlow<String?> = _status.asStateFlow()
    override val nearbyCars: StateFlow<List<NearbyCar>> = _nearby.asStateFlow()
    override val incoming: SharedFlow<RoadMessage> = _incoming.asSharedFlow()

    init {
        scope.launch {
            var lastSync = 0L
            var lastPublished: Me? = null
            var lastPublishAt = 0L
            while (isActive) {
                // 새 위치가 오면 즉시, 안 오면 KEEPALIVE 마다. Firestore 는 쓰기 비용이 있어 최소 간격을 HTTP 보다 길게
                withTimeoutOrNull(KEEPALIVE_MILLIS) { wake.receive() }
                val wait = MIN_SYNC_MILLIS - (System.currentTimeMillis() - lastSync)
                if (wait > 0) delay(wait)
                val current = me ?: continue
                try {
                    val now = System.currentTimeMillis()
                    if (current != lastPublished || now - lastPublishAt >= KEEPALIVE_MILLIS) {
                        publish(current)
                        lastPublished = current
                        lastPublishAt = now
                    }
                    queryNearby(current)
                    emitNearby()
                    _status.value = null
                } catch (e: Exception) {
                    Log.w(TAG, "sync failed", e)
                    _status.value = "Firebase 오류: ${e.message ?: e.javaClass.simpleName}"
                }
                lastSync = System.currentTimeMillis()
            }
        }
        // Firestore 에서는 2초마다 받지만, 화면에는 칼만 필터가 이어 그린 '지금' 위치를 0.1초마다 내보낸다
        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                emitNearby()
            }
        }
    }

    override fun updateMyPresence(myPlate: String, position: MyPosition?) {
        me = Me(myPlate, position)
        tracker.onMyFix(position?.let { CarTracker.Fix(it.lat, it.lng, it.heading, it.speedMps, it.fixedAtMillis, it.accuracyMeters) })
        if (inboxPlate != myPlate) listenInbox(myPlate)
        wake.trySend(Unit)
    }

    override suspend fun send(message: RoadMessage) = withContext(Dispatchers.IO) {
        try {
            val doc = mapOf(
                "fromPlate" to message.fromPlate,
                "toPlate" to message.toPlate,
                "text" to message.text,
                "createdAt" to message.timestampMillis,
                "filtered" to message.wasFiltered,
            )
            await(db.collection("inboxes").document(key(message.toPlate)).collection("messages").add(doc))
            Unit
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            _status.value = "전송 실패: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun close() {
        inbox?.remove()
        scope.cancel()
    }

    private fun listenInbox(plate: String) {
        inbox?.remove()
        inboxPlate = plate
        val since = System.currentTimeMillis() - messageTtlMillis
        inbox = db.collection("inboxes").document(key(plate)).collection("messages")
            .whereGreaterThan("createdAt", since)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _status.value = "수신 오류: ${error.message}"
                    return@addSnapshotListener
                }
                snapshot?.documentChanges
                    ?.filter { it.type == DocumentChange.Type.ADDED }
                    ?.mapNotNull { toMessage(it.document, plate) }
                    ?.forEach { _incoming.tryEmit(it) }
            }
    }

    private fun toMessage(doc: DocumentSnapshot, myPlate: String): RoadMessage? {
        val createdAt = doc.getLong("createdAt") ?: return null
        if (System.currentTimeMillis() - createdAt > messageTtlMillis) return null
        return RoadMessage(
            id = doc.id.hashCode().toLong() xor createdAt,
            fromPlate = doc.getString("fromPlate") ?: return null,
            toPlate = myPlate,
            text = doc.getString("text") ?: return null,
            timestampMillis = createdAt,
            isMine = false,
        )
    }

    private fun publish(current: Me) {
        val now = System.currentTimeMillis()
        val data = buildMap<String, Any> {
            put("plate", current.plate)
            put("updatedAt", now)
            current.pos?.let { pos ->
                put("lat", pos.lat)
                put("lng", pos.lng)
                put("geohash", GeoFireUtils.getGeoHashForLocation(GeoLocation(pos.lat, pos.lng)))
                put("heading", pos.heading.toDouble())
                pos.speedMps?.let { put("speed", it.toDouble()) }
                // 절대 시각 대신 '측정 뒤 몇 ms 지났는지' → 기기 시계가 서로 달라도 됨
                put("fixAge", now - pos.fixedAtMillis)
                pos.accuracyMeters?.let { put("accuracy", it.toDouble()) }
            }
        }
        await(db.collection("presence").document(key(current.plate)).set(data))
    }

    /** 근처 차를 받아 추적기에 넣는다. 화면에 내보내는 것은 [emitNearby] 가 한다 */
    private fun queryNearby(current: Me) {
        val now = System.currentTimeMillis()
        val pos = current.pos
        val docs: List<DocumentSnapshot> = if (pos != null) {
            GeoFireUtils.getGeoHashQueryBounds(GeoLocation(pos.lat, pos.lng), radiusMeters)
                .flatMap { b ->
                    await(
                        db.collection("presence").orderBy("geohash").startAt(b.startHash).endAt(b.endHash).get()
                    ).documents
                }
        } else {
            // 위치를 아직 못 받았으면 (에뮬레이터 등) 최근 접속한 차 전체를 보여준다
            await(
                db.collection("presence").whereGreaterThan("updatedAt", now - presenceTtlMillis).get()
            ).documents
        }

        val tracked = HashSet<String>()
        val unknown = ArrayList<NearbyCar>()
        for (doc in docs.distinctBy { it.id }) {
            val plate = doc.getString("plate") ?: continue
            if (key(plate) == key(current.plate)) continue
            val updatedAt = doc.getLong("updatedAt") ?: continue
            if (now - updatedAt > presenceTtlMillis) continue

            val lat = doc.getDouble("lat")
            val lng = doc.getDouble("lng")
            if (lat == null || lng == null) {
                unknown += NearbyCar(key(plate), plate, "현대차", -1, RelativePosition.FRONT)
                continue
            }
            // geohash 질의는 네모 범위라 반경 밖의 차가 섞여 온다
            if (pos != null && Geo.distanceAndBearing(pos.lat, pos.lng, lat, lng).first > radiusMeters) continue
            // updatedAt 은 상대 기기 시계지만, 차량 시계는 GPS 로 맞춰져 있어 수 초 안쪽 차이로 본다
            val fixedAt = updatedAt - (doc.getLong("fixAge") ?: 0L)
            tracker.onCarFix(
                plate,
                CarTracker.Fix(
                    lat, lng, doc.getDouble("heading")?.toFloat(), doc.getDouble("speed")?.toFloat(),
                    fixedAt, doc.getDouble("accuracy")?.toFloat(),
                ),
            )
            tracked += key(plate)
        }
        tracker.retainCars(tracked)
        untracked = unknown
    }

    /** 추적 중인 차들의 지금 위치 + 좌표를 안 보낸 차(거리 미확인)를 화면으로 내보낸다 */
    private fun emitNearby() {
        _nearby.value = tracker.snapshot(System.currentTimeMillis()) + untracked
    }

    private fun key(plate: String) = Geo.plateKey(plate)

    /** 오프라인이면 Firestore 가 무한 재시도하므로 일정 시간 뒤 실패로 처리해 UI 에 알린다. */
    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, 8, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        throw IllegalStateException("서버 응답 없음 (인터넷/프로젝트 설정 확인)", e)
    } catch (e: ExecutionException) {
        throw (e.cause as? Exception) ?: e
    }

    private companion object {
        const val TAG = "RoadTalkFirestore"
        /** 화면으로 내보내는 간격 (10Hz) */
        const val TICK_MILLIS = 100L
        /** 위치가 아무리 자주 와도 Firestore 쓰기는 이 간격 이상 (무료 할당량 보호) */
        const val MIN_SYNC_MILLIS = 2_000L
        /** 위치 변화가 없어도 이 간격마다 presence 갱신 (presenceTtl 30초보다 충분히 짧게) */
        const val KEEPALIVE_MILLIS = 5_000L
    }
}
