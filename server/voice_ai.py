"""운전자가 한 말을 '누구에게 / 정해진 메시지 중 어느 것'으로 바꾼다 (TypeSafe AI 의 Jev 모델).

예: "뒷차한테 땡큐 보내줘"      → 뒷차(REAR), "감사합니다"
    "뒷차한테 미안하다고 보내줘" → 뒷차(REAR), "죄송합니다"
    욕설·위협·위험한 말          → blocked (보내지 않는다. 앱은 '띵' 소리만 낸다)

Jev 는 글을 쓰지 않고, 물어본 질문마다 '선택지 중 하나 + 확률'만 돌려주는 모델이다.
한 번의 요청으로 네 가지를 묻는다 (질문들은 동시에 처리된다):
    message  (Choice) 정해진 문구 중 뜻이 가장 가까운 것 + 문구별 확률(= 일치도)
    fits     (Noul)   정해진 문구 중에 뜻이 맞는 것이 있기는 한가
    harmful  (Noul)   욕설·비속어·위협·위험한 말인가
    target   (Choice) 어느 방향의 차에게 하는 말인가

설정 (환경 변수):
    TYPESAFE_API_KEY          필수. https://console.typesafe.ai/keys 에서 발급. 서버 PC 에만 둔다 (앱·GitHub 에 넣지 않는다)
    TYPESAFE_DEFAULT_MODEL    선택. 기본 jev-latest

필요: pip install typesafe-sdk   (문서: https://docs.typesafe.ai)
키나 라이브러리가 없으면 서버는 그대로 돌고, 이 기능만 꺼진다 (앱은 단어 매칭으로 대신한다).
"""
import os
import re
import sys
import threading
import time

MODEL = os.environ.get("TYPESAFE_DEFAULT_MODEL", "jev-latest")
TARGETS = ["FRONT", "REAR", "LEFT", "RIGHT", "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT"]
UNSPECIFIED = "UNSPECIFIED"
MAX_UTTERANCE = 200
# 이 확률 이상이어야 받아들인다
MATCH_AT = 0.5     # 고른 문구의 확률 (일치도)
FITS_AT = 0.5      # '맞는 문구가 있다'의 확률
HARMFUL_AT = 0.5   # '욕설·위험한 말이다'의 확률 → 이 이상이면 무시
TARGET_AT = 0.5    # 고른 방향의 확률. 이보다 낮으면 '대상을 말하지 않음'으로 본다

_EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️‍]")
# 차량번호 (예: 12가 3456). 번호로 부른 차는 앱이 직접 찾으므로, 모델에게는 '그 차'로 바꿔서 보여 준다.
# (번호를 그대로 두면 숫자·글자가 문구 판단을 흐린다: "12가 3690한테 괜찮아요" 를 '먼저 가세요'와 헷갈린 적이 있다)
_PLATE = re.compile(r"\d{2,3}\s*[가-힣]\s*\d{4}")

