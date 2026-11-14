package com.roadtalk.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos SpeechToText / TextToSpeech 래퍼. 공식 가이드(SpeechToText → Programming Guide)의 순서를 따른다:
 *  1) 권한 pleos.car.permission.STT_SERVICE 선언 (AndroidManifest)
 *  2) SpeechToText(context, Mode) 생성 → initialize(OnInitializeListener)
 *  3) 초기화 성공 뒤 addListener(ResultListener), setLocale(Locale.KOREA)
 *  4) request() 로 차량 마이크 인식 시작 → onUpdated(text, completed) 로 중간·최종 결과 → stop()
 *     (말하기 버튼을 한 번 누르면 request(). 인식된 글자가 1.5초 동안 더 바뀌지 않으면 말을 멈춘 것으로 보고 stop() → [Endpointer].
 *      그때까지 들은 말이 결과가 된다. 듣는 중에 버튼을 한 번 더 눌러도 끝난다)
 *  5) 끝낼 때 removeListener → release()
 * ON_DEVICE 모드를 써서 앱 등록(서버 인증) 없이 바로 동작하게 한다.
 * 서버 인식 품질이 필요하면 Pleos Playground 에서 My Project 를 만들어 받은 값으로 HYBRID + registerApp() 을 쓰면 된다.
 */
class VoiceGateway(context: Context) {

    enum class ListenState { IDLE, PREPARING, LISTENING }

    data class State(
        val sttReady: Boolean = false,
        val ttsReady: Boolean = false,
        val listen: ListenState = ListenState.IDLE,
        val partial: String = "",
        val error: String? = null,
    )

    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 최종 인식 결과 콜백 (ViewModel 이 등록). 한 번 말할 때마다 한 번만 불린다 */
    var onFinalResult: ((String) -> Unit)? = null

    /** 이번에 듣기 시작한 뒤로 인식된 말 / 그 말을 이미 넘겼는지 */
    @Volatile private var heard = ""
    @Volatile private var delivered = false

    /** 말을 멈췄는지 판단 */
    private val endpointer = Endpointer()
    /** 마이크로 듣기를 시작할 때마다 1씩 늘어난다 (끝난 듣기를 지켜보던 스레드가 다음 듣기를 건드리지 않게) */
    @Volatile private var session = 0

    private fun beginUtterance() {
        heard = ""
        delivered = false
    }

    @Synchronized
    private fun deliver(text: String) {
        if (delivered || text.isBlank()) return
        delivered = true
        onFinalResult?.invoke(text)
        // 화면의 '인식됨: …' 은 잠깐만 보여 주고 지운다 (예전 문장이 계속 남아 있으면 헷갈린다)
        Thread({
            Thread.sleep(SHOW_HEARD_MILLIS)
            if (delivered && _state.value.partial == text) update { copy(partial = "") }
        }, "stt-clear").start()
    }

