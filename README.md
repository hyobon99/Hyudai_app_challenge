# Car-ind CHAT (카인드챗)

비상등 대신 말로: 근처 현대차끼리 짧은 감사·사과 메시지를 주고받는 Pleos Connect 앱 (프로토타입).
차량용 채팅 어플리케이션, 근처 현대 차끼리 '감사합니다', '먼저 가세요' 같은 짧은 의사소통을 할 수 있는 차량 간 소통 앱입니다.
---

## 처음 설치한 사람용 실행 가이드

### 0. 준비물
- **최신 Android Studio** (이 프로젝트는 AGP 9.4.1 / Gradle 9.6 / compileSdk 37 사용)
- **인터넷 연결** (첫 빌드 때 Gradle, Pleos SDK, Firebase 라이브러리를 자동으로 내려받음)
- PC RAM 16GB 이상 권장 (Pleos 에뮬레이터 1대가 RAM 4GB 사용)
- **Firebase는 따로 설치하거나 가입할 필요 없음.** 프로젝트에 `app/google-services.json`이 들어 있어서 팀 Firebase(`hyundai-roadtalk`)에 자동 연결됨

### 1. Pleos Connect 에뮬레이터 설치 (한 번만)
공식 가이드: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator

1. Android Studio → **Tools → SDK Manager → SDK Update Sites** → `+`
   - Name: `Pleos Connect System Image`
   - URL (Windows): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. **SDK Platforms** 탭 → Show Package Details → API 34의 **Pleos Connect system image** 설치
3. 같은 탭에서 **Android API 37** 플랫폼도 설치 (빌드에 필요. 없으면 Sync 때 설치하라고 뜸)
4. Android Studio 재시작
5. **Device Manager → Create device → Automotive → Pleos Connect v2.1.0**
   - Advanced Settings: Default boot **Cold**, RAM **4 GB**
   - System image: API 34 **Pleos Connect v2.1.0 APIs**

### 2. 프로젝트 열기
1. 프로젝트 폴더(`RoadTalk`)를 받는다 (GitHub clone 또는 압축 파일)
   - 압축으로 받았다면 `local.properties` 파일은 **삭제** (만든 사람 PC 경로가 들어 있음. Android Studio가 자동으로 다시 만듦)
2. Android Studio → **File → Open** → `RoadTalk` **폴더** 선택 → Trust Project
3. 오른쪽 아래 Gradle Sync가 끝날 때까지 대기 (첫 Sync는 몇 분 걸림)

### 3. 실행
1. Device Manager에서 Pleos Connect 에뮬레이터 ▶ 실행 (첫 부팅은 몇 분)
2. 상단 툴바 기기 선택 칸에서 그 에뮬레이터 선택 → **▶ Run**
3. 앱 첫 실행 때 "알 수 없는 작업 실행 허용?" 창 → **허용** (위치 권한. Pleos 권한이라 한글 이름이 없어서 이렇게 표시됨)
4. 상단 **"내 차 99허 0000 ✎"** 를 눌러 **각자 다른 차량번호** 등록 (예: `12가 3456`)
   - 번호가 같으면 서로를 자기 자신으로 보고 목록에 안 뜸
5. 상단에 **"서버: Firebase"** 가 보이면 연결 성공

### 4. 두 대로 대화해 보기
- Device Manager에서 Pleos 에뮬레이터를 하나 더 만들어 두 개를 동시에 켠다 (RAM이 빠듯하면 크롬 탭 정리)
- 기기 선택 칸 → **Select Multiple Devices...** → 둘 다 체크 → Run
- 각 에뮬레이터에서 번호를 다르게 등록하면 서로 "근처 현대차"에 뜨고 메시지를 주고받을 수 있음
- 다른 PC의 팀원 에뮬레이터와도 대화 가능 (같은 Firebase를 쓰고, 위치가 300m 안이면)

### 자주 나는 문제
| 증상 | 해결 |
|---|---|
| ▶ Run 버튼이 회색 | **File → Sync Project with Gradle Files** 후 대기. 창이 좁으면 기기 선택 칸이 숨으니 창 넓히기 |
| `Modifications to AndroidManifest.xml require an app restart` | 빨간 **■ Stop** 누르고 ▶ Run (⚡ Apply Changes 쓰지 말기) |
| 상단에 "서버: 시뮬레이션" | `app/google-services.json`이 없음 → 파일 받아서 `app/`에 넣고 Sync |
| "Firebase 오류: PERMISSION_DENIED" | Firestore 규칙 확인 (아래 Firebase 섹션) |
| 히터·안전벨트 등 차량 기능이 반응 없음 | Virtual Console 사용: `adb forward tcp:8088 tcp:8088` → 브라우저 http://127.0.0.1:8088 |

