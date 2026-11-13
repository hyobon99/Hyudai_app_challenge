"""운전자가 한 말을 '누구에게 / 정해진 메시지 중 어느 것'으로 바꾼다 (Claude API).

예: "뒷차한테 땡큐 보내줘"      → 뒷차(REAR), "감사합니다"
    "뒷차한테 미안하다고 보내줘" → 뒷차(REAR), "죄송합니다"

보낼 수 있는 말은 앱이 정해 둔 목록(7개)뿐이다. Claude 는 그중 하나를 고르거나, 맞는 것이 없으면 NONE 을 고른다.
구조화 출력(JSON 스키마의 enum)을 써서 목록 밖의 말은 나올 수 없다.

설정 (환경 변수):
    ANTHROPIC_API_KEY     필수. Anthropic Console 에서 발급. 이 키는 서버 PC 에만 둔다 (앱·GitHub 에 넣지 않는다)
    CARIND_CLAUDE_MODEL   선택. 기본 claude-haiku-5-5 (가장 빠르고 싼 모델). 예: claude-sonnet-5-5, claude-opus-5-5

필요: pip install anthropic
키나 라이브러리가 없으면 서버는 그대로 돌고, 이 기능만 꺼진다 (앱은 단순 단어 매칭으로 대신한다).
"""
import json
import os
import re
import sys
import threading
import time

MODEL = os.environ.get("CARIND_CLAUDE_MODEL", "claude-haiku-5-5")
TARGETS = ["FRONT", "REAR", "LEFT", "RIGHT", "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT"]
UNSPECIFIED = "UNSPECIFIED"
NONE = "NONE"
MAX_UTTERANCE = 200
_EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️‍]")

SYSTEM = """당신은 차량용 앱 'Car-ind CHAT'의 음성 명령 해석기입니다.
운전자는 주행 중에 주변 차에게 짧은 인사를 보내려고 말합니다. 화면을 보거나 다시 말하기 어려운 상황이라, 한 번에 뜻을 알아들어야 합니다.
입력은 음성 인식 결과라서 띄어쓰기·맞춤법이 틀리거나 말이 덜 끝났을 수 있습니다. 글자 그대로가 아니라 뜻으로 판단하세요.

입력(JSON):
- utterance: 운전자가 한 말
- cars: 지금 근처에 있는 차들의 방향 (내 차 기준)
- selected: 화면에서 지금 선택돼 있는 차의 방향 (없으면 null)

정할 것은 두 가지입니다.

1) target — 누구에게 보내는가 (내 차 기준 방향)
   FRONT 앞차 · REAR 뒷차 · LEFT 왼쪽 옆 차 · RIGHT 오른쪽 옆 차
   FRONT_LEFT 왼쪽 앞 · FRONT_RIGHT 오른쪽 앞 · REAR_LEFT 왼쪽 뒤 · REAR_RIGHT 오른쪽 뒤
   - 운전자가 대상을 말하지 않았으면 UNSPECIFIED (앱이 선택된 차에게 보냅니다).
   - 운전자가 방향을 말했으면, 그 방향에 차가 없더라도 말한 방향 그대로 고르세요 (차가 없다는 안내는 앱이 합니다).
   - "옆 차"처럼 좌우를 말하지 않았으면 cars 에 있는 쪽을 고르고, 그래도 정할 수 없으면 UNSPECIFIED.

2) message — 무슨 말을 보내는가
   보낼 수 있는 말은 사용자 메시지의 messages 목록에 있는 것뿐입니다. 운전자의 뜻과 가장 가까운 것 하나를 고르세요.
   - 표현이 달라도 뜻이 같으면 고릅니다. 반말, 외래어, 돌려 말하기 모두 해당됩니다.
   - 양보받은 것에 대한 고마움이면 "양보 감사해요", 그 밖의 고마움이면 "감사합니다"를 고릅니다.
   - 목록의 어느 것과도 뜻이 맞지 않으면 NONE 을 고르세요. 억지로 끼워 맞추지 않습니다.
     상대를 비난하거나 욕하는 말, 인사와 상관없는 말(길 안내, 음악, 질문 등)은 NONE 입니다.
"""

_lock = threading.Lock()
_client = None


