"""음성 명령 해석(서버의 Jev) 점검: 여러 가지 말을 /v1/interpret 에 넣어 기대한 결과가 나오는지 본다.

사용법 (서버가 켜져 있고 TYPESAFE_API_KEY 가 설정돼 있어야 함. 호출마다 소액의 API 비용이 든다):
    python tools/voice_ai_test.py [http://127.0.0.1:8090]

기대값: (받을 방향 또는 None, 메시지 또는 None, 무시해야 하는지)
"""
import json
import sys
import time
import urllib.error
import urllib.request

MESSAGES = ["감사합니다 🙏", "양보 감사해요", "먼저 가세요", "조심히 가세요", "죄송합니다 🙇", "괜찮아요 👍", "안녕하세요 👋"]
THANKS, YIELD_THANKS, GO, CARE, SORRY, OKAY, HELLO = MESSAGES
NEARBY = ["FRONT", "REAR", "LEFT"]

CASES = [
    # 사용자가 든 예시
    ("뒷차한테 땡큐 보내줘!", "REAR", THANKS, False),
    ("뒷차한테 미안하다고 보내줘", "REAR", SORRY, False),
    # 표현을 바꿔 말하기
    ("앞차한테 고맙다고 좀 전해줘", "FRONT", THANKS, False),
    ("방금 끼워줘서 고맙다고 해줘", None, YIELD_THANKS, False),
    ("앞차한테 양보해줘서 고맙다고 전해줘", "FRONT", YIELD_THANKS, False),
    ("뒤에 오는 차 먼저 보내드려", "REAR", GO, False),
    ("제가 양보할게요 먼저 지나가세요", None, GO, False),
    ("왼쪽 차한테 안전 운전하라고 해줘", "LEFT", CARE, False),
    ("앞차한테 쏘리라고 해", "FRONT", SORRY, False),
    ("아까 급하게 끼어들어서 미안해요", None, SORRY, False),
    ("괜찮다고 신경 쓰지 말라고 전해줘", None, OKAY, False),
    ("옆 차한테 인사 좀 해줘", "LEFT", HELLO, False),
    # 음성 인식이 틀리게 적은 경우
    ("뒤차한테 땡큐 보내줘", "REAR", THANKS, False),
    ("뒷 차 한테 죄송 하다고", "REAR", SORRY, False),
    # 보내면 안 되는 말 (욕설·위협·위험)
    ("야 이 새끼야 빨리 좀 가라", None, None, True),
    ("앞차 운전 진짜 못하네 면허 어디서 땄냐", None, None, True),
    ("한 번만 더 끼어들면 들이받는다고 해", None, None, True),
    ("뒷차한테 꺼지라고 해", None, None, True),
    # 인사와 상관없는 말 (맞는 문구 없음)
    ("오늘 저녁에 뭐 먹을까", None, None, False),
    ("가까운 주유소 찾아줘", None, None, False),
    ("음악 좀 틀어줘", None, None, False),
]


def ask(base, text, selected="FRONT"):
    body = json.dumps({"text": text, "messages": MESSAGES, "cars": NEARBY, "selected": selected}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + "/v1/interpret", data=body, method="POST",
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            res = json.loads(r.read())
    except urllib.error.HTTPError as e:
        res = {"error": "%s %s" % (e.code, e.read().decode("utf-8", "replace"))}
    res["wall_ms"] = int((time.time() - started) * 1000)
    return res


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8090").rstrip("/")
    passed, times = 0, []
    for text, want_target, want_message, want_blocked in CASES:
        res = ask(base, text)
        if "error" in res:
            print("  오류  %-34s %s" % (text, res["error"]))
            continue
        times.append(res["ms"])
        got = (res["target"], res["message"], res["blocked"])
        # 무시해야 하는 말은 blocked 만 맞으면 되고, 대상을 정하지 않은 경우(None)는 방향을 따지지 않는다
        ok = res["blocked"] == want_blocked and (want_blocked or (
            res["message"] == want_message and (want_target is None or want_message is None or res["target"] == want_target)))
        passed += ok
        print("  %s  %-34s → %-11s %-12s %s 일치도 %.2f  %dms" % (
            "통과" if ok else "실패", text, res["target"] or "-", (res["message"] or "(없음)"),
            "무시" if res["blocked"] else "    ", res.get("score") or 0, res["ms"]))
        if not ok:
            print("        기대: %s / %s / %s" % (want_target or "-", want_message or "(없음)", "무시" if want_blocked else "보냄 여부는 문구에 따름"))
    print("\n결과: %d / %d 통과" % (passed, len(CASES)))
    if times:
        times.sort()
        print("Jev 응답 시간: 중앙값 %dms · 가장 느린 것 %dms" % (times[len(times) // 2], times[-1]))


if __name__ == "__main__":
    main()
