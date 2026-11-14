# Car-ind CHAT (카인드챗)


차량용 채팅 어플리케이션, 근처 현대 차끼리 '감사합니다', '먼저 가세요' 같은 짧은 의사소통을 할 수 있는 차량 간 소통 앱입니다.

---

## 주요 기능
- **근처 차량 3D 표시**: Car-ind CHAT을 켠 주변 차를 내 차 뒤 위에서 본 3D 도로에 실제 위치대로 그린다. 건물·차로·교차로·횡단보도는 지도(OpenStreetMap) 자료로 실제와 같게 세운다
- **음성으로 보내기**: "음성으로 말하기" 버튼을 한 번 누르고 "뒷차한테 고맙다고 해줘"라고 말하면, AI(TypeSafe AI의 Jev)가 받을 차와 메시지 함의 말 중 가장 가까운 것을 골라 보낸다
- **메시지 함**: 보낼 수 있는 말 23개 중 6개를 운전자가 골라 기본 메시지 함에 담는다. 인사뿐 아니라 '라이트 꺼져 있어요', '주유구가 열려 있어요' 같은 안전 알림도 있다
- **받은 메시지 읽어 주기**: 상대 차 위에 말풍선으로 뜨고, TextToSpeech SDK가 "앞차가 보낸 메시지. 감사합니다"처럼 읽어 준다
- **욕설·위험한 말 차단**: 금칙어 사전과 AI 판정으로 걸러, 보내지 않고 '띵~' 소리만 낸다
- **순정 내비 연동**: NaviHelper SDK로 다음 회전, 목적지, 남은 거리·시간, 제한속도를 보여 주고, 지도에서 고른 곳으로 경로 안내를 요청한다
- **내 차 고르기**: 쇼룸 화면에서 차종 6가지와 색 6가지를 고르면 다른 차 화면에도 그 모습으로 보인다

## 사용하는 Pleos Connect SDK
| SDK | 쓰는 곳 |
|---|---|
| FusedLocation SDK | 내 위치·진행 방향 (`location/LocationGateway.kt`) |
| SpeechToText SDK | 음성으로 보내기, ON_DEVICE 모드 (`voice/VoiceGateway.kt`) |
| TextToSpeech SDK | 받은 메시지·전송 결과 읽어 주기 (`voice/VoiceGateway.kt`) |
| NaviHelper SDK | 순정 내비 정보 표시, 경로 안내 요청·취소 (`navi/NaviGateway.kt`) |
| Vehicle SDK | 차량번호(CRN), 기어(P 여부), 방향지시등 상태 (`vehicle/VehicleGateway.kt`) |

---

## 설치 및 실행 가이드

### 0. 준비물
- **최신 Android Studio** (이 프로젝트는 AGP 9.4.1 / Gradle 9.6 / compileSdk 37 사용)
- **인터넷 연결** (첫 빌드 때 Gradle, Pleos SDK, Firebase 라이브러리를 자동으로 내려받음)
- PC RAM 16GB 이상 권장 (Pleos 에뮬레이터 1대가 RAM 4GB 사용)
- **Firebase는 따로 설치하거나 가입할 필요 없음.** 프로젝트에 `app/google-services.json`이 들어 있어서 팀 Firebase(`hyundai-roadtalk`)에 자동 연결됨

### 1. Pleos Connect 에뮬레이터 설치
공식 가이드: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator

1. Android Studio → **Tools → SDK Manager → SDK Update Sites** → `+`
   - Name: `Pleos Connect System Image`
   - URL (Windows): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. **SDK Platforms** 탭 → Show Package Details → API 34의 **Pleos Connect system image** 설치
3. 같은 탭에서 **Android API 37** 플랫폼도 설치
4. Android Studio 재시작
5. **Device Manager → Create device → Automotive → Pleos Connect v2.1.0**
   - Advanced Settings: Default boot **Cold**, RAM **4 GB**
   - System image: API 34 **Pleos Connect v2.1.0 APIs**

### 2. 프로젝트 열기
1. 프로젝트 폴더(`RoadTalk`)를 받는다 (GitHub clone 또는 압축 파일)
   - 압축으로 받았다면 `local.properties` 파일은 **삭제**
