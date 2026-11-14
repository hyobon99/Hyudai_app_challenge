# Car-ind CHAT 서버 API

앱(클라이언트)과 서버를 분리했을 때의 통신 규격. 앱의 `net/HttpRoadTalkRepository.kt`가 이 규격을 따른다.
서버 구현은 자유 (언어/저장소 무관). 규격 검증: `python tools/contract_test.py http://<서버>:8080`

## 공통
- HTTP/1.1, JSON (UTF-8), 기본 포트 8080
- 차량번호 비교는 공백을 제거해서 (`"12가 3456" == "12가3456"`), 응답에는 원래 표기 그대로
- 시각은 서버 시각 ms epoch
- 앱 주기: presence 갱신은 새 위치가 들어올 때마다 바로 (1~2초, 변화 없으면 5초마다). 근처 조회는 근처에 차가 있으면 0.3초마다, 없으면 1초마다 (최소 0.2초 간격). 메시지 조회 1.5초

## GET /health
`200 {"ok": true}`

## PUT /v1/presence
요청 `{"plate":"12가 3456","lat":37.42,"lng":-122.08,"heading":0.0,"speed":16.7,"fixAge":800,"accuracy":5.0,"skin":2,"car":5}`
- `lat`/`lng`/`heading`/`speed`/`fixAge`/`accuracy`는 null 가능 (생략해도 됨)
- `speed`: m/s. 앱이 직전 위치와 비교해 계산
- `fixAge`: 이 위치가 측정된 뒤 앱이 보낼 때까지 지난 ms. 절대 시각이 아니라서 기기 시계가 서로 달라도 됨
- `accuracy`: 오차 반경 m (FusedLocation confidence)
- `skin`: 운전자가 고른 차 색 (0 파랑, 1 빨강, 2 노랑, 3 초록, 4 보라, 5 은색). 0~5 의 정수가 아니거나 없으면 null 로 저장 → 받는 앱이 차량번호로 색을 정한다
- `car`: 운전자가 고른 차종 (0 미니, 1 세단, 2 해치백, 3 밴, 4 픽업, 5 스포츠카, 6 경찰차, 7 버스, 8 트럭, 9 탱크로리). 0~9 의 정수가 아니거나 없으면 null → 받는 앱이 차량번호로 차종을 정한다
- `fixedAt`: 위치 측정 시각을 **서버 시계**로 나타낸 ms. 앱이 응답의 `now` 로 서버와의 시계 차이를 재서(NTP 방식: 보낸 시각과 받은 시각의 가운데 = 서버 now) 환산한다. 있으면 받는 쪽은 `fixAge` 대신 이 값을 쓴다 → 통신 지연이 위치 보정에 섞이지 않음
→ `200 {"ok": true, "now": ms}` — 서버가 `updatedAt = 지금`으로 저장 (같은 번호면 덮어쓰기). 받은 필드는 그대로 보관. `now` 는 서버 현재 시각

## GET /v1/nearby?plate=&lat=&lng=&radius=300
→ `200 {"now":ms,"cars":[{"plate","lat","lng","heading","speed","fixAge","fixedAt","accuracy","skin","car","updatedAt"}]}`
- `now`: 서버 현재 시각 (`updatedAt`과 같은 시계). 앱은 상대 위치의 나이를 `(서버 시계로 본 지금) - fixedAt` (없으면 `(now - updatedAt) + fixAge`)로 계산한다. 이 나이로 '그 위치가 측정된 시각'을 알아내 칼만 필터(`net/CarTracker.kt`)에 넣고, 화면에는 0.1초마다 '지금' 위치를 추정해 그린다
- 요청자 자신 제외, `updatedAt`이 30초 이내인 차만
- `radius`: 앱은 지도 패널에 지금 보이는 범위(내 위치에서 지도 구석까지)를 보낸다 → 지도에 보이는 곳의 차가 빠지지 않는다. 최소 300m, 지도를 축소해도 최대 3000m (서버는 5000m 까지만 받는다)
- 요청과 상대 모두 좌표가 있으면 haversine 거리 `radius`(기본 300m) 이내만. 한쪽이라도 좌표가 없으면 거리 필터 없이 포함
- 거리·8방향(앞/뒤/좌/우/대각선)은 앱이 계산

