"""Car-ind CHAT 서버 (docs/SERVER_API.md 규격). Python 3.10+ 표준 라이브러리만 사용.
(음성 명령을 TypeSafe AI 의 Jev 모델로 해석하는 기능만 typesafe-sdk 라이브러리와 API 키가 필요하다 → voice_ai.py. 없으면 그 기능만 꺼진다)

실행:  python server/roadtalk_server.py [포트]      (기본 8080, 0.0.0.0 에 바인딩)
규격 검증:  python tools/contract_test.py http://127.0.0.1:8080

저장은 메모리 (재시작하면 비워짐). 메시지는 60초, 위치(presence)는 30초 지나면 버린다.
"""
import json
import math
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import voice_ai

PRESENCE_TTL_MS = 30_000
MESSAGE_TTL_MS = 60_000
MAX_TEXT_LEN = 120
BANNED = ["시발", "씨발", "ㅅㅂ", "ㅆㅂ", "병신", "ㅂㅅ", "개새", "새끼", "존나", "ㅈㄴ", "좆", "fuck", "shit"]

lock = threading.Lock()
presence: dict[str, dict] = {}   # plateKey -> 최근 위치
messages: list[dict] = []
# 메시지 번호는 서버를 켠 시각(ms)에서 시작한다. 0 부터 다시 세면, 서버를 재시작했을 때 이미 떠 있던 앱들이
# '내가 본 번호 이후'만 달라고 해서 새 메시지를 전부 놓친다 (실제로 겪은 문제)
last_id = int(time.time() * 1000)


def now_ms() -> int:
    return int(time.time() * 1000)


def key(plate: str) -> str:
    """차량번호 비교는 공백을 무시한다 ("12가 3456" == "12가3456")"""
    return "".join(plate.split())


def meaningful(c: str) -> bool:
    """욕설 판정에 의미 있는 글자: 한글, 영문. 공백·숫자·기호는 우회용이라 무시"""
    return "가" <= c <= "힣" or "ㄱ" <= c <= "ㅣ" or (c.isascii() and c.isalpha())


def filter_text(text: str) -> tuple[str, bool]:
    """금칙어를 * 로 가린 문장과, 의미 있는 글자의 절반 이상이 가려졌는지(차단) 반환"""
    idx = [i for i, c in enumerate(text) if meaningful(c)]
    norm = "".join(text[i].lower() for i in idx)
    hit: set[int] = set()
    for word in BANNED:
        start = norm.find(word)
        while start >= 0:
            hit.update(idx[start:start + len(word)])
            start = norm.find(word, start + 1)
    masked = "".join("*" if i in hit else c for i, c in enumerate(text))
    blocked = bool(hit) and len(hit) >= max(1, len(norm)) / 2
    return masked, blocked


