package com.roadtalk.app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos NaviHelper SDK 래퍼. 차량 순정 내비게이션 앱의 상태를 받아 온다.
 *  - 읽기 (NAVI_ROUTE 권한): 안내 상태, 목적지·남은 거리/시간, 다음 회전(TBT), 현재 도로 제한속도
 *  - 요청 (NAVI_ROUTE_SEARCH 권한): 목적지로 경로 안내 시작 / 취소
 * 순정 내비 화면 자체나 경로 선(좌표 목록)은 SDK 가 주지 않는다. 그래서 지도는 앱이 따로 그리고, 여기서 받은 정보만 얹는다.
 */
class NaviGateway(context: Context) {

    data class Destination(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val remainMeters: Int,
        val remainSeconds: Int,
    )

    data class Turn(val type: TurnType, val meters: Int, val description: String)

    data class State(
        val connected: Boolean = false,
        val status: NavigationStatus? = null,
        val destination: Destination? = null,
        val nextTurn: Turn? = null,
        /** 현재 도로 제한속도 (km/h). 모르면 null */
        val speedLimit: Int? = null,
        val lastError: String? = null,
    ) {
        val isGuiding: Boolean get() = destination != null
    }

    private val naviHelper = NaviHelper(context.applicationContext)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var started = false
    /** 순정 내비가 경로 안내 중인지 (naviMode 가 ROUTE_GUIDING). 안내 중이 아닐 때도 빈 목적지 정보(좌표 0, 거리 0)가 오기 때문에 이 값으로 걸러낸다 */
    @Volatile private var routing = false

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            Log.d(TAG, "status=$status")
            _state.update { it.copy(status = status) }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Log.d(TAG, "routeState isRouting=${routeStateInfo.isRouting} mode=${routeStateInfo.naviMode}")
            // isRouting 은 안내 중에도 false 로 온다 (경로 탐색 중일 때만 true 로 보임). 안내 여부는 naviMode 로 판단
            routing = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING
            if (!routing) clearRoute()
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "routeStarted name=${routeStartInfo.name} ${routeStartInfo.distance}m/${routeStartInfo.duration}s")
            // 좌표는 곧 이어지는 onDestinationInfo 로 채운다
            routing = true
            safely("getDestinationInfo") { naviHelper.getDestinationInfo() }
        }

        override fun onRoutingCompleted() = refresh()
        override fun onReRouteEnded() = refresh()
        override fun onDestinationChanged() = refresh()

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(TAG, "destination name=${destinationInfo.name} lat=${destinationInfo.latitude} remain=${destinationInfo.remainDistance}m/${destinationInfo.remainTime}s")
            val valid = destinationInfo.latitude != 0.0 || destinationInfo.longitude != 0.0
            if (!routing || !valid) return
            _state.update {
                it.copy(
                    destination = Destination(
                        name = destinationInfo.name.ifBlank { destinationInfo.address.ifBlank { "목적지" } },
                        latitude = destinationInfo.displayLatitude ?: destinationInfo.latitude,
                        longitude = destinationInfo.displayLongitude ?: destinationInfo.longitude,
                        remainMeters = destinationInfo.remainDistance,
                        remainSeconds = destinationInfo.remainTime,
                    ),
                    lastError = null,
                )
            }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            // 주행 중 주기적으로 오는 값으로 남은 시간을 갱신 (남은 거리는 refresh 로 다시 받는다)
            _state.update { s ->
                s.copy(destination = s.destination?.copy(remainSeconds = drivingInfo.destination.duration))
            }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            Log.d(TAG, "tbt ${tbtInfoList.take(2).map { "${it.type} ${it.distance}m ${it.description}" }}")
            val next = tbtInfoList.firstOrNull().takeIf { routing }
            _state.update {
                it.copy(nextTurn = next?.let { t -> Turn(t.type, t.distance.toInt(), t.description) })
            }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            if (speedLimit != _state.value.speedLimit) Log.d(TAG, "speedLimit=$speedLimit")
            _state.update { it.copy(speedLimit = speedLimit.takeIf { v -> v > 0 }) }
        }

        override fun onRouteCancelled() = clearRoute()

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = clearRoute()

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "naviError $errorCode")
            _state.update { it.copy(lastError = "내비 오류: ${errorCode.name}") }
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            naviHelper.addListener(listener)
            naviHelper.initialize(
                onConnected = {
                    _state.update { it.copy(connected = true, lastError = null) }
                    refresh()
                },
                onFailed = { e ->
                    Log.w(TAG, "NaviHelper connect failed", e)
                    _state.update { it.copy(connected = false, lastError = "내비 서비스에 연결하지 못했어요") }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper start failed", e)
            _state.update { it.copy(lastError = "내비 서비스를 쓸 수 없어요") }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            naviHelper.removeListener(listener)
            naviHelper.release()
        }
        _state.value = State()
    }

    /** 순정 내비에 현재 상태를 다시 물어본다. 답은 listener 로 온다 */
    fun refresh() {
        if (!_state.value.connected) return
        safely("getRouteStateInfo") { naviHelper.getRouteStateInfo() }
        safely("getDestinationInfo") { naviHelper.getDestinationInfo() }
        safely("getTBTInfo") { naviHelper.getTBTInfo() }
    }

    /** 이 좌표로 경로 안내를 시작해 달라고 순정 내비에 요청한다. 요청을 보냈으면 true */
    fun requestRoute(latitude: Double, longitude: Double, name: String): Boolean {
        if (!_state.value.connected) return false
        return safely("requestRoute") {
            naviHelper.requestRoute(
                RouteInfo(
                    latitude = latitude,
                    longitude = longitude,
                    poiName = name,
                    poiId = "",
                    address = "",
                    poiSubId = "",
                )
            )
        }
    }

    fun cancelRoute(): Boolean {
        if (!_state.value.connected) return false
        return safely("cancelRoute") { naviHelper.cancelRoute() }
    }

    private fun clearRoute() {
        routing = false
        _state.update { it.copy(destination = null, nextTurn = null) }
    }

    private fun safely(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        // 권한이 없거나(SecurityException) 서비스가 끊긴 경우
        Log.w(TAG, "$what failed", e)
        _state.update { it.copy(lastError = "내비 요청 실패: ${e.message ?: e.javaClass.simpleName}") }
        false
    }

    private companion object {
        const val TAG = "RoadTalkNavi"
    }
}