## POST /v1/messages
요청 `{"fromPlate":"99허 0000","toPlate":"12가 3456","text":"감사합니다"}`
→ `201 {"id":17,"createdAt":ms,"text":"(필터 적용 후)"}`
- `id`: 서버 전체 1씩 증가 정수
- text가 비었거나 120자 초과 → `400 {"error":"invalid"}`
- 서버 2차 욕설 필터: 공백·숫자·기호를 무시하고 금칙어를 찾아 `*`로 가림. 의미 있는 글자의 절반 이상이 가려지면 저장하지 않고 `422 {"error":"blocked"}`

## GET /v1/messages?to=&after=0
→ `200 {"messages":[{"id","fromPlate","toPlate","text","createdAt"}],"cursor":17}`
- `to` 차량에게 온 메시지 중 `id > after` 이고 60초 이내인 것만, id 오름차순
- `id` 는 계속 커지는 수다. 서버를 재시작해도 이전보다 작아지지 않게 서버를 켠 시각(ms)에서 시작한다 (0 부터 다시 세면 떠 있던 앱들이 새 메시지를 놓친다. 앱도 `cursor` 가 자기가 본 값보다 작아지면 처음부터 다시 받는다)
- `cursor` = 서버가 지금까지 발급한 가장 큰 id. 앱은 다음 요청에 `after=cursor` → 기기 시계가 달라도 중복·누락 없음

## POST /v1/interpret — 음성 명령 해석 (선택 기능)
운전자가 한 말을 "누구에게 · 정해진 메시지 중 일치도가 가장 높은 것"으로 바꾸고, 욕설·위험한 말인지 판단한다.
서버가 TypeSafe AI의 Jev 모델을 부른다 (`server/voice_ai.py`). API 키는 서버에만 있고 앱은 모른다.

요청:
```json
{"text": "뒷차한테 땡큐 보내줘", "messages": ["감사합니다", "좋은 하루 보내세요", "…운전자의 기본 메시지 함 6개"], "cars": ["REAR", "FRONT"], "selected": "FRONT"}
```
- `messages`: 보낼 수 있는 말 전체 (앱이 정한 목록, 최대 20개). 이 중 하나만 고를 수 있다
- `cars`: 지금 근처에 있는 차의 방향들, `selected`: 화면에서 선택된 차의 방향 (없으면 null). 방향 값은 `FRONT` `REAR` `LEFT` `RIGHT` `FRONT_LEFT` `FRONT_RIGHT` `REAR_LEFT` `REAR_RIGHT`

응답 200:
```json
{"ok": true, "blocked": false, "target": "REAR", "messageIndex": 0, "message": "감사합니다 🙏", "score": 0.91, "model": "jev-latest", "ms": 180}
```
- `blocked`: 욕설·비속어·위협·위험한 말 → 앱은 보내지 않고 '띵' 소리만 낸다
- `target`: 운전자가 말한 방향. 대상을 말하지 않았으면 null (앱이 선택된 차에게 보낸다)
- `messageIndex`: `messages` 의 번호. 일치도가 높은 말이 없으면 null (앱은 보내지 않고 '띵' 소리를 낸다)
- `score`: 고른 문구의 일치도 (0~1, Jev 가 준 확률)

오류: 400 `invalid` (text·messages 누락) · 503 `ai_unavailable` (`reason`: `no_key` 키 없음, `no_sdk` 라이브러리 없음, 그 밖에는 Jev API 오류 설명). 503 이나 시간 초과(앱 10초)면 앱이 단어 매칭으로 대신한다.

`GET /health` 응답의 `ai` 로 이 기능이 켜져 있는지 볼 수 있다: `ready` / `no_key` / `no_sdk`.