def haversine_m(lat1, lng1, lat2, lng2) -> float:
    r = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def num_or_none(v):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"   # 연결 유지(keep-alive): 앱이 1~2초마다 요청하므로 매번 새로 연결하지 않게

    def send_json(self, code: int, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            data = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return None
        return data if isinstance(data, dict) else None

    def log_message(self, fmt, *args):  # 한 줄 로그
        sys.stdout.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))
        sys.stdout.flush()

    # ---- GET ----
    def do_GET(self):
        url = urlparse(self.path)
        qs = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/health":
            # ai: 음성 명령 해석(Jev)을 쓸 수 있는지. ready / no_key / no_sdk
            return self.send_json(200, {"ok": True, "ai": voice_ai.status(), "aiModel": voice_ai.MODEL})
        if url.path == "/v1/nearby":
            return self.nearby(qs)
        if url.path == "/v1/messages":
            return self.inbox(qs)
        self.send_json(404, {"error": "not_found"})

    def nearby(self, qs):
        me = key(qs.get("plate", ""))
        try:
            lat = float(qs["lat"]) if "lat" in qs else None
            lng = float(qs["lng"]) if "lng" in qs else None
            radius = float(qs.get("radius", 300))
        except ValueError:
            return self.send_json(400, {"error": "invalid"})
        t = now_ms()
        cars = []
        with lock:
            for k, p in presence.items():
                if k == me or t - p["updatedAt"] > PRESENCE_TTL_MS:
                    continue
                # 요청과 상대 모두 좌표가 있을 때만 거리 필터
                if lat is not None and lng is not None and p["lat"] is not None and p["lng"] is not None:
                    if haversine_m(lat, lng, p["lat"], p["lng"]) > radius:
                        continue
                cars.append(p)
        # now: 앱이 (now - updatedAt) + fixAge 로 상대 위치의 나이를 계산한다
        self.send_json(200, {"now": t, "cars": cars})

    def inbox(self, qs):
        if "to" not in qs:
            return self.send_json(400, {"error": "invalid"})
        to = key(qs["to"])
        try:
            after = int(qs.get("after", 0))
        except ValueError:
            return self.send_json(400, {"error": "invalid"})
        t = now_ms()
        with lock:
            found = [m for m in messages if key(m["toPlate"]) == to and m["id"] > after and t - m["createdAt"] <= MESSAGE_TTL_MS]
            self.send_json(200, {"messages": found, "cursor": last_id})

    # ---- PUT ----
    def do_PUT(self):
        if urlparse(self.path).path != "/v1/presence":
            return self.send_json(404, {"error": "not_found"})
        body = self.read_json()
        if body is None or not isinstance(body.get("plate"), str) or not body["plate"].strip():
            return self.send_json(400, {"error": "invalid"})
        record = {
            "plate": body["plate"],
            "lat": num_or_none(body.get("lat")),
            "lng": num_or_none(body.get("lng")),
            "heading": num_or_none(body.get("heading")),
            "speed": num_or_none(body.get("speed")),          # m/s, 앱이 직전 위치와 비교해 계산
            "fixAge": None if body.get("fixAge") is None else int(body["fixAge"]),  # 측정 뒤 보낼 때까지 지난 ms
            "fixedAt": None if body.get("fixedAt") is None else int(body["fixedAt"]),  # 측정 시각 (서버 시계 ms, 앱이 시계 차이를 재서 환산)
            "accuracy": num_or_none(body.get("accuracy")),    # 오차 반경 m
            "skin": body["skin"] if isinstance(body.get("skin"), int) and 0 <= body["skin"] <= 5 else None,  # 운전자가 고른 차 색
            "updatedAt": now_ms(),
        }
        with lock:
            presence[key(body["plate"])] = record
        # now: 앱이 서버와의 시계 차이를 재는 데 쓴다
        self.send_json(200, {"ok": True, "now": record["updatedAt"]})

    # ---- POST ----
    def interpret(self):
        """운전자가 한 말 → (누구에게, 정해진 메시지 중 일치도가 가장 높은 것). 욕설·위험한 말이면 blocked.
        Jev 를 쓸 수 없으면 503 → 앱이 단어 매칭으로 대신한다"""
        body = self.read_json() or {}
        text = body.get("text")
        choices = body.get("messages")
        if (not isinstance(text, str) or not text.strip() or not isinstance(choices, list) or not choices
                or len(choices) > 20 or not all(isinstance(m, str) and m.strip() for m in choices)):
            return self.send_json(400, {"error": "invalid"})
        state = voice_ai.status()
        if state != "ready":
            return self.send_json(503, {"error": "ai_unavailable", "reason": state})
        cars = [c for c in (body.get("cars") or []) if isinstance(c, str)]
        try:
            result = voice_ai.interpret(text.strip(), choices, cars, body.get("selected"))
        except RuntimeError as e:
            return self.send_json(503, {"error": "ai_unavailable", "reason": str(e)})
        index = result["messageIndex"]
        self.send_json(200, {
            "ok": True,
            "blocked": result["blocked"],                                 # 욕설·위험한 말 → 앱은 보내지 않고 '띵' 소리만 낸다
            "target": result["target"],                                   # 방향, 대상을 말하지 않았으면 null
            "messageIndex": index,                                        # messages 의 번호, 일치도가 높은 말이 없으면 null
            "message": choices[index] if index is not None else None,
            "score": result["score"],                                     # 고른 문구의 일치도 (0~1)
            "model": voice_ai.MODEL,
            "ms": result["ms"],
        })

    def do_POST(self):
        global last_id
        if urlparse(self.path).path == "/v1/interpret":
            return self.interpret()
        if urlparse(self.path).path != "/v1/messages":
            return self.send_json(404, {"error": "not_found"})
        body = self.read_json()
        if body is None:
            return self.send_json(400, {"error": "invalid"})
        text = (body.get("text") or "").strip() if isinstance(body.get("text"), str) else ""
        src, dst = body.get("fromPlate"), body.get("toPlate")
        if not text or len(text) > MAX_TEXT_LEN or not isinstance(src, str) or not isinstance(dst, str) or not src or not dst:
            return self.send_json(400, {"error": "invalid"})
        masked, blocked = filter_text(text)
        if blocked:
            return self.send_json(422, {"error": "blocked"})
        with lock:
            last_id += 1
            msg = {"id": last_id, "fromPlate": src, "toPlate": dst, "text": masked, "createdAt": now_ms()}
            messages.append(msg)
            # 오래된 메시지는 주기적으로 정리
            cutoff = msg["createdAt"] - MESSAGE_TTL_MS
            messages[:] = [m for m in messages if m["createdAt"] >= cutoff]
        self.send_json(201, {"id": msg["id"], "createdAt": msg["createdAt"], "text": masked})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"Car-ind CHAT server on 0.0.0.0:{port} · 음성 명령 AI: {voice_ai.status()} ({voice_ai.MODEL})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