# 문구마다 '언제 고르는지'를 적어 주면 서로 헷갈리는 문구를 더 잘 가른다. 목록에 없는 문구는 설명 없이 이름만 보낸다
DESCRIPTIONS = {
    "감사합니다": "고마움을 전하는 말 (고마워, 땡큐, 양보해 줘서 고맙다 등)",
    "조심히 가세요": "안전하게 가라는 작별 인사 (조심해서 가세요, 안전 운전하세요 등)",
    "좋은 하루 보내세요": "좋은 하루를 보내라는 인사 (좋은 하루 되세요 등)",
    "죄송합니다": "사과하는 말 (미안해요, 쏘리 등)",
    "제가 뒤로 갈게요": "내가 후진해서 비켜 주겠다는 말 (제가 뒤로 뺄게요 등)",
    "제가 비켜 드릴게요": "내가 옆으로 비켜 주겠다는 말",
    "끼어들어도 될까요?": "앞으로 끼어들어도 되는지 묻는 말 (좀 끼워 주세요 등)",
    "주유구가 열려 있어요": "상대 차의 주유구(충전구) 뚜껑이 열려 있다고 알려 주는 말",
    "라이트 꺼져 있어요": "상대 차의 전조등(라이트)이 꺼져 있다고 알려 주는 말 (라이트 안 켰어요, 전조등 켜세요, 불 꺼졌어요 등)",
    "브레이크 등이 안 들어와요": "상대 차의 브레이크 등(제동등)이 안 켜진다고 알려 주는 말",
    "짐이 떨어질 것 같아요": "상대 차의 짐(적재물)이 떨어질 것 같다고 알려 주는 말",
    "차에서 연기가 나요": "상대 차에서 연기가 난다고 알려 주는 말",
    "조심하세요": "주의하라고 알리는 말 (작별 인사가 아니라 경고)",
    "추월 가능합니다": "나를 추월해(앞질러) 가도 된다는 말 (추월하셔도 돼요, 앞질러 가셔도 돼요 등)",
    "전방에 위험이 있습니다": "앞쪽에 위험한 것(사고, 낙하물 등)이 있다고 알리는 말",
    "차량에 이상이 있습니다": "상대 차에 고장이나 이상이 있어 보인다고 알리는 말",
    "전방 정체 중입니다": "앞쪽 길이 막혀 있다고 알리는 말",
    "정차하겠습니다": "내가 곧 멈춰 서겠다고 알리는 말",
    "보행자가 있습니다": "길에 걷는 사람이 있다고 알리는 말",
    "승객이 탑승 중입니다": "내 차에 사람이 타고 있는 중이라고 알리는 말",
    "승객이 하차 중입니다": "내 차에서 사람이 내리고 있는 중이라고 알리는 말",
    "먼저 가세요": "상대에게 먼저 가라고 양보하는 말 (먼저 지나가세요, 제가 양보할게요 등. 추월·앞지르기 이야기는 아니다)",
    "네": "상대의 말에 알겠다고 짧게 답하는 말 (알겠습니다, 그래요, 괜찮아요 등)",
}
TARGET_LABELS = {
    "FRONT": "앞차 (바로 앞)", "REAR": "뒷차 (바로 뒤)", "LEFT": "왼쪽 옆 차", "RIGHT": "오른쪽 옆 차",
    "FRONT_LEFT": "왼쪽 앞 대각선의 차", "FRONT_RIGHT": "오른쪽 앞 대각선의 차",
    "REAR_LEFT": "왼쪽 뒤 대각선의 차", "REAR_RIGHT": "오른쪽 뒤 대각선의 차",
    UNSPECIFIED: "운전자가 어느 차인지 말하지 않았다",
}

_lock = threading.Lock()
_client = None


def _user_env_key():
    """Windows 에서 `setx TYPESAFE_API_KEY ...` 로 저장한 값은 이미 떠 있는 프로그램에는 안 보인다 → 사용자 환경 변수를 직접 읽는다"""
    if sys.platform != "win32":
        return None
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
            value, _ = winreg.QueryValueEx(k, "TYPESAFE_API_KEY")
            return value or None
    except OSError:
        return None


def status():
    """'ready' / 'no_sdk' (pip install typesafe-sdk 필요) / 'no_key' (TYPESAFE_API_KEY 없음)"""
    try:
        import typesafe_sdk  # noqa: F401
    except ImportError:
        return "no_sdk"
    if not os.environ.get("TYPESAFE_API_KEY"):
        key = _user_env_key()
        if not key:
            return "no_key"
        os.environ["TYPESAFE_API_KEY"] = key.strip()
    return "ready"


def _get_client():
    global _client
    if _client is None:
        from typesafe_sdk import TypeSafeClient
        _client = TypeSafeClient(model=MODEL)
    return _client


def clean(text):
    """그림 문자를 뺀 문구 (모델에게는 글자만 보여 준다)"""
    return _EMOJI.sub("", text).strip()


