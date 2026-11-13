package com.roadtalk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.roadtalk.app.ui.RoadTalkScreen
import com.roadtalk.app.ui.RoadTalkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RoadTalkViewModel by viewModels()

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onLocationPermission(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RoadTalkTheme {
                RoadTalkScreen(viewModel)
            }
        }
        if (savedInstanceState == null) {
            val granted = ContextCompat.checkSelfPermission(this, FUSED_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onLocationPermission(true) else locationPermission.launch(FUSED_LOCATION)
        }
        handleTestIntent(intent)
        if (isDebuggable()) {
            // 앱이 떠 있는 동안에도 시험할 수 있게 (Pleos 화면에서는 실행 중인 앱에 새 Intent 가 전달되지 않는다)
            ContextCompat.registerReceiver(this, sttTestReceiver, IntentFilter(ACTION_STT_TEST), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onDestroy() {
        if (isDebuggable()) runCatching { unregisterReceiver(sttTestReceiver) }
        super.onDestroy()
    }

    private val sttTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handleTestIntent(intent)
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    /**
     * 시험용 (디버그 빌드에서만): 마이크 없는 에뮬레이터에서 음성 인식을 확인한다.
     *   adb shell am broadcast --user 10 -a com.roadtalk.app.STT_TEST --es stt_test front_thanks
     * (음성 파일: app/src/debug/assets/stt_test/ 의 front_thanks · rear_go_first · sorry)
     */
    private fun handleTestIntent(intent: Intent?) {
        val debuggable = isDebuggable()
        val clip = intent?.getStringExtra("stt_test") ?: return
        android.util.Log.d("RoadTalkVoice", "stt_test intent: $clip (debuggable=$debuggable)")
        if (debuggable) viewModel.testVoiceWithClip(clip)
    }

    private companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val ACTION_STT_TEST = "com.roadtalk.app.STT_TEST"
    }
}