---

## 위치 설정

- 앱은 **Pleos FusedLocation SDK**로 위치를 받는다. 실차에서는 차량 GPS(+센서 보정) 위치가 그대로 들어온다.
- **에뮬레이터 기본 위치는 미국 Google 본사 좌표** (위도 37.4220, 경도 -122.0840), 방향 0°(북쪽)
  - 에뮬레이터 여러 대가 모두 같은 위치라 서로 거리가 0m 근처로 보이고, 2m 안이면 "앞차"로 표시된다.
- 위치 바꾸기
  - 에뮬레이터 옆 **⋯ (Extended controls) → Location** 에서 지도 클릭 → Set location
  - 또는 명령어 (경도 먼저, 위도 나중): `adb -s emulator-5554 emu geo fix 127.0276 37.4979`
- 위치는 SDK가 주는 대로(에뮬레이터 기준 1~2초마다) 바로 서버에 올린다. 서버 모드는 새 위치가 오면 바로(최소 0.2초 간격), Firebase는 최소 2초 간격. 위치 변화가 없으면 5초마다 한 번
- 상대 차의 새 위치를 빨리 받으려고, 서버 모드에서는 근처에 차가 있는 동안 0.3초마다(없으면 1초마다) 근처 차 목록을 다시 묻는다. 늦게 받을수록 화면의 차가 실제보다 늦게 움직인다
- 위치가 15초 넘게 안 들어오면(차량 위치 서비스가 다시 시작된 경우 등) FusedLocation 등록을 다시 한다
- 근처 판정 기준 (`net/FirestoreRoadTalkRepository.kt`, `net/HttpRoadTalkRepository.kt`)
  - `radiusMeters = 300` : 반경 300m 안의 차만 표시
  - `presenceTtlMillis = 30초` : 30초 넘게 위치 갱신이 없는 차는 제외
  - `messageTtlMillis = 60초` : 60초 지난 메시지는 무시
- 8방향(앞/뒤/좌/우/대각선)은 내 진행 방향(heading) 기준 상대 위치 벡터로 나눈다 (`net/Geo.kt`)
  - 위치는 1~2초마다 드문드문 오지만 화면은 **0.1초마다(약 10Hz)** 갱신한다. 차마다 칼만 필터(`net/CarTracker.kt`)가 위치·속도를 추정하고, 마지막 측정 뒤 지난 시간만큼 이어 그려서 두 차를 같은 '지금'으로 맞춘다. 새 위치가 와서 생기는 보정은 한 번에 튀지 않게 0.3초에 걸쳐 반영
  - 움직임 확인용 로그: `adb shell setprop log.tag.RoadTalkTrack VERBOSE` 후 `adb logcat -s RoadTalkTrack` (0.1초마다 차별 앞뒤·좌우 m)
  - 측정(급한 끼어들기 시나리오, 에뮬레이터): 갱신 9.8Hz. 실제 위치와의 차이는 일정하게 달릴 때 평균 0.1~0.2m(최대 0.6m), 급제동·급차로변경 구간은 평균 1.3~1.6m(최대 3.4m)
  - 급하게 움직이는 순간에 어긋나는 이유: 위치가 1~2초에 한 번만 오고 그 사이는 '직전 속도 그대로'라고 보고 이어 그리기 때문. 그래서 급제동은 0.5~0.8초 늦게 보이고, 그 직후 2m쯤 더 갔다가 돌아온다
  - 화면 배율은 차가 조금 움직일 때마다 바뀌면 전체가 출렁여 보여서, 앞 12→18→24→30m · 뒤 7→12→17→22m 단계로만 바뀐다
  - 일반 GPS 오차(수~10m) 때문에 차선 단위 정확도는 아님. 같은 차선/옆 차선 구분이 가장 약하다

---

## 별도 서버 모드 (클라이언트/서버 분리)
- 서버 실행 (Python 3.10+, 추가 설치 없음): `python server/roadtalk_server.py 8080` → 이 PC의 모든 네트워크에서 8080 포트로 받음 (방화벽 허용 필요)
- 앱 상단 **"서버: … ✎"** 를 눌러 서버 주소 입력 (예: `192.168.0.10:8080`) → 즉시 그 서버로 전환. 비워서 저장하면 Firebase로 복귀
- 우선순위: 서버 주소 > Firebase(`google-services.json`) > 시뮬레이션
- 에뮬레이터에서 **같은 PC**의 서버에 붙을 때는 `10.0.2.2:<포트>`, 다른 PC면 그 PC의 내부망 IP
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