def interpret(utterance, messages, cars, selected, client=None):
    """utterance 를 해석한다.
    돌려주는 값: {
        "blocked": 욕설·위험한 말이라 무시해야 하는지,
        "target": 방향 또는 None(대상을 말하지 않음),
        "messageIndex": messages 의 번호 또는 None(일치도가 높은 문구가 없음),
        "score": 고른 문구의 일치도(0~1), "ms": 걸린 시간 }
    Jev 를 쓸 수 없으면 RuntimeError(이유) 를 던진다."""
    from typesafe_sdk import (Choice, Noul, NoulCriteria, RetryPolicy, TypeSafeAPIConnectionError, TypeSafeAPIError,
                              TypeSafeAuthenticationError, TypeSafeError, TypeSafeRateLimitError)

    choices = [clean(m) for m in messages]
    state = {
        "driver_said": _PLATE.sub("그 차", utterance)[:MAX_UTTERANCE],
        "allowed_messages": choices,
        "nearby_cars": [TARGET_LABELS[c] for c in cars if c in TARGETS],
        "selected_car": TARGET_LABELS.get(selected) if selected in TARGETS else None,
        "context": "차량용 앱. 운전자가 주행 중에 주변 차에게 짧은 인사를 보내려고 한 말을 음성 인식한 결과라서, "
                   "띄어쓰기·맞춤법이 틀리거나 말이 덜 끝났을 수 있다.",
    }
    questions = {
        "message": Choice(
            instructions="driver_said 로 운전자가 주변 차에게 전하려는 뜻과 가장 가까운 문구는 무엇인가? "
                         "표현이 달라도(반말, 외래어, 돌려 말하기) 뜻으로 판단한다.",
            criteria={c: DESCRIPTIONS.get(c) for c in choices},
        ),
        "fits": Noul(
            instructions="driver_said 는 allowed_messages 중 하나와 같은 뜻을 전하려는 말인가?",
            criteria=NoulCriteria(
                true="목록의 문구 중 하나와 뜻이 같다 (표현은 달라도 됨. 같은 사실이나 상황을 다른 말로 알리는 것도 포함)",
                false="목록의 어느 문구와도 뜻이 다르다. 인사와 상관없는 말(길 안내, 음악, 질문, 혼잣말 등)도 여기에 해당",
            ),
        ),
        "harmful": Noul(
            instructions="driver_said 에 욕설·비속어, 상대를 비난·조롱·위협하는 말, 위험한 행동을 부추기는 말이 들어 있는가?",
            criteria=NoulCriteria(
                true="욕설·비속어가 있거나, 상대 운전자를 비난·조롱·위협하거나, 위험 운전·보복·폭력을 부추긴다",
                false="그런 표현이 없다. 정중하거나 평범한 말",
            ),
        ),
        "target": Choice(
            instructions="driver_said 에서 운전자는 내 차 기준 어느 방향의 차에게 말하고 있는가? "
                         "방향을 말했으면 그 방향에 차가 없더라도 말한 방향을 고른다.",
            criteria=dict(TARGET_LABELS),
        ),
    }
    started = time.time()
    try:
        with _lock:
            result = (client or _get_client()).system_one(
                state, questions, model=MODEL,
                # 운전 중 명령이라 오래 기다리지 않는다. 늦으면 앱이 단어 매칭으로 대신한다
                retry=RetryPolicy(max_retries=1, backoff_initial=0.2, backoff_max=0.5, timeout=4.0),
            )
    except TypeSafeAuthenticationError as e:
        raise RuntimeError("API 키가 올바르지 않아요") from e
    except TypeSafeRateLimitError as e:
        raise RuntimeError("요청이 너무 많아요 (잠시 뒤 다시)") from e
    except TypeSafeAPIConnectionError as e:   # 시간 초과 포함
        raise RuntimeError("Jev API 에 연결하지 못했어요") from e
    except TypeSafeAPIError as e:
        raise RuntimeError("Jev API 오류 %s" % getattr(e, "status", "")) from e
    except TypeSafeError as e:               # 키 형식 오류 등 요청을 보내기 전의 문제
        raise RuntimeError("Jev 를 쓸 수 없어요: %s" % e) from e
    elapsed = int((time.time() - started) * 1000)

    message = result.choices["message"]
    target = result.choices["target"]
    harmful = float(result.nouls["harmful"].noul)
    fits = float(result.nouls["fits"].noul)
    score = float(message.probabilities.get(message.choice, 0.0))
    blocked = harmful >= HARMFUL_AT
    matched = (not blocked) and message.choice in choices and fits >= FITS_AT and score >= MATCH_AT
    target_ok = target.choice in TARGETS and float(target.probabilities.get(target.choice, 0.0)) >= TARGET_AT
    return {
        "blocked": blocked,
        "target": target.choice if target_ok else None,
        "messageIndex": choices.index(message.choice) if matched else None,
        "score": round(score, 3),
        "fits": round(fits, 3),
        "harmful": round(harmful, 3),
        "ms": elapsed,
    }