2. Android Studio → **File → Open** → `RoadTalk` **폴더** 선택 → Trust Project
3. 오른쪽 아래 Gradle Sync가 끝날 때까지 대기

### 3. 실행
1. Device Manager에서 Pleos Connect 에뮬레이터 ▶ 실행
2. 상단 툴바 기기 선택 칸에서 그 에뮬레이터 선택 → **▶ Run**
3. 앱 첫 실행 때 "알 수 없는 작업 실행 허용?" 창 → **허용**
4. 맨 위의 앱 이름 **"Car-ind CHAT"을 길게 눌러** 뜨는 설정 창에서 **각자 다른 차량번호** 등록 (예: `12가 3456`)
5. 같은 설정 창에 **"서버: Firebase"** 가 보이면 연결 성공

### 4. 차량 두 대 시뮬레이션
- Device Manager에서 Pleos 에뮬레이터를 하나 더 만들어 두 개를 동시에 켠다
- 기기 선택 칸 → **Select Multiple Devices...** → 둘 다 체크 → Run
- 각 에뮬레이터에서 번호를 다르게 등록하면 서로 "근처 현대차"에 뜨고 메시지를 주고받을 수 있음
- 다른 PC의 팀원 에뮬레이터와도 대화 가능 (같은 Firebase를 쓰고, 상대 거리가 300m 안이면)

### 자주 발생하는 문제
| 증상 | 해결 |
|---|---|
| ▶ Run 버튼이 회색 | **File → Sync Project with Gradle Files** 후 대기 |
| `Modifications to AndroidManifest.xml require an app restart` | 빨간 **■ Stop** 누르고 ▶ Run |
| 설정 창에 "서버: 시뮬레이션" | `app/google-services.json`이 없음 → 파일 받아서 `app/`에 넣고 Sync |
| "Firebase 오류: PERMISSION_DENIED" | Firestore 규칙 확인 (아래 Firebase 섹션) |
| 히터·안전벨트 등 차량 기능이 반응 없음 | Virtual Console 사용: `adb forward tcp:8088 tcp:8088` → 브라우저 http://127.0.0.1:8088 |

---

## 화면 구성

### 왼쪽: 3D 도로 (`ui/BirdEyeView.kt`, `ui/scene3d/`)
- 내 차 뒤 위에서 본 3D 도로에 Car-ind CHAT을 켠 차만 GPS 상대 위치에 표시한다. 차를 누르면 그 차가 받는 차로 선택된다 (차 아래 네온 고리, 처음에는 가장 가까운 차)
- 추가 라이브러리 없이 OpenGL ES 2.0으로 직접 그린다 (`ui/scene3d/Scene3DView.kt`). 내 속도만큼 도로가 뒤로 흘러간다. 카메라는 내 차 뒤 위에서 45도로 내려다보고 거리는 고정이다 (뒤 약 8m, 앞 약 46m까지 보인다 · `ui/scene3d/Camera3D.kt`). 그보다 멀리 있는 차는 화면 맨 아래·맨 위에 걸치게 그린다
- **주변 차로 보는 범위** (`net/Perception.kt`): 차의 카메라 인식 거리를 바탕으로 방향마다 거리를 정했다 (실제 카메라를 쓰는 것은 아니다)
  - 정면(좌우 약 17도) 180m · 정면 좌우 약 25도 100m · 앞 옆 80m · 뒤 옆 100m · 바로 뒤 50m
  - 범위 밖의 차는 3D에는 안 보이고 오른쪽 지도에만 점으로 나온다
