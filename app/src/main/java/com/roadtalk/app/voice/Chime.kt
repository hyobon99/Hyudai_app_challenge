package com.roadtalk.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * '띵~' 하는 짧은 종소리. 다른 차가 보낸 메시지가 도착했다는 것을 화면을 보지 않고도 알게 해 준다 (받는 쪽에서만 울린다).
 * 소리 파일 없이 그 자리에서 만들어 낸다 (맑은 높은 음 + 배음이 0.7초에 걸쳐 잦아듦).
 */
class Chime {

    private val pcm: ShortArray = samples()

    fun ding() {
        Log.d(TAG, "ding")
        Thread({
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((DURATION_SECONDS * 1000).toLong() + 150)
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "ding failed", e)
            }
        }, "chime").start()
    }

    companion object {
        private const val TAG = "RoadTalkVoice"
        const val SAMPLE_RATE = 44_100
        const val DURATION_SECONDS = 0.7
        /** 기본음 (미6, 약 1319Hz) */
        private const val TONE_HZ = 1318.5

        /** 종소리 파형 (16bit mono). 안드로이드 없이도 계산되는 순수 함수 */
        fun samples(): ShortArray {
            val count = (SAMPLE_RATE * DURATION_SECONDS).toInt()
            return ShortArray(count) { i ->
                val t = i.toDouble() / SAMPLE_RATE
                val attack = (t / 0.004).coerceAtMost(1.0)          // 딱 소리 없이 시작
                val decay = exp(-t / 0.16)                           // 잦아듦
                val wave = sin(2 * PI * TONE_HZ * t) + 0.35 * sin(2 * PI * TONE_HZ * 2.01 * t) * exp(-t / 0.08)
                (wave * attack * decay * 0.55 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
    }
}