Firestore 구조: `presence/{번호}` (위치·geohash, 5초마다 갱신), `inboxes/{번호}/messages` (받은 메시지)

`app/google-services.json`이 없으면 앱은 시뮬레이션 서버(가짜 근처 차량 4대)로 동작한다.

---

## 화면 구성
- 왼쪽: 위에서 본 도로 (`ui/BirdEyeView.kt`). Car-ind CHAT을 켠 차만 GPS 상대 위치에 표시. 파란 테두리가 있는 차는 '지금 선택된 차'(탭하거나 메시지를 보낸 차, 처음에는 가장 가까운 차)로, 🎤 음성 메시지를 받는 차다. **차를 누른 채 끌면 메시지 7개가 정칠각형으로 뜨고, 원하는 쪽으로 끌었다 놓으면** 그 방향의 메시지가 그 차에게 전송된다 (`RADIAL_MESSAGES`에서 문구 변경)
- 메시지는 차 위에 큰 말풍선(글자 60sp)으로 뜨고, 받은 메시지는 **TextToSpeech SDK**로 읽어 준다 (예: "앞차가 보낸 메시지. 양보 감사해요", 그림 문자는 빼고 읽음 · `chat/SpeechText.kt`)
- 가운데: 내비게이션 (`ui/NavPanel.kt`). 지도는 OpenStreetMap 타일(osmdroid), 내 위치 중심·진행 방향이 위. 지도 데이터 © OpenStreetMap contributors
  - 그 위에 **NaviHelper SDK**(`navi/NaviGateway.kt`)로 받은 순정 내비 정보를 표시: 다음 회전, 목적지, 남은 거리·시간, 제한속도
  - 지도를 길게 누르면 그 위치로 순정 내비에 경로 안내를 요청, 안내 카드의 "안내 종료"로 취소

## 음성으로 보내기 (SpeechToText SDK)
- 🎤 버튼을 누르고 말하면 **Pleos SpeechToText SDK**가 글자로 바꾸고, 앱이 "누구에게 / 무슨 말"로 나눠 보낸다 (`voice/VoiceGateway.kt`, `chat/VoiceCommandParser.kt`)
  - 보낼 수 있는 말은 방향 메뉴와 같은 **정해진 7개**뿐이다 (감사합니다 · 양보 감사해요 · 먼저 가세요 · 조심히 가세요 · 죄송합니다 · 괜찮아요 · 안녕하세요) (`chat/PresetMessages.kt`). 자유롭게 말하면 그중 뜻이 가장 가까운 것으로 바뀌어 간다
  - "뒷차한테 땡큐 보내줘" → 뒷차에게 "감사합니다 🙏" · "뒷차한테 미안하다고 보내줘" → 뒷차에게 "죄송합니다 🙇" · "12가 3456한테 먼저 가라고 해줘" · 대상을 안 말하면 지금 선택된 차(파란 테두리)
  - 보낸 뒤에는 "뒷차에게 감사합니다 보냈어요"라고 읽어 준다. 맞는 말이 없으면 보내지 않고 알려 준다
- 공식 가이드(SpeechToText → Programming Guide) 순서 그대로다: 권한 `pleos.car.permission.STT_SERVICE` → `SpeechToText(context, Mode.ON_DEVICE)` → `initialize(listener)` → `addListener` · `setLocale(Locale.KOREA)` → `request()` / `stop()` → 끝낼 때 `removeListener` · `release()`
  - `ON_DEVICE` 모드라 앱 등록(My Project의 clientId/secret)이 필요 없다. 서버 인식을 쓰려면 `HYBRID` + `registerApp()`
  - 핸들의 음성 버튼은 Gleo 전용이라 앱에서 쓸 수 없다 → 앱 안의 🎤 버튼으로 시작
- **마이크 없는 PC에서 시험하기 (디버그 빌드 전용)**: SDK의 "Sending audio data"(`sendAudio` → `completeAudioSend`, 16kHz·16bit·mono PCM)로 음성 파일을 넣어 본다. 마이크만 빼고 실제와 같은 길을 탄다