- **지도 기반 주변 모습** (`map/`): 내 위치 둘레의 OpenStreetMap 자료로 실제 모습을 세운다
  - 건물: 바닥 윤곽 그대로 벽을 세우고, 높이는 지도의 높이·층수(없으면 바닥 넓이로 짐작)를 쓴다 (`map/CityMesh.kt`)
  - 차로: 내가 달리는 길의 차로 수대로 그리고, 양방향 도로면 노란 중앙선을 긋는다
  - 교차로·횡단보도·신호등: 지도의 위치·방향·폭대로 그린다. 신호등은 지금 신호 색을 알 수 없어서 불이 꺼진 모습으로 그린다
  - 시연 위치 일대(강남역 북쪽 반경 750m)는 앱에 넣어 두었고(`assets/buildings_demo.json`), 그 밖에서는 Overpass API에서 그때그때 받는다. 다른 곳을 넣어 두려면 `python tools/assets/fetch_buildings.py 위도 경도 반경`
  - 한계: 내 길은 굽은 모양을 따르지 않고 곧게 그린다. GPS 오차만큼 내 차가 옆 차로에 놓일 수 있다
- **차로 지키기** (`ui/scene3d/Lanes.kt`): 내 차와 내 길 위의 주변 차는 가장 가까운 차로의 가운데에 그린다. GPS로 계산한 좌우 위치는 1~2m씩 틀릴 수 있어서, 그대로 그리면 차선을 밟고 달리는 것처럼 보이기 때문이다. 차로를 바꾸는 차는 옆 차로 가운데로 부드럽게 옮겨 간다. 지도의 차로 수는 없거나 틀릴 수 있어서, 내 옆 차로에 차가 있으면 그 차가 길 안에 들어오도록 내 차로를 정하고 모자라면 차로를 늘려 그린다
- **서 있을 때의 방향**: 에뮬레이터는 차의 방향을 주지 않아서 앱이 이동 방향으로 계산한다. 앱을 켠 뒤 아직 움직이지 않아 방향을 모를 때는 지도에서 가장 가까운 도로가 뻗은 방향을 내 방향으로 본다 (`map/OsmRoads.kt`의 `bearingAt`)
- **계절**: 가로수 잎 색이 달에 따라 바뀐다 — 봄 분홍, 여름 초록, 가을 주황, 겨울 흰색 (`ui/scene3d/Seasons.kt`)
- **끌어서 보내기**: 차를 누른 채 끌면 기본 메시지 함의 6개가 그 차 둘레에 뜨고(메시지 함 화면의 6칸과 같은 자리), 손을 뗀 방향의 말이 그 차에게 전송된다. 거의 안 끌고 떼면 그 차를 받는 차로 선택만 한다
- 도로 아래: **"음성으로 말하기" 버튼**. 한 번 누르고 말하면, 말을 멈춘 뒤 알아서 듣기를 끝내고 전송한다 (아래 "음성으로 보내기")
- 오른쪽 위 동그란 버튼 둘: **차 그림 → 내 차 고르기**, **말풍선 그림 → 메시지 함**

### 내 차 고르기 (`ui/GaragePanel.kt`, `ui/scene3d/GarageView.kt`)
- 회전 받침 위의 차 둘레에 차종 카드 6장(미니·세단·해치백·밴·픽업·스포츠카)이 놓이고, 아래에서 색 6가지를 고른다
- 고르는 즉시 적용되고, 서버를 거쳐 **다른 차 화면에도 그 차종·색으로** 보인다 (HTTP 서버 모드). 고르지 않은 차는 차량번호로 정해진 모양·색 (`ui/scene3d/CarLook.kt`)

### 메시지 함 (`ui/MessagesPanel.kt`, `chat/MessageCatalog.kt`)
- 왼쪽은 보낼 수 있는 말 **23개**의 목록, 오른쪽은 **기본 메시지 함 6칸**
- 목록의 말을 바꾸고 싶은 칸 위로 끌어다 놓으면 그 칸이 바뀐다 (옆이나 비스듬히 끌면 바로 집히고, 거의 위아래로 끌면 목록이 넘어간다. 잠깐 누르고 있어도 집힌다)
- 지금 함에 든 말은 목록에서 네온으로 빛나고, 들지 않은 말은 회색빛이다
- 처음 6개: 감사합니다 · 좋은 하루 보내세요 · 죄송합니다 · 먼저 가세요 · 라이트 꺼져 있어요 · 추월 가능합니다
- 전체 23개: 감사합니다 · 조심히 가세요 · 좋은 하루 보내세요 · 죄송합니다 · 제가 뒤로 갈게요 · 제가 비켜 드릴게요 · 끼어들어도 될까요? · 주유구가 열려 있어요 · 라이트 꺼져 있어요 · 브레이크 등이 안 들어와요 · 짐이 떨어질 것 같아요 · 차에서 연기가 나요 · 조심하세요 · 추월 가능합니다 · 전방에 위험이 있습니다 · 차량에 이상이 있습니다 · 전방 정체 중입니다 · 정차하겠습니다 · 보행자가 있습니다 · 승객이 탑승 중입니다 · 승객이 하차 중입니다 · 먼저 가세요 · 네

