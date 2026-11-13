"""RoadTalk 서버 규격 테스트 (docs/SERVER_API.md).

사용법:  python tools/contract_test.py http://<서버IP>:8080
표준 라이브러리만 사용. 테스트용 차량번호는 매번 무작위라 실서버 데이터와 섞이지 않는다.
"""
import json
import random
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
n = random.randint(1000, 9999)
A, B, C = f"81테 {n}", f"82테 {n}", f"83테 {n}"
LAT, LNG = 37.4979, 127.0276            # 강남역
NEAR = (LAT + 0.0001, LNG)              # 약 11m 북쪽
FAR = (LAT + 0.05, LNG)                 # 약 5.5km 북쪽

passed = failed = 0


def call(method, path, body=None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode(errors="replace")}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def q(s):
    return urllib.parse.quote(s)


print(f"RoadTalk contract test → {BASE}")

s, b = call("GET", "/health")
check("GET /health", s == 200 and b.get("ok") is True, (s, b))

for plate, (lat, lng) in [(A, (LAT, LNG)), (B, NEAR), (C, FAR)]:
    s, b = call("PUT", "/v1/presence", {"plate": plate, "lat": lat, "lng": lng, "heading": 0.0})
    check(f"PUT /v1/presence {plate}", s == 200, (s, b))
s, b = call("PUT", "/v1/presence", {"plate": f"84테 {n}", "lat": None, "lng": None, "heading": None})
check("PUT /v1/presence 좌표 없음 허용", s == 200, (s, b))

s, b = call("GET", f"/v1/nearby?plate={q(A)}&lat={LAT}&lng={LNG}&radius=300")
plates = [c.get("plate") for c in b.get("cars", [])] if s == 200 else []
check("nearby: 300m 안의 차 포함", B in plates, plates)
check("nearby: 자기 자신 제외", A not in plates, plates)
check("nearby: 300m 밖 제외", C not in plates, plates)
check("nearby: 좌표 없는 차는 거리 필터 없이 포함", f"84테 {n}" in plates, plates)
check("nearby: 원래 표기(공백 포함) 유지", B in plates, plates)

s, b = call("GET", f"/v1/nearby?plate={q(A)}")
plates = [c.get("plate") for c in b.get("cars", [])] if s == 200 else []
check("nearby: 요청에 좌표 없으면 최근 차 전부", B in plates and C in plates and A not in plates, plates)

s, b = call("PUT", "/v1/presence", {"plate": A, "lat": LAT, "lng": LNG, "heading": 90.0, "speed": 16.7, "fixAge": 800, "fixedAt": 1794384458380, "accuracy": 5.0})
check("PUT /v1/presence speed/fixAge/fixedAt/accuracy 허용", s == 200, (s, b))
check("PUT /v1/presence 응답에 서버 시각 now", isinstance(b.get("now"), int), b)
s, b = call("GET", f"/v1/nearby?plate={q(B)}&lat={LAT}&lng={LNG}&radius=300")
me = next((c for c in b.get("cars", []) if c.get("plate") == A), {}) if s == 200 else {}
check("nearby: speed/fixAge/fixedAt/accuracy 그대로 전달", me.get("speed") == 16.7 and me.get("fixAge") == 800 and me.get("fixedAt") == 1794384458380 and me.get("accuracy") == 5.0, me)
check("nearby: 서버 현재 시각 now 포함", isinstance(b.get("now"), int) and abs(b["now"] - me.get("updatedAt", 0)) < 10_000, b.get("now"))

s, b = call("GET", f"/v1/messages?to={q(B)}&after=0")
start_cursor = b.get("cursor", 0) if s == 200 else 0
check("GET /v1/messages 빈 함 + cursor", s == 200 and isinstance(b.get("messages"), list) and "cursor" in b, (s, b))

s, sent = call("POST", "/v1/messages", {"fromPlate": A, "toPlate": B, "text": "양보 감사합니다"})
check("POST /v1/messages → 201", s == 201 and isinstance(sent.get("id"), int) and "createdAt" in sent, (s, sent))
check("POST: 깨끗한 문장은 그대로", sent.get("text") == "양보 감사합니다", sent)

s, b = call("GET", f"/v1/messages?to={q(B)}&after={start_cursor}")
msgs = b.get("messages", []) if s == 200 else []
check("받은 메시지에 방금 보낸 것 포함", any(m.get("id") == sent.get("id") and m.get("fromPlate") == A for m in msgs), msgs)
check("cursor ≥ 받은 id", s == 200 and b.get("cursor", -1) >= sent.get("id", 1 << 60), b)

s, b2 = call("GET", f"/v1/messages?to={q(B)}&after={b.get('cursor', 0)}")
check("after=cursor 이면 중복 없음", s == 200 and not any(m.get("id") == sent.get("id") for m in b2.get("messages", [])), b2)

s, b = call("GET", f"/v1/messages?to={q(B.replace(' ', ''))}&after={start_cursor}")
check(f"차량번호 공백 무시 (82테{n} == 82테 {n})", any(m.get("id") == sent.get("id") for m in b.get("messages", [])), b)

s, b = call("GET", f"/v1/messages?to={q(A)}&after={start_cursor}")
check("다른 차 메시지함에는 안 들어감", s == 200 and not any(m.get("id") == sent.get("id") for m in b.get("messages", [])), b)

s, b = call("POST", "/v1/messages", {"fromPlate": A, "toPlate": B, "text": "아 시1발 진짜 감사하네"})
check("서버 욕설 필터: 일부 가림", s == 201 and "*" in b.get("text", "") and "시1발" not in b.get("text", ""), (s, b))

s, b = call("POST", "/v1/messages", {"fromPlate": A, "toPlate": B, "text": "ㅅ ㅂ 병 신"})
check("서버 욕설 필터: 대부분 욕설이면 422", s == 422 and b.get("error") == "blocked", (s, b))

s, b = call("POST", "/v1/messages", {"fromPlate": A, "toPlate": B, "text": ""})
check("빈 메시지 → 400", s == 400, (s, b))
s, b = call("POST", "/v1/messages", {"fromPlate": A, "toPlate": B, "text": "가" * 121})
check("120자 초과 → 400", s == 400, (s, b))

print(f"\n결과: {passed} 통과 / {failed} 실패")
sys.exit(1 if failed else 0)
