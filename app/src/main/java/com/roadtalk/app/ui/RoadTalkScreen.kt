package com.roadtalk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import com.roadtalk.app.ui.scene3d.CarLook
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadtalk.app.RoadTalkViewModel
import com.roadtalk.app.net.RoadMessage
import com.roadtalk.app.vehicle.VehicleGateway
import com.roadtalk.app.voice.VoiceGateway
import ai.pleos.playground.vehicle.constant.control.VehicleGear

/** 기본 화면: 왼쪽에 위에서 본 도로(주변 Car-ind CHAT 차), 가운데에 내비게이션(지도 + 순정 내비 안내 정보) */
@Composable
fun RoadTalkScreen(vm: RoadTalkViewModel) {
    val vehicle by vm.vehicle.state.collectAsStateWithLifecycle()
    val voice by vm.voice.state.collectAsStateWithLifecycle()
    val navi by vm.navi.state.collectAsStateWithLifecycle()
    val cars by vm.nearbyCars.collectAsStateWithLifecycle()
    val selected by vm.selectedPlate.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val sent by vm.sent.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val myPlate by vm.myPlate.collectAsStateWithLifecycle()
    val mySkin by vm.mySkin.collectAsStateWithLifecycle()
    val myCarType by vm.myCarType.collectAsStateWithLifecycle()
    val garageOpen by vm.garageOpen.collectAsStateWithLifecycle()
    val buildings by vm.buildings.collectAsStateWithLifecycle()
    val myLocation by vm.myLocation.collectAsStateWithLifecycle()
    val backendStatus by vm.backendStatus.collectAsStateWithLifecycle()
    val backendName by vm.backendName.collectAsStateWithLifecycle()
    var editingPlate by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(RoadColors.Background)
            .padding(20.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                myPlate = myPlate,
                backend = backendName,
                backendStatus = backendStatus,
                vehicle = vehicle,
                voice = voice,
                mySkin = mySkin,
                onEditPlate = { editingPlate = true },
                onPickSkin = vm::openGarage,
                onEditServer = { editingServer = true },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BirdEyePanel(
                    cars = cars,
                    selectedPlate = selected,
                    incoming = banner,
                    sent = sent,
                    voice = voice,
                    myType = myCarType,
                    mySkin = mySkin,
                    onMyCarTap = vm::openGarage,
                    myFix = myLocation,
                    buildings = buildings,
                    onSelectCar = vm::select,
                    onSelectDirection = vm::selectDirection,
                    onTalkStart = vm::startTalking,
                    onTalkEnd = vm::stopTalking,
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                )
                if (garageOpen) {
                    GaragePanel(
                        myType = myCarType,
                        mySkin = mySkin,
                        onPickType = vm::setMyCarType,
                        onPickSkin = vm::setMySkin,
                        onClose = vm::closeGarage,
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                    )
                } else {
                    NavPanel(
                        myFix = myLocation,
                        cars = cars,
                        navi = navi,
                        onRequestRoute = vm::startRouteTo,
                        onCancelRoute = vm::cancelRoute,
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = banner != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            // 지도 위쪽에 띄워서 도로 화면(보낸 차 위 말풍선)과 마이크 버튼을 가리지 않게
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 120.dp, end = 32.dp),
        ) {
            banner?.let { IncomingBanner(it) }
        }

        notice?.let {
            Text(
                it,
                color = Color.Black,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RoadColors.Warn)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (editingPlate) {
        PlateDialog(
            current = myPlate,
            onSave = vm::setMyPlate,
            onDismiss = { editingPlate = false },
        )
    }
    if (editingServer) {
        ServerDialog(
            current = vm.serverUrl.orEmpty(),
            onSave = vm::setServerUrl,
            onDismiss = { editingServer = false },
        )
    }
}

@Composable
private fun ServerDialog(current: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(current) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Car-ind CHAT 서버 주소") },
        text = {
            Column {
                TextField(value = input, onValueChange = { input = it; invalid = false }, singleLine = true)
                Text(
                    if (invalid) "형식이 맞지 않아요 (예: 192.168.0.10:8080)"
                    else "예: 192.168.0.10:8080 · 비워서 저장하면 Firebase로 돌아가요",
                    color = if (invalid) RoadColors.Error else RoadColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(input)) onDismiss() else invalid = true }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun PlateDialog(current: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(current) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("내 차량번호 등록") },
        text = {
            Column {
                TextField(value = input, onValueChange = { input = it; invalid = false }, singleLine = true)
                Text(
                    if (invalid) "형식이 맞지 않아요 (예: 12가 3456)" else "예: 12가 3456",
                    color = if (invalid) RoadColors.Error else RoadColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(input)) onDismiss() else invalid = true }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun TopBar(
    myPlate: String,
    backend: String,
    backendStatus: String?,
    vehicle: VehicleGateway.State,
    voice: VoiceGateway.State,
    mySkin: Int,
    onEditPlate: () -> Unit,
    onPickSkin: () -> Unit,
    onEditServer: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Car-ind CHAT", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = RoadColors.TextPrimary)
        Spacer(Modifier.width(20.dp))
        Pill("내 차 $myPlate ✎", RoadColors.SurfaceHigh, Modifier.clickable(onClick = onEditPlate))
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(RoadColors.SurfaceHigh)
                .clickable(onClick = onPickSkin)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(CarLook.SKIN_COLORS[mySkin.coerceIn(0, CarLook.SKIN_COUNT - 1)]))
            )
            Spacer(Modifier.width(8.dp))
            Text("내 차 꾸미기", fontSize = 15.sp, color = RoadColors.TextPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Pill("서버: $backend ✎", RoadColors.SurfaceHigh, Modifier.clickable(onClick = onEditServer))
        Spacer(Modifier.width(8.dp))
        val gearText = when (vehicle.gear) {
            null -> "기어 정보 없음"
            VehicleGear.GEAR_PARK -> "P 주차"
            VehicleGear.GEAR_REVERSE -> "R 후진"
            VehicleGear.GEAR_NEUTRAL -> "N 중립"
            else -> "D 주행"
        }
        Pill(gearText, if (vehicle.isParked) RoadColors.AccentDim else RoadColors.SurfaceHigh)
        Spacer(Modifier.weight(1f))
        StatusDot("Vehicle", vehicle.connected)
        StatusDot("STT", voice.sttReady)
        StatusDot("TTS", voice.ttsReady)
    }
    val errors = listOfNotNull(backendStatus, vehicle.lastError, voice.error)
    if (errors.isNotEmpty()) {
        Text(
            errors.joinToString("  ·  "),
            color = RoadColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun IncomingBanner(message: RoadMessage) {
    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(min = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RoadColors.Accent)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${message.peerPosition?.label ?: "근처 차"}가 보낸 메시지", fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f))
        Text(message.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 15.sp,
        color = RoadColors.TextPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun StatusDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (ok) RoadColors.Ok else RoadColors.Error)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, color = RoadColors.TextSecondary)
    }
}
