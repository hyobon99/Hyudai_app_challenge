package com.roadtalk.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos Vehicle SDK 래퍼.
 *  - CarInfo.getCRNInfo : 차량번호 (PRIVATE_CAR_INFO 권한 필요 → 승인 전엔 실패할 수 있음)
 *  - Gear               : P 단 여부로 "주행 중 입력 제한" 판단
 *  - TurnSignal         : 방향지시등 상태 (깜빡이 켜고 끼어들 때 '양보 감사' 추천 등에 활용)
 * SDK 호출이 실패해도 앱이 죽지 않도록 모두 상태값으로만 노출한다.
 */
class VehicleGateway(context: Context) {

    data class State(
        val connected: Boolean = false,
        val plate: String? = null,
        val plateError: String? = null,
        val gear: VehicleGear? = null,
        val turnSignal: TurnSignalValue? = null,
        val lastError: String? = null,
    ) {
        /** 기어 정보를 못 받으면 안전하게 '주행 중'으로 간주 */
        val isParked: Boolean get() = gear == VehicleGear.GEAR_PARK
    }

    private val vehicle = Vehicle(context.applicationContext)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = update { copy(gear = gear) }
        override fun onFailed(e: Exception) = fail("gear", e)
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = update { copy(turnSignal = state) }
        override fun onFailed(e: Exception) = fail("turnSignal", e)
    }

    fun start() {
        try {
            vehicle.initialize()
            update { copy(connected = true) }
        } catch (e: Exception) {
            fail("initialize", e)
            return
        }

        safe("plate") {
            vehicle.getCarInfo().getCRNInfo(
                { plate ->
                    // 에뮬레이터는 "invalid" 를 돌려준다 → 번호판 형식일 때만 채택
                    val valid = plate?.trim()?.takeIf { PLATE_REGEX.matches(it) }
                    update { copy(plate = valid, plateError = if (valid == null) "값: $plate" else null) }
                },
                { e -> update { copy(plateError = e.message ?: e.javaClass.simpleName) } },
            )
        }
        safe("gear") {
            val gear = vehicle.getGear()
            gear.getCurrentGearSelectionState({ g -> update { copy(gear = g) } }, { fail("gear", it) })
            gear.registerGearSelection(gearListener)
        }
        safe("turnSignal") {
            val ts = vehicle.getTurnSignal()
            ts.getCurrentTurnSignalState({ v -> update { copy(turnSignal = v) } }, { fail("turnSignal", it) })
            ts.registerTurnSignalState(turnSignalListener)
        }
    }

    fun stop() {
        if (!_state.value.connected) return
        safe("release") {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            vehicle.release()
        }
        update { copy(connected = false) }
    }

    private inline fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }

    private fun fail(where: String, e: Exception) {
        Log.w(TAG, "Vehicle $where failed", e)
        update { copy(lastError = "$where: ${e.message ?: e.javaClass.simpleName}") }
    }

    private inline fun safe(where: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            fail(where, e)
        }
    }

    private companion object {
        const val TAG = "RoadTalkVehicle"
        val PLATE_REGEX = Regex("""\d{2,3}\s*[가-힣]\s*\d{4}""")
    }
}