```
adb -s emulator-5556 shell am broadcast --user 10 -a com.roadtalk.app.STT_TEST --es stt_test front_thanks
adb -s emulator-5556 logcat -s RoadTalkVoice
```

  - 음성 파일은 `app/src/debug/assets/stt_test/` 에 있다: `front_thanks`(앞차에게 감사합니다), `rear_go_first`(뒷차에게 먼저 가세요), `sorry`(죄송합니다), `rear_thankyou`, `rear_sorry`, `front_yield_thanks`. Windows 한국어 음성(Heami)으로 만든 것
  - 확인한 결과(에뮬레이터 v2.1.0): 문장이 그대로 인식됐고(뒷차는 "뒤차"로 적힘), "뒤차한테 땡큐 보내줘" → 뒷차에게 "감사합니다 🙏", "뒤차한테 미안하다고 보내줘" → 뒷차에게 "죄송합니다 🙇", "앞차한테 양보해줘서 고맙다고 전해줘" → 앞차에게 "양보 감사해요"가 전송됐다. 실제 마이크로 말하는 것은 마이크가 있는 PC나 차량에서 따로 확인해야 한다

## 자유로운 말 알아듣기 (Claude API, 선택 사항)
Gleo 가 하는 일(말을 알아듣고 앱 기능을 골라 부르기)을 Claude API 로 대신한다. 흐름: 🎤 → STT SDK(말 → 글자) → **우리 서버** → Claude(누구에게 · 7개 중 무엇을) → 앱이 전송.

- 서버 코드: `server/voice_ai.py`, `POST /v1/interpret` (규격은 `docs/SERVER_API.md`). 앱은 `net/HttpRoadTalkRepository.interpret()` → `chat/VoiceIntentResolver.kt`
- Claude 는 JSON 스키마(enum)로 답하게 해서 **정해진 7개 밖의 말은 나올 수 없다.** 뜻이 맞는 게 없으면 "없음"을 고르고, 앱은 보내지 않는다
- **API 키는 서버 PC 에만 둔다.** 앱(APK)이나 GitHub 에는 넣지 않는다
- 키가 없거나, 인터넷이 안 되거나, 6초 안에 답이 없으면 앱이 **단어 매칭**(`chat/PresetMatcher.kt`)으로 대신한다. 흔한 표현(감사·고마워·땡큐, 미안·죄송, 먼저 가, 조심히, 괜찮, 안녕, 양보)은 키 없이도 된다

켜는 방법 (서버를 돌리는 PC 에서 한 번만):

```
pip install anthropic
setx ANTHROPIC_API_KEY "여기에_발급받은_키"
```

1. 키는 https://console.anthropic.com → API Keys 에서 만든다 (결제 수단 등록 필요)
2. `setx` 뒤에 서버를 다시 켤 필요는 없다 (서버가 사용자 환경 변수를 직접 읽는다)
3. 확인: 브라우저에서 `http://127.0.0.1:8090/health` → `"ai": "ready"` 면 켜진 것 (`no_key` = 키 없음, `no_sdk` = `pip install anthropic` 필요)

모델과 비용:
- 기본 모델은 가장 빠르고 싼 **Claude Haiku 5.5** (`claude-haiku-5-5`, 입력 100만 토큰당 $0.10 · 출력 $0.50). 명령 한 번이 입력 약 700토큰 · 출력 약 20토큰이라 **한 번에 약 0.1원**, 1만 번에 약 1,100원 수준이다
- 다른 모델을 쓰려면 `setx CARIND_CLAUDE_MODEL claude-sonnet-5-5` (또는 `claude-opus-5-5`) 뒤 서버를 다시 켠다. 짧은 분류 작업이라 Haiku 로 충분하다
- 아직 확인하지 못한 것: 이 PC 에 키가 없어서 **실제 Claude 호출은 돌려 보지 못했다** (가짜 응답으로 결과 처리만 확인). 키를 넣은 뒤 아래 시험 명령으로 확인할 것

시험 (디버그 빌드, 마이크 없이): 음성 파일 `rear_thankyou`(뒷차한테 땡큐 보내줘) · `rear_sorry`(뒷차한테 미안하다고 보내줘) · `front_yield_thanks`(앞차한테 양보해줘서 고맙다고 전해줘)

```
adb -s emulator-5556 shell am broadcast --user 10 -a com.roadtalk.app.STT_TEST --es stt_test rear_thankyou
adb -s emulator-5556 logcat -s RoadTalkVoice RoadTalkHttp
```

`RoadTalkHttp: interpret "…" -> target=REAR message=0 (claude-haiku-5-5, …ms)` 가 찍히면 Claude 가 해석한 것이고, `interpret unavailable` 이면 단어 매칭으로 처리된 것이다.

## 주행 시나리오 시연 영상 만들기
"실제 도로에서 차들이 이렇게 움직일 때, 각 차의 Car-ind CHAT 화면에는 이렇게 보이고 이렇게 메시지를 보낸다"를 한 화면에 담는 도구 (`tools/scenario/run_scenario.py`).