    private val resultListener = object : ResultListener {
        override fun onReady() = update { copy(listen = ListenState.PREPARING) }
        override fun onStartedRecognition() {
            endpointer.begin(SystemClock.elapsedRealtime())
            update { copy(listen = ListenState.LISTENING, partial = "") }
        }
        override fun onUpdated(stt: String, completed: Boolean) {
            Log.d(TAG, "STT ${if (completed) "final" else "partial"}: $stt")
            endpointer.heard(stt, SystemClock.elapsedRealtime())
            heard = stt
            update { copy(partial = stt) }
            if (completed) deliver(stt)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            update { copy(listen = ListenState.IDLE) }
            // SDK 는 '인식 끝'을 알린 직후에 최종 결과(더 정확하게 다듬은 문장)를 주는 경우가 많다 → 잠깐 기다린다.
            // 듣기를 끝냈는데 최종 결과가 끝내 안 오면, 그때까지 들은 말을 결과로 쓴다
            Thread({
                Thread.sleep(FINAL_WAIT_MILLIS)
                deliver(heard)
            }, "stt-final").start()
        }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "STT error $errorType")
            update { copy(listen = ListenState.IDLE, error = "음성 인식: ${describe(errorType)}") }
        }
    }

    /** SDK 가이드의 Error code 표를 운전자가 알아볼 말로 */
    private fun describe(error: SttError): String = when (error) {
        SttError.MIC_ERROR -> "마이크를 쓸 수 없어요 (마이크 상태·개인정보 보호 설정 확인)"
        SttError.AUDIO_ERROR -> "음성 데이터를 처리하지 못했어요"
        SttError.PERMISSION_DENIED -> "음성 인식 권한이 없어요"
        SttError.ON_DEVICE_ENGINE_ERROR -> "차량 안 인식 엔진에 문제가 있어요"
        SttError.UNAVAILABLE_LANGUAGE -> "한국어 인식을 쓸 수 없어요"
        SttError.INVALID_REQUEST_ERROR -> "이미 듣는 중이거나 요청 순서가 맞지 않아요"
        SttError.TIMEOUT -> "시간 안에 끝나지 않았어요"
        SttError.NETWORK_ERROR, SttError.SERVER_INTERNAL_ERROR, SttError.UNAUTHENTICATED -> "서버 인식을 쓸 수 없어요 ($error)"
        else -> error.toString()
    }

    private val ttsListener = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: TtsError) = update { copy(error = "TTS: $errorType") }
    }

    fun start() {
        try {
            stt.initialize(object : SttInitListener {
                override fun onSuccess() {
                    stt.addListener(resultListener)
                    runCatching { stt.setLocale(Locale.KOREA) }
                    update { copy(sttReady = true) }
                }

                override fun onFailure(errorType: SttError) =
                    update { copy(error = "STT init: $errorType") }
            })
        } catch (e: Exception) {
            fail("STT init", e)
        }
        try {
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    tts.addEventListener(ttsListener)
                    runCatching { tts.setLocale(Locale.KOREA) }
                    update { copy(ttsReady = true) }
                }

                override fun onFailure(errorType: TtsError) =
                    update { copy(error = "TTS init: $errorType") }
            })
        } catch (e: Exception) {
            fail("TTS init", e)
        }
    }

    fun startListening() {
        if (!_state.value.sttReady) {
            update { copy(error = "음성인식이 아직 준비되지 않았어요") }
            return
        }
        if (_state.value.listen != ListenState.IDLE) return
        try {
            beginUtterance()
            endpointer.begin(SystemClock.elapsedRealtime())
            update { copy(error = null, partial = "", listen = ListenState.PREPARING) }
            stt.request()
            session += 1
            watchForEndOfSpeech(session)
        } catch (e: Exception) {
            fail("STT request", e)
        }
    }

    fun stopListening() {
        runCatching { stt.stop() }
    }

    /**
     * 듣는 동안 0.1초마다 '말을 멈췄는지' 를 본다. 멈췄으면 인식을 끝낸다 (그 뒤는 손으로 끝냈을 때와 같다: onEndedRecognition → 결과 전달).
     * SDK 가 스스로 끝내거나 오류가 나면(listen 이 IDLE 이 되면) 그만 지켜본다.
     */
    private fun watchForEndOfSpeech(mine: Int) {
        Thread({
            while (session == mine && _state.value.listen != ListenState.IDLE) {
                Thread.sleep(100)
                if (!endpointer.isOver(SystemClock.elapsedRealtime())) continue
                Log.d(TAG, "end of speech: stop listening, heard=$heard")
                runCatching { stt.stop() }
                // SDK 가 '인식 끝' 을 알려 주지 않아도 버튼이 '듣는 중' 에 멈춰 있지 않게, 잠깐 기다렸다가 직접 끝낸다
                Thread.sleep(STOP_GRACE_MILLIS)
                if (session == mine && _state.value.listen != ListenState.IDLE) {
                    update { copy(listen = ListenState.IDLE) }
                    deliver(heard)
                }
                return@Thread
            }
        }, "stt-endpoint").start()
    }

    /**
     * 마이크 대신 앱이 가진 음성 데이터를 인식시킨다 (SDK 가이드의 'Sending audio data'. ON_DEVICE 모드 전용).
     * 형식은 16kHz · 16bit · mono PCM (헤더 없음). 가이드 예시처럼 0.1초 분량(3,200바이트)씩 실제 말하는 빠르기로 보낸다.
     * 결과는 마이크로 말했을 때와 똑같이 [onFinalResult] 로 온다. 마이크가 없는 에뮬레이터에서 인식 흐름을 시험하는 용도.
     */
    fun recognizePcm(pcm: ByteArray) {
        if (!_state.value.sttReady) {
            Log.w(TAG, "recognizePcm: STT not ready")
            update { copy(error = "음성인식이 아직 준비되지 않았어요") }
            return
        }
        beginUtterance()
        update { copy(error = null, partial = "") }
        Log.d(TAG, "sending ${pcm.size} bytes of PCM audio to STT")
        Thread({
            try {
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + PCM_CHUNK_BYTES, pcm.size)
                    stt.sendAudio(pcm.copyOfRange(offset, end))
                    offset = end
                    Thread.sleep(100)
                }
                stt.completeAudioSend()
                Log.d(TAG, "sent ${pcm.size} bytes of PCM audio")
            } catch (e: Exception) {
                fail("STT sendAudio", e)
            }
        }, "stt-pcm").start()
    }

    fun speak(text: String) {
        if (!_state.value.ttsReady) return
        try {
            tts.speak(text, true)
        } catch (e: Exception) {
            fail("TTS speak", e)
        }
    }

    fun stop() {
        runCatching {
            stt.removeListener(resultListener)
            stt.release()
        }
        runCatching {
            tts.removeEventListener(ttsListener)
            tts.release()
        }
        _state.value = State()
    }

    private inline fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }

    private fun fail(where: String, e: Exception) {
        Log.w(TAG, "$where failed", e)
        update { copy(listen = ListenState.IDLE, error = "$where: ${e.message ?: e.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "RoadTalkVoice"
        /** 16kHz · 16bit · mono 의 0.1초 분량 */
        const val PCM_CHUNK_BYTES = 3_200
        /** '인식 끝' 뒤에 최종 결과를 기다리는 시간 */
        const val FINAL_WAIT_MILLIS = 500L
        /** stop() 뒤에 SDK 의 '인식 끝' 을 기다리는 시간 */
        const val STOP_GRACE_MILLIS = 1_500L
        /** 인식된 문장을 화면에 남겨 두는 시간 */
        const val SHOW_HEARD_MILLIS = 5_000L
    }
}