def _user_env_key():
    """Windows 에서 `setx ANTHROPIC_API_KEY ...` 로 저장한 값은 이미 떠 있는 프로그램에는 안 보인다 → 사용자 환경 변수를 직접 읽는다"""
    if sys.platform != "win32":
        return None
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            value, _ = winreg.QueryValueEx(k, "ANTHROPIC_API_KEY")
            return value or None
    except OSError:
        return None


def status():
    """'ready' / 'no_sdk' (pip install anthropic 필요) / 'no_key' (ANTHROPIC_API_KEY 없음)"""
    try:
        import anthropic  # noqa: F401
    except ImportError:
        return "no_sdk"
    if not (os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")):
        key = _user_env_key()
        if not key:
            return "no_key"
        os.environ["ANTHROPIC_API_KEY"] = key
    return "ready"


def _get_client():
    global _client
    with _lock:
        if _client is None:
            import anthropic
            # 운전 중 명령이라 오래 기다리지 않는다. 늦으면 앱이 단순 매칭으로 대신한다
            _client = anthropic.Anthropic(timeout=6.0, max_retries=1)
        return _client


def _schema(choices):
    return {
        "type": "object",
        "properties": {
            "target": {"type": "string", "enum": TARGETS + [UNSPECIFIED]},
            "message": {"type": "string", "enum": choices + [NONE]},
        },
        "required": ["target", "message"],
        "additionalProperties": False,
    }


def clean(text):
    """그림 문자를 뺀 문구 (Claude 에게는 글자만 보여 준다)"""
    return _EMOJI.sub("", text).strip()


def interpret(utterance, messages, cars, selected, client=None):
    """utterance 를 해석한다.
    돌려주는 값: {"target": 방향 또는 None(대상을 말하지 않음), "messageIndex": messages 의 번호 또는 None(맞는 말 없음), "ms": 걸린 시간}
    Claude 를 쓸 수 없으면 RuntimeError(이유) 를 던진다."""
    import anthropic

    choices = [clean(m) for m in messages]
    cars = [c for c in cars if c in TARGETS]
    request = {
        "utterance": utterance[:MAX_UTTERANCE],
        "messages": choices,
        "cars": cars,
        "selected": selected if selected in TARGETS else None,
    }
    extra = {}
    if MODEL.startswith("claude-haiku"):
        # 짧은 분류라 생각 단계를 꺼서 가장 빠르게 (Haiku 5.5 는 effort 가 high 이하일 때 끌 수 있다)
        extra["thinking"] = {"type": "disabled"}
    started = time.time()
    try:
        response = (client or _get_client()).messages.create(
            model=MODEL,
            max_tokens=300,
            system=SYSTEM,
            messages=[{"role": "user", "content": json.dumps(request, ensure_ascii=False)}],
            output_config={"effort": "low", "format": {"type": "json_schema", "schema": _schema(choices)}},
            **extra,
        )
    except anthropic.AuthenticationError as e:
        raise RuntimeError("API 키가 올바르지 않아요") from e
    except anthropic.RateLimitError as e:
        raise RuntimeError("요청이 너무 많아요 (잠시 뒤 다시)") from e
    except anthropic.APIStatusError as e:
        raise RuntimeError("Claude API 오류 %s" % e.status_code) from e
    except anthropic.APIConnectionError as e:   # 시간 초과 포함
        raise RuntimeError("Claude API 에 연결하지 못했어요") from e
    elapsed = int((time.time() - started) * 1000)
    if response.stop_reason == "refusal":
        # 안전 장치가 거절한 말: 보낼 수 있는 말이 없는 것으로 처리한다
        return {"target": None, "messageIndex": None, "ms": elapsed}
    text = next((b.text for b in response.content if b.type == "text"), None)
    if text is None:
        raise RuntimeError("Claude 응답에 내용이 없어요 (%s)" % response.stop_reason)
    try:
        data = json.loads(text)
    except ValueError as e:
        raise RuntimeError("Claude 응답을 읽지 못했어요") from e
    target = data.get("target")
    message = data.get("message")
    return {
        "target": target if target in TARGETS else None,
        "messageIndex": choices.index(message) if message in choices else None,
        "ms": elapsed,
    }