### 받은 메시지
- 차 위에 네온 말풍선으로 뜨고, **TextToSpeech SDK**로 읽어 준다 (예: "앞차가 보낸 메시지. 감사합니다" · `chat/SpeechText.kt`)

### 오른쪽: 내비게이션 (`ui/NavPanel.kt`)
- 지도는 OpenStreetMap 타일(osmdroid), 내 위치 중심·진행 방향이 위. 지도에 보이는 범위(최소 300m · 최대 3km) 안의 차가 점으로 나온다
- 그 위에 **NaviHelper SDK**(`navi/NaviGateway.kt`)로 받은 순정 내비 정보를 표시: 다음 회전, 목적지, 남은 거리·시간, 제한속도
- 지도를 길게 누르면 그 위치로 순정 내비에 경로 안내를 요청하고, 안내 카드의 "안내 종료"로 취소한다

---

## 음성으로 보내기 (SpeechToText SDK → Jev → TextToSpeech SDK)
1. **"음성으로 말하기" 버튼을 한 번 누르고 말한다.** 누르면 STT SDK의 `request()`. 인식된 글자가 1.5초 동안 더 바뀌지 않으면 말을 멈춘 것으로 보고 `stop()` 한다 (`voice/Endpointer.kt`). 아무 말도 없으면 마이크가 준비된 때부터 6초 뒤에 끝내고, 듣는 중에 버튼을 한 번 더 눌러도 끝난다 (`voice/VoiceGateway.kt`)
2. **Pleos SpeechToText SDK**가 말을 글자로 바꾼다
3. 앱의 금칙어 사전(`chat/ProfanityFilter.kt`)에 걸리면 **보내지 않고 '띵~' 소리**만 낸다 (`voice/Chime.kt`)
4. 서버가 **TypeSafe AI의 Jev 모델**에게 묻는다 (`server/voice_ai.py`): 기본 메시지 함 6개 중 뜻이 가장 가까운 말, 맞는 말이 있기는 한지, 욕설·위협·위험한 말인지, 어느 방향의 차에게 하는 말인지
5. 욕설·위험한 말이거나 맞는 말이 없으면 **보내지 않고 '띵~'** 소리. 맞는 말이 있으면 그 차에게 전송하고 "뒷차에게 감사합니다 보냈어요"라고 읽어 준다
6. 받는 차에서는 말풍선과 함께 **TextToSpeech SDK**가 읽어 준다

- 음성으로 보낼 수 있는 말은 **기본 메시지 함의 6개**다. 앱이 음성 해석을 요청할 때 지금 함에 든 6개를 서버에 보낸다
  - "뒷차한테 땡큐 보내줘" → 뒷차에게 "감사합니다" · "앞차 전조등 꺼졌다고 알려줘" → 앞차에게 "라이트 꺼져 있어요" · 대상을 말하지 않으면 지금 선택된 차(파란 빛 고리)
- STT는 공식 가이드(SpeechToText → Programming Guide) 순서를 따른다: 권한 `pleos.car.permission.STT_SERVICE` → `SpeechToText(context, Mode.ON_DEVICE)` → `initialize(listener)` → `addListener` · `setLocale(Locale.KOREA)` → `request()` / `stop()` → 끝낼 때 `removeListener` · `release()`
  - `ON_DEVICE` 모드라 앱 등록(My Project의 clientId/secret)이 필요 없다
  - 핸들의 음성 버튼은 Gleo 전용이라 앱에서 쓸 수 없다 → 화면의 말하기 버튼으로 시작