- 왼쪽: 위에서 본 실제 상황 (스크립트가 그림. 앱 없는 차·공사 구간도 보임) / 오른쪽: 두 에뮬레이터의 **실제 앱 화면 녹화**
- 앱 화면은 꾸민 것이 아니다. 스크립트가 시나리오의 차 위치를 두 에뮬레이터의 GPS에 초당 10번 넣어 주고(`geo fix`. 더 촘촘히 넣으면 에뮬레이터의 위치 서비스가 멈춘 적이 있다), 앱은 평소처럼 FusedLocation SDK → 서버 → 상대 위치 벡터로 그린다. 메시지도 에뮬레이터 화면에서 실제로 "차를 누른 채 끌기"를 해서 보낸다
- 준비: 서버 실행, 에뮬레이터 2대에 Car-ind CHAT 실행(번호 `99허 0000` / `12가 3690`, 서버 주소 `10.0.2.2:8090`), `pip install pillow`, ffmpeg

```
python tools/scenario/run_scenario.py merge     # 급한 끼어들기 → 급제동으로 양보 → 인사 (테헤란로)
python tools/scenario/run_scenario.py yield     # 좁은 골목에서 마주침 → 양보 (테헤란로8길)
python tools/scenario/run_scenario.py merge --preview    # 에뮬레이터 없이 왼쪽 그림만 미리 보기
python tools/scenario/run_scenario.py merge --live --loop   # 영상으로 만들지 않고 지금 돌아가는 모습을 창으로 보기
```

**실시간으로 보기 (`--live`, 또는 `live_demo.bat` 더블클릭)**: 창 3개가 뜬다. 왼쪽 = 실제 도로 상황(지금 에뮬레이터 GPS에 넣고 있는 위치 그대로), 나머지 2개 = 두 에뮬레이터의 앱 화면을 그대로 받아 보여 주는 창(보기 전용, 0.5초쯤 늦게 보임). 서버가 꺼져 있으면 알아서 켠다. `--loop` 는 끝나면 처음부터 반복, `--hands` 는 메시지를 자동으로 보내지 않음(에뮬레이터 화면에서 직접 차를 끌어 보내 보기), `--no-mirror` 는 앱 화면 창을 띄우지 않음(에뮬레이터 창이나 Android Studio의 Running Devices에서 직접 볼 때). 창을 닫으면 끝난다.

결과는 `docs/demo/scenario_<이름>.mp4`. 새 상황은 스크립트의 `scenario_merge()`를 참고해 차마다 속도·차로 변경 시각과 메시지 시각을 적으면 된다. 도로 좌표는 OpenStreetMap(© OpenStreetMap contributors)에서 가져왔다.

참고: Pleos 에뮬레이터는 위치를 옮겨도 진행 방향(heading)을 항상 0으로 준다. 그래서 앱은 SDK 값이 0이면 이동 방향으로 계산한다 (`location/LocationGateway.kt`). 실차에서는 SDK 값을 그대로 쓴다.

## 코드 구조
| 파일 | 역할 |
|---|---|
| `RoadTalkViewModel.kt` | 전체 동작 흐름 (여기서부터 읽기) |
| `ui/RoadTalkScreen.kt` | Compose UI |
| `vehicle/VehicleGateway.kt` | Vehicle SDK: 차량번호(CRN), 기어(P 여부), 방향지시등 |
| `vehicle/PlateStore.kt` | 사용자가 등록한 내 차량번호 저장 |
| `location/LocationGateway.kt` | FusedLocation SDK: 내 위치 |
| `net/CarTracker.kt` | 칼만 필터로 내 차·주변 차 위치 추적, 0.1초마다 상대 위치(8방향) 계산 |
| `voice/VoiceGateway.kt` | SpeechToText / TextToSpeech SDK (ON_DEVICE 모드) |
| `chat/ProfanityFilter.kt` | 게임 채팅식 욕설 필터 (우회 문자 정규화 + 마스킹/차단) |
| `chat/VoiceCommandParser.kt` | "앞차에게 감사합니다" → 대상 + 내용 분리 |
| `net/RoadTalkRepository.kt` | 서버 인터페이스 |
| `net/FirestoreRoadTalkRepository.kt` | Firebase 구현 |
| `net/SimulatedRoadTalkRepository.kt` | 시연용 가짜 서버 |

## 다음 단계
- Firestore 보안 규칙 + Firebase 익명 로그인
- 기어 상태를 Android Car API `GEAR_SELECTION`으로 교체 (Pleos Gear API deprecated)
- 위치 권한 요청 전 안내 화면
