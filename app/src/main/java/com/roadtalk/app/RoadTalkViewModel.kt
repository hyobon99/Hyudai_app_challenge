package com.roadtalk.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadtalk.app.chat.ProfanityFilter
import com.roadtalk.app.chat.PresetMessages
import com.roadtalk.app.chat.SpeechText
import com.roadtalk.app.chat.VoiceCommandParser
import com.roadtalk.app.chat.VoiceIntentResolver
import com.google.firebase.FirebaseApp
import com.roadtalk.app.location.LocationGateway
import com.roadtalk.app.navi.NaviGateway
import com.roadtalk.app.net.FirestoreRoadTalkRepository
import com.roadtalk.app.net.HttpRoadTalkRepository
import com.roadtalk.app.net.ServerStore
import com.roadtalk.app.ui.scene3d.CarLook
import com.roadtalk.app.vehicle.PlateStore
import com.roadtalk.app.net.MyPosition
import com.roadtalk.app.net.NearbyCar
import com.roadtalk.app.net.RelativePosition
import com.roadtalk.app.net.RoadMessage
import com.roadtalk.app.net.RoadTalkRepository
import com.roadtalk.app.net.SimulatedRoadTalkRepository
import com.roadtalk.app.vehicle.VehicleGateway
import com.roadtalk.app.voice.Chime
import com.roadtalk.app.voice.VoiceGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RoadTalkViewModel(app: Application) : AndroidViewModel(app) {

    val vehicle = VehicleGateway(app)
    val voice = VoiceGateway(app)
    private val chime = Chime()
    val navi = NaviGateway(app)
    private val location = LocationGateway(app)
    private val plateStore = PlateStore(app)

    private val serverStore = ServerStore(app)

    /** 서버 주소가 등록돼 있으면 별도 서버, 아니면 google-services.json 이 있으면 Firebase, 둘 다 없으면 시뮬레이션 */
    private val _repository = MutableStateFlow(createRepository())
    private val filter = ProfanityFilter()

    val serverUrl: String? get() = serverStore.load()
    val backendName: StateFlow<String> = _repository.map { it.backendName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _repository.value.backendName)
    val backendStatus: StateFlow<String?> = _repository.flatMapLatest { it.status }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val nearbyCars: StateFlow<List<NearbyCar>> = _repository.flatMapLatest { it.nearbyCars }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableStateFlow<List<RoadMessage>>(emptyList())
    val messages: StateFlow<List<RoadMessage>> = _messages.asStateFlow()

    private val _selectedPlate = MutableStateFlow<String?>(null)
    val selectedPlate: StateFlow<String?> = _selectedPlate.asStateFlow()

    /** 화면 상단에 크게 띄우는 수신 알림 (몇 초 뒤 사라짐) */
    private val _banner = MutableStateFlow<RoadMessage?>(null)
    val banner: StateFlow<RoadMessage?> = _banner.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 방금 내가 보낸 메시지 (받는 차 위에 몇 초간 표시) */
    private val _sent = MutableStateFlow<RoadMessage?>(null)
    val sent: StateFlow<RoadMessage?> = _sent.asStateFlow()

    /** 지도 패널용 내 위치 */
    val myLocation: StateFlow<LocationGateway.Fix?> get() = location.location

    /** 우선순위: 사용자가 등록한 번호 > SDK 가 준 번호 > 임시 번호 */
    private val _myPlate = MutableStateFlow(plateStore.load() ?: FALLBACK_PLATE)
    val myPlate: StateFlow<String> = _myPlate.asStateFlow()

    /** 내 차 색 (0~5). 고르지 않았으면 차량번호로 정해진 색 */
    private val _mySkin = MutableStateFlow(plateStore.loadSkin() ?: CarLook.defaultSkinOf(_myPlate.value))
    val mySkin: StateFlow<Int> = _mySkin.asStateFlow()

    init {
        viewModelScope.launch {
            vehicle.state.collect { s ->
                if (plateStore.load() == null && s.plate != null) _myPlate.value = s.plate
            }
        }
        vehicle.start()
        voice.start()
        navi.start()
        // 남은 거리·다음 회전은 순정 내비에 주기적으로 다시 물어본다 (이벤트만으로는 갱신이 드물 수 있음)
        viewModelScope.launch {
            while (true) {
                delay(3_000)
                navi.refresh()
                location.restartIfSilent(15_000)
            }
        }
        voice.onFinalResult = ::onVoiceResult

        viewModelScope.launch {
            _repository.flatMapLatest { it.incoming }.collect(::onIncoming)
        }
        viewModelScope.launch {
            combine(_repository, _mySkin) { repo, skin -> repo to skin }.collect { (repo, skin) -> repo.mySkin = skin }
        }
        viewModelScope.launch {
            combine(_repository, _myPlate, location.location) { repo, plate, fix -> Triple(repo, plate, fix) }
                .collect { (repo, plate, fix) ->
                    val position = fix?.let {
                        MyPosition(it.latitude, it.longitude, it.heading, it.speedMps, it.timestampMillis, it.accuracyMeters)
                    }
                    repo.updateMyPresence(plate, position)
                }
        }
        viewModelScope.launch {
            nearbyCars.collect { cars ->
                if (_selectedPlate.value == null || cars.none { it.plate == _selectedPlate.value }) {
                    _selectedPlate.value = cars.firstOrNull()?.plate
                }
            }
        }
    }

    /** FUSED_LOCATION 은 런타임 권한이라 Activity 에서 허용받은 뒤 시작한다. */
    fun onLocationPermission(granted: Boolean) {
        if (granted) location.start()
        else showNotice("위치 권한이 없어 근처 차량 검색이 제한돼요.")
    }

    /** 내 차 색 고르기 (0~5). 다른 차 화면에도 이 색으로 보인다 */
    fun setMySkin(skin: Int) {
        val value = skin.coerceIn(0, CarLook.SKIN_COUNT - 1)
        plateStore.saveSkin(value)
        if (_mySkin.value == value) return
        _mySkin.value = value
        showNotice("내 차 색을 ${CarLook.SKIN_NAMES[value]}(으)로 바꿨어요.")
    }

    /** 내 차량번호 등록. 형식이 맞지 않으면 false */
    fun setMyPlate(input: String): Boolean {
        val plate = PlateStore.normalize(input) ?: return false
        plateStore.save(plate)
        _myPlate.value = plate
        if (plateStore.loadSkin() == null) _mySkin.value = CarLook.defaultSkinOf(plate)
        showNotice("내 차량번호를 $plate 로 등록했어요.")
        return true
    }

    /** 서버 주소 등록. 빈 값이면 서버를 끄고 Firebase/시뮬레이션으로 돌아간다. 형식이 틀리면 false */
    fun setServerUrl(input: String): Boolean {
        val url = if (input.isBlank()) null else ServerStore.normalize(input) ?: return false
        serverStore.save(url)
        val old = _repository.value
        _repository.value = createRepository()
        old.close()
        showNotice(if (url == null) "별도 서버 연결을 해제했어요." else "$url 서버로 전환했어요.")
        return true
    }

    private fun createRepository(): RoadTalkRepository {
        serverStore.load()?.let { return HttpRoadTalkRepository(it) }
        return if (FirebaseApp.getApps(getApplication()).isNotEmpty()) FirestoreRoadTalkRepository()
        else SimulatedRoadTalkRepository()
    }

    fun select(plate: String) {
        _selectedPlate.value = plate
    }

    /** 3x3 칸을 누르면 그 방향에서 가장 가까운 차를 받는 사람으로 */
    fun selectDirection(position: RelativePosition) {
        val car = nearbyCars.value.filter { it.position == position }.minByOrNull { it.distanceMeters }
        if (car == null) showNotice("${position.cell} 방향에는 Car-ind CHAT 차가 없어요.")
        else _selectedPlate.value = car.plate
    }

    /** 지도에서 고른 위치로 순정 내비에 경로 안내를 요청한다 */
    fun startRouteTo(latitude: Double, longitude: Double) {
        if (navi.requestRoute(latitude, longitude, "지도에서 선택한 위치")) showNotice("순정 내비에 경로 안내를 요청했어요.")
        else showNotice("내비에 요청하지 못했어요.")
    }

    fun cancelRoute() {
        if (navi.cancelRoute()) showNotice("경로 안내 종료를 요청했어요.")
    }

    /** 정해진 메시지를 그 차에게 보낸다 (음성 명령으로 고른 것) */
    fun sendTo(plate: String, text: String): Boolean {
        _selectedPlate.value = plate
        return send(plate, text)
    }

    /** 말하기 버튼을 누름: 듣기 시작 */
    fun startTalking() = voice.startListening()

    /** 말하기 버튼에서 손을 뗌: 듣기 끝 → 그때까지 들은 말이 [onVoiceResult] 로 온다 */
    fun stopTalking() = voice.stopListening()

    /** 에뮬레이터 마이크가 안 될 때 음성 명령 흐름을 시험하는 용도 */
    fun simulateVoice(spoken: String) = onVoiceResult(spoken)

    /**
     * 시험용: 앱에 넣어 둔 음성 파일(assets/stt_test/이름.pcm, 디버그 빌드에만 들어 있음)을 STT SDK 에 넣어 인식시킨다.
     * 마이크만 빼고 실제와 같은 길(STT SDK → 문장 해석 → 전송)을 탄다.
     */
    fun testVoiceWithClip(name: String) {
        viewModelScope.launch {
            val pcm = try {
                getApplication<Application>().assets.open("stt_test/$name.pcm").use { it.readBytes() }
            } catch (e: Exception) {
                showNotice("시험용 음성 파일이 없어요: $name")
                return@launch
            }
            // 앱을 막 켠 직후라면 STT 초기화가 끝날 때까지 잠깐 기다린다
            repeat(50) {
                if (voice.state.value.sttReady) return@repeat
                delay(200)
            }
            voice.recognizePcm(pcm)
        }
    }

    /**
     * 음성 인식 결과 처리. 보낼 수 있는 말은 정해진 7개([PresetMessages])뿐이다.
     *  1) 비속어·위험한 말이면 무시하고 '띵' 소리만 낸다 (앱의 금칙어 사전, 그리고 서버의 AI 판단)
     *  2) 서버의 AI(TypeSafe AI 의 Jev)가 7개 중 일치도가 가장 높은 말과 받을 차를 고른다. AI 를 쓸 수 없으면 단어 매칭으로 대신한다
     *  3) 일치도가 높은 말이 있으면 그 차에게 보낸다 (받는 차에서는 TTS 로 읽어 준다)
     */
    private fun onVoiceResult(spoken: String) {
        viewModelScope.launch {
            val cars = nearbyCars.value
            val selectedPlate = _selectedPlate.value
            val selected = cars.firstOrNull { it.plate == selectedPlate }
            val hasProfanity = filter.check(spoken).hasProfanity
            // 금칙어가 있으면 서버에 묻지도 않는다
            val ai = if (hasProfanity) null else _repository.value.interpret(spoken, PresetMessages.ALL, cars, selected?.position)
            when (val result = VoiceIntentResolver.resolve(spoken, cars, selectedPlate, ai, hasProfanity)) {
                is VoiceIntentResolver.Result.Send -> {
                    val text = PresetMessages.ALL[result.messageIndex]
                    if (sendTo(result.car.plate, text)) {
                        // 화면을 보지 않아도 무엇이 보내졌는지 알 수 있게 읽어 준다
                        voice.speak(SpeechText.sent(result.car.position.label, text))
                    }
                }
                VoiceIntentResolver.Result.Blocked -> {
                    // 무시: 아무것도 보내지 않고 소리로만 알린다
                    chime.ding()
                    showNotice("보낼 수 없는 말이라 전송하지 않았어요.")
                }
                VoiceIntentResolver.Result.NoMessage -> {
                    chime.ding()
                    showNotice("보낼 수 있는 말 중에 맞는 게 없어요: \"$spoken\"")
                }
                is VoiceIntentResolver.Result.NoCarAt -> {
                    showNotice("${result.position.cell} 방향에는 Car-ind CHAT 차가 없어요.")
                    voice.speak("${result.position.cell} 방향에는 차가 없어요")
                }
                VoiceIntentResolver.Result.NoCar -> {
                    showNotice("근처에 메시지를 받을 차량이 없어요.")
                    voice.speak("근처에 받을 차가 없어요")
                }
            }
        }
    }

    private fun send(toPlate: String?, rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        if (toPlate == null) {
            showNotice("근처에 메시지를 받을 차량이 없어요.")
            return false
        }
        val checked = filter.check(text)
        if (checked.blocked) {
            showNotice("부적절한 표현이 포함되어 전송하지 않았어요.")
            voice.speak("부적절한 표현이 있어서 보내지 않았어요")
            return false
        }
        val message = RoadMessage(
            id = System.nanoTime(),
            fromPlate = _myPlate.value,
            toPlate = toPlate,
            text = checked.masked,
            timestampMillis = System.currentTimeMillis(),
            isMine = true,
            wasFiltered = checked.hasProfanity,
            peerPosition = positionOf(toPlate),
        )
        _messages.value = _messages.value + message
        _sent.value = message
        viewModelScope.launch {
            delay(3_500)
            if (_sent.value?.id == message.id) _sent.value = null
        }
        if (checked.hasProfanity) showNotice("일부 표현이 가려져서 전송됐어요.")
        viewModelScope.launch { _repository.value.send(message) }
        return true
    }

    private fun onIncoming(raw: RoadMessage) {
        // 받는 쪽에서도 한 번 더 필터링 (서버/상대 앱을 신뢰하지 않음)
        val checked = filter.check(raw.text)
        if (checked.blocked) return
        val message = raw.copy(
            text = checked.masked,
            wasFiltered = checked.hasProfanity,
            peerPosition = positionOf(raw.fromPlate),
        )
        _messages.value = _messages.value + message
        _banner.value = message
        // 운전 중에 화면을 보지 않아도 되게 소리로 읽어 준다 (Pleos TextToSpeech)
        voice.speak(SpeechText.incoming(message.peerPosition?.label, message.text))
        viewModelScope.launch {
            delay(6_000)
            if (_banner.value?.id == message.id) _banner.value = null
        }
    }

    private fun positionOf(plate: String): RelativePosition? {
        val key = VoiceCommandParser.normalizePlate(plate)
        return nearbyCars.value.firstOrNull { VoiceCommandParser.normalizePlate(it.plate) == key }?.position
    }

    private fun showNotice(text: String) {
        _notice.value = text
        viewModelScope.launch {
            delay(4_000)
            if (_notice.value == text) _notice.value = null
        }
    }

    override fun onCleared() {
        voice.stop()
        navi.stop()
        location.stop()
        vehicle.stop()
        _repository.value.close()
    }

    private companion object {
        const val FALLBACK_PLATE = "99허 0000"
    }
}