### Jev (TypeSafe AI) 설정
Jev는 글을 쓰지 않고, 물어본 질문마다 "선택지 중 하나 + 확률"만 돌려주는 모델이다 (문서: https://docs.typesafe.ai). 그래서 메시지 함 밖의 말은 나올 수 없다.

- 서버가 Jev를 부른다 (`POST /v1/interpret`, 규격은 `docs/SERVER_API.md`). **API 키는 서버 PC에만 둔다.** 앱(APK)이나 GitHub에는 넣지 않는다
- 받아들이는 기준은 `server/voice_ai.py` 위쪽의 숫자로 조절한다: 고른 문구의 확률 0.5 이상(`MATCH_AT`), "맞는 문구가 있다" 0.5 이상(`FITS_AT`), "욕설·위험한 말이다" 0.5 이상이면 무시(`HARMFUL_AT`)
- 키가 없거나, 인터넷이 안 되거나, 10초 안에 답이 없으면 앱이 **단어 매칭**(`MessageCatalog.match`)으로 대신한다. 메시지마다 흔한 단어(감사·고마워·땡큐, 미안·죄송, 먼저 가, 추월, 라이트·전조등, 주유구, 브레이크 등)가 정해져 있다

켜는 방법 (서버를 돌리는 PC에서 한 번만):

```
pip install typesafe-sdk
setx TYPESAFE_API_KEY "여기에_발급받은_키"
```

1. 키는 https://console.typesafe.ai/keys 에서 만든다
2. `setx` 뒤에 서버를 다시 켤 필요는 없다 (서버가 사용자 환경 변수를 직접 읽는다)
3. 확인: 브라우저에서 `http://127.0.0.1:8090/health` → `"ai": "ready"` 면 켜진 것 (`no_key` = 키 없음, `no_sdk` = `pip install typesafe-sdk` 필요)
4. 모델은 기본 `jev-latest`. 바꾸려면 `setx TYPESAFE_DEFAULT_MODEL 모델이름` 뒤 서버를 다시 켠다

확인한 결과 (에뮬레이터 v2.1.0, 실제 Jev, 기본 메시지 함 6개 기준 · 2026-11-14): `tools/voice_ai_test.py` 의 문장 23개가 기대대로 나왔다. 함에 넣지 않은 말("앞차 주유구 열렸다고 알려줘"), 욕설이 든 말, 욕설 없는 비난·위협, 관계없는 말("오늘 저녁에 뭐 먹을까?")은 모두 전송 없이 '띵~'으로 끝났다. 실제 마이크로 말하기와 '띵~' 소리가 스피커로 들리는지는 아직 확인하지 못했다 (이 PC의 에뮬레이터는 마이크를 못 잡는다).

### 마이크 없이 시험하기 (디버그 빌드 전용)
STT SDK의 "Sending audio data"(`sendAudio` → `completeAudioSend`, 16kHz·16bit·mono PCM)로 음성 파일을 넣어 본다. 마이크만 빼고 실제와 같은 길을 탄다.

```
adb -s emulator-5556 shell am broadcast --user 10 -a com.roadtalk.app.STT_TEST --es stt_test rear_thankyou
adb -s emulator-5556 logcat -s RoadTalkVoice RoadTalkHttp
```

- 음성 파일(`app/src/debug/assets/stt_test/`): `rear_thankyou`(뒷차한테 땡큐 보내줘) · `rear_sorry`(뒷차한테 미안하다고 보내줘) · `front_yield_thanks`(앞차한테 양보해줘서 고맙다고 전해줘) · `front_thanks` · `rear_go_first` · `sorry` · `rude_front`(욕설이 든 말) · `unrelated`(오늘 저녁에 뭐 먹을까)
- 글자를 바로 넣어 보기(STT 건너뜀): `... -a com.roadtalk.app.STT_TEST --es say "뒷차한테 고맙다고 해줘"`
- 로그 읽는 법: `interpret "…" -> target=REAR message=0 blocked=false score=0.91 (jev-…)` 는 Jev가 고른 것, `interpret unavailable` 은 단어 매칭으로 처리된 것, `ding` 은 보내지 않고 소리만 낸 것

---

## 위치 설정
- 앱은 **Pleos FusedLocation SDK**로 위치를 받는다. 실차에서는 차량 GPS(+센서 보정) 위치가 그대로 들어온다
- **에뮬레이터 기본 위치는 미국 Google 본사 좌표** (위도 37.4220, 경도 -122.0840), 방향 0°(북쪽)
- 위치 바꾸기
  - 에뮬레이터 옆 **⋯ (Extended controls) → Location** 에서 지도 클릭 → Set location
  - 또는 명령어 (경도 먼저, 위도 나중): `adb -s emulator-5554 emu geo fix 127.0276 37.4979`
- 위치는 SDK가 주는 대로 바로 서버에 올린다. 서버 모드는 최소 0.2초 간격, Firebase는 최소 2초 간격. 위치 변화가 없으면 5초마다 한 번
- 서버 모드에서는 근처에 차가 있는 동안 0.3초마다(없으면 1초마다) 근처 차 목록을 다시 묻는다
- 위치가 15초 넘게 안 들어오면 FusedLocation 등록을 다시 한다
- 근처 판정 기준 (`net/FirestoreRoadTalkRepository.kt`, `net/HttpRoadTalkRepository.kt`)
  - `radiusMeters = 300` : 반경 300m 안의 차만 받는다 (서버 모드는 지도에 보이는 범위만큼, 최대 3km)
  - `presenceTtlMillis = 30초` : 30초 넘게 위치 갱신이 없는 차는 제외
  - `messageTtlMillis = 60초` : 60초 지난 메시지는 무시
- 위치는 1~2초마다 오지만 화면은 **0.1초마다(약 10Hz)** 갱신한다. 차마다 칼만 필터(`net/CarTracker.kt`)가 위치·속도를 추정하고, 마지막 측정 뒤 지난 시간만큼 이어 그려서 두 차를 같은 '시점'으로 맞춘다
  - 움직임 확인용 로그: `adb shell setprop log.tag.RoadTalkTrack VERBOSE` 후 `adb logcat -s RoadTalkTrack`
- Pleos 에뮬레이터는 위치를 옮겨도 진행 방향(heading)을 항상 0으로 준다. 그래서 앱은 SDK 값이 0이면 이동 방향으로 계산한다 (`location/LocationGateway.kt`). 실차에서는 SDK 값을 그대로 쓴다

---

## 별도 서버 모드 (클라이언트/서버 분리)
- 서버 실행 (Python 3.10+): `python server/roadtalk_server.py 8080` → 이 PC의 모든 네트워크에서 8080 포트로 받음 (방화벽 허용 필요)
- 맨 위의 앱 이름을 길게 눌러 뜨는 설정 창에서 **"서버 주소 바꾸기"** 를 눌러 서버 주소 입력 (예: `192.168.0.10:8080`) → 즉시 그 서버로 전환. 비워서 저장하면 Firebase로 복귀
- 우선순위: 서버 주소 > Firebase(`google-services.json`) > 시뮬레이션
- 에뮬레이터에서 **같은 PC**의 서버에 붙을 때는 `10.0.2.2:<포트>`, 다른 PC면 그 PC의 내부망 IP
- 음성 해석(Jev)과 내 차 고르기 공유는 서버 모드에서 동작한다
- 통신 규격: [docs/SERVER_API.md](docs/SERVER_API.md) · 규격 테스트: `python tools/contract_test.py http://<서버>:8080`

## Firebase (관리자용)
팀원은 할 일 없음. 프로젝트를 새로 만들 때만 필요.

1. https://console.firebase.google.com → 프로젝트 만들기
2. Android 앱 추가 → 패키지 이름 `com.roadtalk.app` → `google-services.json`을 `app/`에 넣기
3. 데이터베이스 및 스토리지 → Firestore → 데이터베이스 만들기 (Standard, ID `(default)`, 서울) 후 규칙 탭에 아래를 게시
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /presence/{plate} { allow read, write: if true; }
       match /inboxes/{plate}/messages/{messageId} { allow read, create: if true; }
     }
   }
   ```
   (시연용. 로그인 없이 열려 있으니 **GitHub 저장소는 비공개로** 두고, 공개 배포 전에는 Firebase 로그인 기반 규칙으로 강화)

Firestore 구조: `presence/{번호}` (위치·geohash), `inboxes/{번호}/messages` (받은 메시지)

`app/google-services.json`이 없으면 앱은 시뮬레이션 서버(가짜 근처 차량 4대)로 동작한다.

---

## 주행 시나리오 시연 영상 만들기
"실제 도로에서 차들이 이렇게 움직일 때, 각 차의 Car-ind CHAT 화면에는 이렇게 보이고 이렇게 메시지를 보낸다"를 한 화면에 담는 도구 (`tools/scenario/run_scenario.py`).

- 왼쪽: 위에서 본 실제 상황 (앱 없는 차·공사 구간도 보임) / 오른쪽: 두 에뮬레이터의 **실제 앱 화면 녹화**
- 앱 화면은 꾸민 것이 아니다. 스크립트가 시나리오의 차 위치를 두 에뮬레이터의 GPS에 초당 10번 넣어 주고, 앱은 평소처럼 FusedLocation SDK → 서버 → 상대 위치로 그린다. 메시지는 앱의 음성 명령 경로로 보낸다 (마이크 인식 단계만 건너뛰고 "이렇게 말했다"는 글자를 넣어 준다)
- 준비: 서버 실행, 에뮬레이터 2대에 Car-ind CHAT 실행(번호 `99허 0000` / `12가 3690`, 서버 주소 `10.0.2.2:8090`), `pip install pillow`, ffmpeg

```
python tools/scenario/run_scenario.py merge     # 급한 끼어들기 → 급제동으로 양보 → 인사 (테헤란로)
python tools/scenario/run_scenario.py yield     # 좁은 골목에서 마주침 → 양보 (테헤란로8길)
python tools/scenario/run_scenario.py merge --preview    # 에뮬레이터 없이 왼쪽 그림만 미리 보기
python tools/scenario/run_scenario.py merge --live --loop   # 영상으로 만들지 않고 지금 돌아가는 모습을 창으로 보기
```

- **실시간으로 보기** (`--live`, 또는 `live_demo.bat` 더블클릭): 실제 도로 상황 창과 두 에뮬레이터의 앱 화면 창이 뜬다. 서버가 꺼져 있으면 알아서 켠다
  - `--loop` 끝나면 처음부터 반복 · `--hands` 메시지를 자동으로 보내지 않음(에뮬레이터에서 직접 보내 보기) · `--no-mirror` 앱 화면 창을 띄우지 않음
- 결과는 `docs/demo/scenario_<이름>.mp4`. 도로 좌표는 OpenStreetMap(© OpenStreetMap contributors)에서 가져왔다

---

## 3D 에셋
3D 화면의 모델은 아래 무료 에셋을 앱이 읽는 형식으로 바꾼 것이고, 바꾼 파일이 `app/src/main/assets/scene3d/` 에 들어 있다. 따로 받을 것 없이 빌드하면 된다.

| 쓰는 것 | 출처 (만든 사람) | 조건 |
|---|---|---|
| 차 10종 + 색 팔레트 6종 | [Low Poly Cars](https://brokenvector.itch.io/low-poly-cars) — Broken Vector | 무료 배포. 페이지에 라이선스 문구가 따로 없다 |
| 도로·나무·가로등·신호등 | [Low Poly City & Traffic Pack: FREE Lite](https://ggiedrius.itch.io/lowpoly-city-traffic-lite) — GGiedrius | 개인·상업 프로젝트에 사용 가능. 모델을 에셋으로 되팔거나 재배포하지 말 것 |
| 건물 윤곽·높이, 도로(차로 수), 횡단보도, 신호등 위치 (`assets/buildings_demo.json`) | OpenStreetMap | ODbL — "© OpenStreetMap contributors" 표기 |

모델의 권리는 각 제작자에게 있다. 다른 곳에 쓰려면 위 페이지에서 원본을 받아 조건을 확인할 것.

다시 만들려면:
1. 위 페이지들에서 `Models.zip`, `Textures.zip` (차), `LowPoly_City_Traffic_Lite_FREE.zip` (도로) 을 받는다
2. 한 폴더 안에 `cars/` (`cars/Models/*.fbx`, `cars/Textures/*.png`) 와 `road/` (`road/GLB/*.glb`, `road/Textures/T_Palette.png`) 로 풀어 둔다
3. `python tools/assets/convert_assets.py <그 폴더>` → `app/src/main/assets/scene3d/` 를 다시 만든다. `--preview 그림.png` 를 붙이면 결과를 그림으로 볼 수 있다

---

## 코드 구조
패키지 이름(`com.roadtalk.app`)과 폴더 이름(`RoadTalk`)은 처음 이름인 RoadTalk에서 왔다.

| 파일 | 역할 |
|---|---|
| `RoadTalkViewModel.kt` | 전체 동작 흐름 (여기서부터 읽기) |
| `ui/RoadTalkScreen.kt` | Compose UI |
| `ui/BirdEyeView.kt` | 왼쪽 패널: 3D 도로 위에 말풍선 얹기, 차 선택, 말하기 버튼 |
| `ui/scene3d/Scene3DView.kt` | 3D 도로·차 그리기 (OpenGL ES 2.0) |
| `ui/scene3d/Camera3D.kt` | 내 차 뒤 위에서 보는 카메라 |
| `ui/scene3d/CarLook.kt` | 차 모양·색 정하기 |
| `ui/scene3d/StripBuilder.kt` | 지도 도로를 바닥 띠로 (교차로에서 인도·차선 끊기) |
| `ui/Neon.kt` | 네온 꾸밈 (테두리, 글자, 동그란 버튼) |
| `ui/MessagesPanel.kt`, `chat/MessageCatalog.kt` | 메시지 함 화면, 보낼 수 있는 말 23개·기본 6개·단어 매칭 |
| `ui/GaragePanel.kt`, `ui/scene3d/GarageView.kt` | 내 차 고르기 (쇼룸 화면) |
| `ui/NavPanel.kt` | 오른쪽 지도 + 순정 내비 정보 카드 |
| `map/BuildingsSource.kt` | 내 위치 둘레의 지도 자료(건물·도로·횡단보도) 받기 |
| `map/OsmBuildings.kt`, `map/OsmRoads.kt`, `map/CityMesh.kt` | 지도 자료 → 건물 윤곽·도로·횡단보도, 건물을 3D로 세우기 |
| `net/Perception.kt` | 주변 차로 볼 범위 |
| `net/CarTracker.kt` | 칼만 필터로 내 차·주변 차 위치 추적, 0.1초마다 상대 위치 계산 |
| `navi/NaviGateway.kt` | NaviHelper SDK: 순정 내비 정보, 경로 안내 요청·취소 |
| `vehicle/VehicleGateway.kt` | Vehicle SDK: 차량번호(CRN), 기어(P 여부), 방향지시등 |
| `vehicle/PlateStore.kt` | 사용자가 등록한 내 차량번호·차종·차 색 저장 |
| `location/LocationGateway.kt` | FusedLocation SDK: 내 위치 |
| `voice/VoiceGateway.kt` | SpeechToText / TextToSpeech SDK (ON_DEVICE 모드) |
| `voice/Endpointer.kt` | 말을 멈췄는지 판단 (한 번 누르고 말하기) |
| `chat/ProfanityFilter.kt` | 욕설 필터 (우회 문자 정규화 + 차단) |
| `chat/VoiceCommandParser.kt` | "앞차에게 감사합니다" → 대상 + 내용 분리 |
| `net/RoadTalkRepository.kt` | 서버 인터페이스 |
| `net/HttpRoadTalkRepository.kt` | 별도 서버 구현 (음성 해석 포함) |
| `net/FirestoreRoadTalkRepository.kt` | Firebase 구현 |
| `net/SimulatedRoadTalkRepository.kt` | 시연용 가짜 서버 |
| `server/roadtalk_server.py`, `server/voice_ai.py` | 별도 서버, Jev 음성 해석 |

## 다음 단계
- Firestore 보안 규칙 + Firebase 익명 로그인
- 기어 상태를 Android Car API `GEAR_SELECTION`으로 교체 (Pleos Gear API deprecated)
- 위치 권한 요청 전 안내 화면
