package com.roadtalk.app.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos FusedLocation SDK 래퍼. 근처 차량 검색에 쓸 내 위치를 제공한다.
 * 에뮬레이터 기준 위치는 1~2초 간격으로 들어온다 (SDK 에 간격 설정은 없음).
 */
class LocationGateway(context: Context) {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val heading: Float,
        /** 위치가 측정된 시각 (기기 시계) */
        val timestampMillis: Long,
        /** SDK 의 confidence. 문서에 단위가 없어 오차 반경(m)으로 추정 */
        val accuracyMeters: Float?,
        /** 직전 위치와 비교해 계산한 속도. 첫 위치이거나 10초 넘게 끊겼으면 null */
        val speedMps: Float?,
        /** heading 을 믿을 수 있는지. 앱을 켠 뒤 한 번도 움직이지 않았으면 방향을 알 길이 없어 false (그때 heading 은 0) */
        val headingKnown: Boolean = true,
    )

    private val fusedLocation = FusedLocation(context.applicationContext)
    private val _location = MutableStateFlow<Fix?>(null)
    val location: StateFlow<Fix?> = _location.asStateFlow()
    private var started = false
    /** 마지막으로 위치를 받은 시각 (기기 시계). 한동안 소식이 없으면 다시 등록하는 데 쓴다 */
    @Volatile private var lastCallbackAt = 0L

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            lastCallbackAt = System.currentTimeMillis()
            Log.d(TAG, "fix ts=${location.timestampMillis} conf=${location.confidence} heading=${location.heading}")
            val prev = _location.value
            val (heading, headingKnown) = headingFrom(prev, location)
            _location.value = Fix(
                latitude = location.latitude,
                longitude = location.longitude,
                heading = heading,
                timestampMillis = location.timestampMillis,
                accuracyMeters = location.confidence.takeIf { it > 0f },
                speedMps = speedFrom(prev, location),
                headingKnown = headingKnown,
            )
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "FusedLocation error", exception)
        }
    }

    /**
     * 진행 방향. SDK 값이 있으면 그대로 쓴다.
     * Pleos 에뮬레이터는 위치를 옮겨도 heading 이 항상 0 으로 와서, 그럴 때는 직전 위치에서 지금 위치로의 방위각(이동 방향)을 쓴다.
     * 거의 안 움직였으면(1m 미만) GPS 흔들림일 수 있어 직전 방향을 유지한다.
     * 돌려주는 값: (방향, 그 방향을 믿을 수 있는지). 아직 한 번도 움직이지 않았으면 (0, false)
     */
    private fun headingFrom(prev: Fix?, now: LocationData): Pair<Float, Boolean> {
        if (now.heading != 0f) return now.heading to true
        prev ?: return 0f to false
        val result = FloatArray(2)
        Location.distanceBetween(prev.latitude, prev.longitude, now.latitude, now.longitude, result)
        return if (result[0] < 1f) prev.heading to prev.headingKnown else (result[1] + 360f) % 360f to true
    }

    /** SDK 가 속도를 주지 않아서 직전 위치와의 거리 ÷ 시간으로 계산한다 */
    private fun speedFrom(prev: Fix?, now: LocationData): Float? {
        prev ?: return null
        val dt = now.timestampMillis - prev.timestampMillis
        return when {
            dt > 10_000 -> null          // 오래 끊겼으면 모름
            dt < 300 -> prev.speedMps    // 너무 짧으면 오차가 커서 직전 값 유지
            else -> {
                val d = FloatArray(1)
                Location.distanceBetween(prev.latitude, prev.longitude, now.latitude, now.longitude, d)
                d[0] / dt * 1000f
            }
        }
    }

    fun start() {
        try {
            if (!fusedLocation.isSupported()) {
                Log.i(TAG, "FusedLocation not supported on this device")
                return
            }
            fusedLocation.initialize()
            fusedLocation.registerFusedLocationCallback(listener)
            started = true
            lastCallbackAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation start failed", e)
        }
    }

    /**
     * 위치는 차가 서 있어도 1~2초마다 들어온다. 그런데 차량의 위치 서비스가 다시 시작되면(부팅 직후 등) 등록이 풀려서
     * 더는 안 들어오는 일이 있다 → 한동안 소식이 없으면 등록을 다시 한다.
     */
    fun restartIfSilent(maxSilenceMillis: Long) {
        if (!started || System.currentTimeMillis() - lastCallbackAt < maxSilenceMillis) return
        Log.w(TAG, "no location for ${maxSilenceMillis}ms, registering again")
        stop()
        start()
    }

    fun stop() {
        if (!started) return
        runCatching {
            fusedLocation.unregisterFusedLocationCallback(listener)
            fusedLocation.release()
        }
        started = false
    }

    private companion object {
        const val TAG = "RoadTalkLocation"
    }
}
