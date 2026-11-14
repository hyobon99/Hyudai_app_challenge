"""Car-ind CHAT 주행 시나리오 시연 도구.

'실제 도로에서 차들이 이렇게 움직일 때, 각 차의 Car-ind CHAT 화면에는 이렇게 보이고 이렇게 메시지를 보낸다'
를 한 화면에 보여 주는 영상을 만든다.

  왼쪽  : 위에서 본 실제 상황 (이 스크립트가 그림. 앱 없는 차·공사 구간 등도 보임)
  오른쪽: 두 에뮬레이터의 Car-ind CHAT 앱 화면 (실제 앱을 녹화한 것)

앱 화면은 꾸민 것이 아니다. 시나리오의 차 위치를 에뮬레이터 GPS 에 계속 넣어 주면(geo fix),
앱이 FusedLocation SDK 로 그 위치를 받아 서버에 올리고, 상대 차의 위치 벡터를 계산해 그린다.
메시지는 앱의 음성 명령 경로로 보낸다: '이렇게 말했다'는 글자를 앱에 넣어 주면(시험용 신호), 앱이 실제와 같이
정해진 메시지 중 가장 가까운 말을 골라 상대 차에게 보낸다. (마이크 인식 단계만 건너뛴다)

사용법 (서버와 에뮬레이터 2대, 두 대 모두 Car-ind CHAT 가 떠 있어야 함):
    python tools/scenario/run_scenario.py merge      # 끼어들기 → 감사 인사
    python tools/scenario/run_scenario.py yield      # 좁은 골목에서 마주침 → 양보
    python tools/scenario/run_scenario.py merge --preview   # 에뮬레이터 없이 왼쪽 상황 그림만 미리 보기
    python tools/scenario/run_scenario.py merge --live      # 영상으로 만들지 않고 지금 돌아가는 모습을 창으로 본다
                                                            #   (실제 상황 창 + 두 에뮬레이터의 앱 화면 창. --loop 반복, --hands 메시지는 직접 보내기)

결과: docs/demo/scenario_<이름>.mp4
필요: Python 3.10+, Pillow (pip install pillow), ffmpeg, adb
도로 좌표: OpenStreetMap (테헤란로, 테헤란로8길) © OpenStreetMap contributors
"""
import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = shutil.which("adb") or "adb"
M_PER_DEG = 111_320.0
CAR_L, CAR_W = 4.6, 1.9

# 앱의 방향 메뉴와 같은 순서 (chat/PresetMessages.kt). 0 = 위쪽, 시계 방향으로 같은 간격 (7개 = 정칠각형, 약 51.4° 씩)
RADIAL_MESSAGES = ["감사합니다 🙏", "양보 감사해요", "먼저 가세요", "조심히 가세요",
                   "죄송합니다 🙇", "괜찮아요 👍", "안녕하세요 👋"]

EMULATOR_SKINS = {"A": 1, "B": 0}   # 차 색 (0 파랑, 1 빨강, 2 노랑, 3 초록, 4 보라, 5 은색)
PRE_ROLL = 8.0    # 시작 전에 미리 달리는 시간 (앱이 이동 방향·속도를 알게 됨)
LEAD_IN = 2.0     # 영상 앞에 넣는 시작 전 구간
FPS = 30
REC_STOP_DELAY = 0.35   # screenrecord 가 시간 제한에 걸린 뒤 파일을 마무리하고 끝나기까지 걸리는 시간 (대략)
# 에뮬레이터 GPS 에 위치를 넣는 간격. 앱(FusedLocation)은 1초마다 '마지막으로 넣은 위치'를 받아 가므로,
# 드문드문 넣으면 그 사이에 달린 거리만큼 위치가 들쭉날쭉해진다 (초속 10m · 0.2초 = 2m) → 0.1초마다 넣는다.
# 이보다 훨씬 촘촘하게(0.04초) 넣으면 에뮬레이터의 위치 서비스가 밀려서 멈춘 적이 있으니 더 줄이지 말 것
GPS_FEED_SECONDS = 0.1


# ------------------------------------------------------------------ 도로 · 궤적

class Road:
    """직선 도로. s = 진행 방향 거리(m), d = 중심선에서 오른쪽으로 떨어진 거리(m)"""

    def __init__(self, lat0, lng0, lat1, lng1):
        self.lat0, self.lng0 = lat0, lng0
        north = (lat1 - lat0) * M_PER_DEG
        east = (lng1 - lng0) * M_PER_DEG * math.cos(math.radians(lat0))
        self.bearing = math.atan2(east, north)
        self.length = math.hypot(north, east)

    def to_latlng(self, s, d):
        b = self.bearing
        north = s * math.cos(b) - d * math.sin(b)
        east = s * math.sin(b) + d * math.cos(b)
        return (self.lat0 + north / M_PER_DEG,
                self.lng0 + east / (M_PER_DEG * math.cos(math.radians(self.lat0))))


class Line:
    """(t, 값) 꺾은선. 범위 밖은 양 끝 값 유지"""

    def __init__(self, keys):
        self.keys = sorted(keys)

    def at(self, t):
        k = self.keys
        if t <= k[0][0]:
            return k[0][1]
        for (t0, v0), (t1, v1) in zip(k, k[1:]):
            if t <= t1:
                return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
        return k[-1][1]

    def integral(self, t):
        """0 부터 t 까지 적분 (속도 → 이동 거리)"""
        k = self.keys
        if t <= k[0][0]:
            return k[0][1] * (t - k[0][0]) + k[0][1] * k[0][0]
        total = k[0][1] * k[0][0]
        for (t0, v0), (t1, v1) in zip(k, k[1:]):
            if t <= t1:
                v = v0 + (v1 - v0) * (t - t0) / (t1 - t0)
                return total + (v0 + v) / 2 * (t - t0)
            total += (v0 + v1) / 2 * (t1 - t0)
        return total + k[-1][1] * (t - k[-1][0])


class Ease:
    """(t, 값) 사이를 부드럽게(S자) 잇는다. 차로 변경 같은 옆 이동에 쓴다"""

    def __init__(self, keys):
        self.keys = sorted(keys)

    def at(self, t):
        k = self.keys
        if t <= k[0][0]:
            return k[0][1]
        for (t0, v0), (t1, v1) in zip(k, k[1:]):
            if t <= t1:
                u = (t - t0) / (t1 - t0)
                return v0 + (v1 - v0) * (u * u * (3 - 2 * u))
        return k[-1][1]


@dataclass
class Car:
    key: str                 # 'A', 'B' …
    plate: str               # 앱에 등록된 차량번호 (에뮬레이터 차는 앱에 설정된 번호와 같아야 함)
    kind: str                # 'emulator' = 에뮬레이터의 실제 앱 / 'virtual' = 앱 쓰는 가상 차(서버에만 위치 보고) / 'plain' = 앱 없는 차
    s0: float
    speed: Line
    lateral: Ease
    direction: int = 1       # +1 도로 방향, -1 반대 방향
    color: tuple = (120, 130, 150)
    label: str = ""
    blink: list = field(default_factory=list)   # [(t0, t1, 'L'|'R')]
    device: str = ""
    _course: float = None

    def state(self, t):
        """t 초의 (s, d, 속도, 진행 각도(도로 방향 기준 rad, 오른쪽 +), 가속도). 멈춰 있으면 마지막 진행 각도를 유지"""
        v = self.speed.at(t)
        s = self.s0 + self.direction * self.speed.integral(t)
        d = self.lateral.at(t)
        if self._course is None:
            self._course = 0.0 if self.direction > 0 else math.pi
        if v > 0.2:
            dd = (self.lateral.at(t + 0.05) - self.lateral.at(t - 0.05)) / 0.1
            self._course = math.atan2(dd, self.direction * v)
        accel = (self.speed.at(t + 0.1) - self.speed.at(t - 0.1)) / 0.2
        return s, d, v, self._course, accel

    def blinking(self, t):
        for t0, t1, side in self.blink:
            if t0 <= t <= t1:
                return side
        return None


@dataclass
class Scenario:
    name: str
    title: str
    road: Road
    duration: float
    cars: list
    steps: list              # [(t, 설명)]
    actions: list            # [(t, 보내는 차, 받는 차, RADIAL_MESSAGES 번호)]
    style: str               # 'avenue' | 'alley'
    px_per_m: float
    extras: dict = field(default_factory=dict)

    def car(self, key):
        return next(c for c in self.cars if c.key == key)


def relative(road_state_me, road_state_other):
    """내 차 기준 상대 위치 벡터 (앞 +, 오른쪽 +). 앱의 Geo.kt 와 같은 계산"""
    s0, d0, _, c, _ = road_state_me
    s1, d1 = road_state_other[0], road_state_other[1]
    ds, dd = s1 - s0, d1 - d0
    forward = ds * math.cos(c) + dd * math.sin(c)
    lateral = -ds * math.sin(c) + dd * math.cos(c)
    return forward, lateral


def cell_name(forward, lateral):
    """앱과 같은 8칸 분류 (좌우 1.75m 안 = 같은 차로, 앞뒤 5m 안 = 바로 옆)"""
    if abs(lateral) <= 1.75:
        return "앞차" if forward >= 0 else "뒷차"
    side = "오른쪽" if lateral > 0 else "왼쪽"
    if abs(forward) <= 5:
        return f"{side} 차"
    return f"{side} {'앞' if forward > 0 else '뒤'} 차"


# ------------------------------------------------------------------ 시나리오

BLUE, AMBER, TEAL, GREY, DARK = (61, 139, 255), (255, 170, 60), (64, 200, 170), (130, 138, 152), (92, 98, 112)


def scenario_merge(dev_a, dev_b):
    """끼어들기: 공사 구간을 코앞에 두고 B 가 A 바로 앞으로 급하게 들어오고, A 는 급브레이크로 자리를 내준 뒤 인사를 주고받는다"""
    road = Road(37.498437, 127.029417, 37.500712, 127.036993)   # 테헤란로 동쪽 방향 (715m 직선)
    base = 110.0
    cruise = 10.0   # 36 km/h
    cars = [
        # 6.6초에 급브레이크 (1.3초 만에 36 → 20 km/h, 약 -3.5 m/s²), 잠깐 버틴 뒤 다시 가속
        Car("A", "99허 0000", "emulator", base, Line([(0, cruise), (6.6, cruise), (7.9, 5.5), (8.4, 5.5), (10.9, cruise), (60, cruise)]),
            Ease([(0, -1.75)]), color=BLUE, label="A · 내 차", device=dev_a),
        # A 보다 6m 앞(차 사이 1.4m)에서 2.2초 만에 차로를 바꾼다
        Car("B", "12가 3690", "emulator", base + 6.0, Line([(0, cruise), (60, cruise)]),
            Ease([(0, 1.75), (6.2, 1.75), (8.4, -1.75)]), color=AMBER, label="B", blink=[(5.0, 8.8, "L")], device=dev_b),
        Car("C", "55모 5501", "virtual", base - 14.0, Line([(0, 10.8), (60, 10.8)]),
            Ease([(0, -5.25)]), color=TEAL, label="C"),
        Car("D", "", "plain", base - 9.0, Line([(0, 9.4), (60, 9.4)]), Ease([(0, 5.25)]), color=GREY),
        Car("E", "", "plain", base + 34.0, Line([(0, cruise), (60, cruise)]), Ease([(0, -1.75)]), color=GREY),
    ]
    steps = [
        (-99, "주행 중. B(주황)가 달리는 차로는 바로 앞이 공사로 막혀 있어요"),
        (5.0, "B가 뒤늦게 왼쪽 깜빡이를 켜요"),
        (6.2, "B가 A 바로 앞으로 급하게 끼어들어요"),
        (6.9, "A(파랑, 내 차)가 급브레이크를 밟아 자리를 내줘요"),
        (9.2, "A가 양보해서 두 차 사이가 벌어졌어요"),
        (12.2, "B: 버튼을 꾹 누르고 \"양보해 줘서 고마워요\"라고 말해요"),
        (16.2, "A: 버튼을 꾹 누르고 \"괜찮아요\"라고 답해요"),
        (20.5, "비상등 대신 말로 전했어요"),
    ]
    actions = [(13.0, "B", "A", 1), (17.0, "A", "B", 5)]
    return Scenario("merge", "급한 끼어들기 → 양보 → 인사", road, 24.0, cars, steps, actions, "avenue", 16.0,
                    extras={"work_zone": (base + 105.0, base + 160.0, 1.75)})


def scenario_yield(dev_a, dev_b):
    """좁은 골목에서 마주침: A 가 '먼저 가세요'를 보내고 빈자리로 비켜 주면 B 가 지나간다"""
    road = Road(37.495722, 127.032628, 37.499081, 127.031)   # 테헤란로8길 (400m 직선, 북북서 방향)
    base = 120.0
    slow = 4.5      # 16 km/h
    a_stop = base + slow * 5 + slow * 3.5 / 2            # 처음 멈추는 곳
    gap = 14.0
    cars = [
        Car("A", "99허 0000", "emulator", base,
            Line([(0, slow), (5, slow), (8.5, 0), (15.5, 0), (16.5, 2.4), (18.3, 2.4), (20.5, 0),
                  (27, 0), (29, 3.0), (32, slow), (60, slow)]),
            Ease([(0, 0), (15.6, 0), (18.2, 2.2), (27.2, 2.2), (31, 0)]), color=BLUE, label="A · 내 차", device=dev_a),
        Car("B", "12가 3690", "emulator", a_stop + gap + (slow * 5 + slow * 3.5 / 2),
            Line([(0, slow), (5, slow), (8.5, 0), (21, 0), (23, 3.5), (60, 3.5)]),
            Ease([(0, 0), (21, 0), (23, -0.45), (27, -0.45), (30, 0)]), direction=-1, color=AMBER, label="B", device=dev_b),
    ]
    steps = [
        (-99, "좁은 골목. 양쪽에 주차된 차 때문에 한 대만 지나갈 수 있어요"),
        (6.0, "A(파랑, 내 차)와 B(주황)가 마주쳐서 둘 다 멈췄어요"),
        (9.2, "A: 버튼을 꾹 누르고 \"먼저 가세요\"라고 말해요"),
        (12.4, "B: \"감사합니다\" 답장"),
        (15.5, "A가 오른쪽 빈자리로 비켜 줘요"),
        (21.0, "B가 지나가요"),
        (27.0, "A도 다시 출발. 경적이나 손짓 없이 해결했어요"),
    ]
    actions = [(10.0, "A", "B", 2), (13.2, "B", "A", 0)]
    pocket = (a_stop - 1.0, a_stop + 17.0)   # 오른쪽에 주차된 차가 없는 구간
    return Scenario("yield", "좁은 골목에서 마주침 → 양보", road, 33.0, cars, steps, actions, "alley", 21.0,
                    extras={"pocket": pocket})


SCENARIOS = {"merge": scenario_merge, "yield": scenario_yield}


# ------------------------------------------------------------------ 에뮬레이터 · 서버

class EmulatorConsole:
    """에뮬레이터 콘솔(telnet)에 직접 붙어 GPS 위치를 넣는다. adb 를 매번 실행하는 것보다 훨씬 빠르다"""

    def __init__(self, serial):
        self.serial = serial
        port = int(serial.split("-")[1])
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        time.sleep(0.2)
        self._drain()
        token_path = os.path.join(os.path.expanduser("~"), ".emulator_console_auth_token")
        if os.path.exists(token_path):
            self.sock.sendall(("auth %s\r\n" % open(token_path).read().strip()).encode())
            time.sleep(0.2)
            self._drain()
        self.sock.setblocking(False)

    def _drain(self):
        try:
            self.sock.settimeout(0.05)
            while self.sock.recv(4096):
                pass
        except (socket.timeout, BlockingIOError, OSError):
            pass

    def geo_fix(self, lat, lng):
        try:
            self.sock.sendall(("geo fix %.7f %.7f\r\n" % (lng, lat)).encode())
            try:
                self.sock.recv(4096)
            except (BlockingIOError, OSError):
                pass
        except OSError as e:
            print("  ! geo fix 실패 (%s): %s" % (self.serial, e))


def adb(serial, *args, timeout=30):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, timeout=timeout)


class Server:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json; charset=utf-8"})
        with urllib.request.urlopen(req, timeout=4) as r:
            return json.loads(r.read() or b"{}")

    def presence(self, plate, lat, lng, heading, speed):
        # 이 PC 가 서버와 같은 시계를 쓰므로 측정 시각(fixedAt)을 그대로 보낸다
        self.call("PUT", "/v1/presence", {"plate": plate, "lat": lat, "lng": lng, "heading": heading, "speed": speed,
                                           "fixAge": 0, "fixedAt": int(time.time() * 1000), "accuracy": 3.0})

    def cursor(self, plate):
        return self.call("GET", "/v1/messages?to=%s&after=0" % urllib.parse.quote(plate)).get("cursor", 0)

    def arrived(self, to_plate, from_plate, after):
        res = self.call("GET", "/v1/messages?to=%s&after=%d" % (urllib.parse.quote(to_plate), after))
        key = "".join(from_plate.split())
        return any("".join(m["fromPlate"].split()) == key for m in res.get("messages", []))

    def send(self, from_plate, to_plate, text):
        self.call("POST", "/v1/messages", {"fromPlate": from_plate, "toPlate": to_plate, "text": text})


def on_screen_position(serial, plate):
    """앱이 0.4초마다 남기는 로그(RoadTalkUi)에서, 그 차가 지금 화면 어디에 그려져 있는지 읽는다"""
    out = adb(serial, "logcat", "-d", "-t", "4000", "-s", "RoadTalkUi:D").stdout.decode("utf-8", "replace")
    key = "".join(plate.split())
    found = None
    for m in re.finditer(r"car (\S+) (-?\d+) (-?\d+)", out):
        if m.group(1) == key:
            found = (int(m.group(2)), int(m.group(3)))
    return found


# 정해진 메시지마다 운전자가 실제로 할 법한 말 (앱이 이 말을 듣고 그 메시지를 골라야 한다)
SPOKEN = ["고맙습니다", "양보해 줘서 고마워요", "먼저 가세요", "조심히 가세요", "미안합니다", "괜찮아요", "안녕하세요"]


def say(serial, to_plate, index):
    """앱에 '이 차에게 이렇게 말했다'고 넣어 준다 (디버그 빌드의 시험용 신호. 마이크 인식만 건너뛰고 나머지는 실제와 같다)"""
    text = "%s한테 %s" % (to_plate, SPOKEN[index])
    adb(serial, "shell", "am", "broadcast", "--user", "10", "-a", "com.roadtalk.app.STT_TEST", "--es", "say", "'%s'" % text)
    return text


# ------------------------------------------------------------------ 실행

def run_live(sc, server, out_dir, record=True, events=None, stop=None, with_actions=True):
    """시나리오를 실제 에뮬레이터에 흘려보낸다. 메시지가 실제로 보내진 시각 목록을 돌려준다.
    events = 보낸 메시지를 실시간으로 받아 볼 목록, stop = 중간에 멈추는 신호, with_actions = 정해진 메시지를 자동으로 보낼지"""
    emus = [c for c in sc.cars if c.kind == "emulator"]
    virtuals = [c for c in sc.cars if c.kind == "virtual"]
    consoles = {c.key: EmulatorConsole(c.device) for c in emus}
    for c in emus:
        adb(c.device, "shell", "am", "start", "--user", "10", "-n", "com.roadtalk.app/.MainActivity")
        adb(c.device, "logcat", "-c")
        # 두 차가 화면에서 구별되게 색을 정해 둔다 (A 빨강, B 파랑). 앱에서 '차 색'으로 고르는 것과 같은 값
        adb(c.device, "shell", "am", "broadcast", "--user", "10", "-a", "com.roadtalk.app.STT_TEST", "--ei", "skin", str(EMULATOR_SKINS.get(c.key, 5)))
    if events is None:
        events = []        # (t, 보낸 차, 받은 차, 문구, 방식)
    lock = threading.Lock()
    feeding = threading.Event()
    feeding.set()
    t0_wall = time.time() + PRE_ROLL
    sc.t0_wall = t0_wall   # 분석 도구가 시나리오 시각과 기기 로그 시각을 맞출 때 쓴다
    recorders = {}
    rec_started = {}
    rec_seconds = int(sc.duration + LEAD_IN + 9)

    def now():
        return time.time() - t0_wall

    def feed_gps():
        while feeding.is_set():
            started = time.time()
            # 다음에 넣을 때까지 이 위치가 쓰이므로, 그 구간의 가운데 시각의 위치를 넣는다
            t = now() + GPS_FEED_SECONDS / 2
            for c in emus:
                s, d, _, _, _ = c.state(t)
                lat, lng = sc.road.to_latlng(s, d)
                consoles[c.key].geo_fix(lat, lng)
            time.sleep(max(0.01, GPS_FEED_SECONDS - (time.time() - started)))

    def feed_virtuals():
        while feeding.is_set() and now() < sc.duration + 1.0:
            t = now()
            for c in virtuals:
                s, d, v, course, _ = c.state(t)
                lat, lng = sc.road.to_latlng(s, d)
                heading = (math.degrees(sc.road.bearing + course) + 360) % 360
                try:
                    server.presence(c.plate, lat, lng, heading, v)
                except Exception as e:
                    print("  ! 가상 차 위치 보고 실패:", e)
            time.sleep(0.5)

    def do_action(sender, target, index):
        text = RADIAL_MESSAGES[index]
        try:
            cursor = server.cursor(target.plate)
        except Exception:
            cursor = 0
        how = "음성 명령으로"
        ok = False
        sent_at = now()
        for attempt in range(2):   # 못 보냈으면 한 번 더
            say(sender.device, target.plate, index)
            sent_at = now() - 0.2
            for _ in range(10):
                try:
                    if server.arrived(target.plate, sender.plate, cursor):
                        ok = True
                        break
                except Exception:
                    pass
                time.sleep(0.3)
            if ok:
                break
        if not ok:
            # 앱이 보내지 못했을 때: 영상 흐름이 끊기지 않게 서버로 직접 보낸다
            how = "서버로 직접(앱 전송 실패)"
            server.send(sender.plate, target.plate, text)
            sent_at = now()
        with lock:
            events.append((sent_at, sender.key, target.key, text, how))
        print("  %5.1fs  %s → %s  \"%s\"  [%s]" % (sent_at, sender.key, target.key, text, how))

    threads = []
    gps_thread = threading.Thread(target=feed_gps, daemon=True)
    gps_thread.start()
    if virtuals:
        threads.append(threading.Thread(target=feed_virtuals, daemon=True))
        threads[-1].start()
    pending = sorted(sc.actions) if with_actions else []
    print("시나리오 '%s' 시작 (미리 달리기 %.0f초 + 본편 %.0f초)" % (sc.title, PRE_ROLL, sc.duration))
    while True:
        t = now()
        if t > sc.duration + 1.5 or (stop is not None and stop.is_set()):
            break
        # 녹화는 명령을 내리고 2~4초 뒤에야 실제로 시작되므로 넉넉히 일찍 건다
        if record and not recorders and t >= -(LEAD_IN + 5.0):
            for c in emus:
                rec_started[c.key] = time.time()
                recorders[c.key] = subprocess.Popen(
                    [ADB, "-s", c.device, "shell", "screenrecord", "--time-limit", str(rec_seconds),
                     "--size", "1920x1080", "--bit-rate", "8000000", "/data/local/tmp/roadtalk_scn.mp4"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        while pending and t >= pending[0][0]:
            _, a, b, index = pending.pop(0)
            th = threading.Thread(target=do_action, args=(sc.car(a), sc.car(b), index), daemon=True)
            th.start()
            threads.append(th)
        time.sleep(0.05)
    for th in threads:
        th.join(timeout=6)
    feeding.clear()
    gps_thread.join(timeout=2)
    for console in consoles.values():
        try:
            console.sock.close()
        except OSError:
            pass
    offsets = {}
    if record:
        print("녹화 마무리 중…")
        # 녹화는 시작한 지 정확히 rec_seconds 뒤에 끝난다. 끝난 시각에서 거꾸로 계산하면 실제 시작 시각을 알 수 있다
        # (명령을 실행한 뒤 실제로 녹화가 시작되기까지 1~2초 걸리고 그때그때 달라서, 실행 시각으로는 못 맞춘다)
        ended = {}
        deadline = time.time() + rec_seconds + 25
        while len(ended) < len(emus) and time.time() < deadline:
            for c in emus:
                if c.key not in ended and recorders[c.key].poll() is not None:
                    ended[c.key] = time.time()
            time.sleep(0.03)
        time.sleep(1.0)
        for c in emus:
            local = os.path.join(out_dir, "app_%s.mp4" % c.key)
            adb(c.device, "pull", "/data/local/tmp/roadtalk_scn.mp4", local, timeout=60)
            started = ended.get(c.key, rec_started[c.key] + rec_seconds + 1.5) - rec_seconds - REC_STOP_DELAY
            # 본편 -LEAD_IN 초가 녹화 영상의 몇 초인지
            # (음수면 녹화가 늦게 시작된 것 → 합칠 때 그만큼 늦춰서 얹는다)
            offsets[c.key] = (t0_wall - LEAD_IN) - started
            print("  %s 녹화: 실행 후 %.1f초 뒤 시작, 영상 %.1f초 지점부터 사용" % (c.key, started - rec_started[c.key], offsets[c.key]))
    return sorted(events), offsets


# ------------------------------------------------------------------ 그리기 (왼쪽 실제 상황 + 설명)

W, H = 1920, 1080
WORLD_W = 520
# 오른쪽은 차 한 대당 세로 칸 하나: 위에 설명 3줄, 아래에 그 차 앱의 '도로 화면' 녹화
COL_X = {"A": 540, "B": 1230}
COL_W, HEAD_H = 670, 150
APP_W, APP_H = 553, 914          # 앱 도로 화면(732x1210)을 세로 914 에 맞춘 크기
APP_Y = HEAD_H + 8
BG = (11, 18, 32)


def font(size, bold=False):
    for path in (r"C:\Windows\Fonts\malgunbd.ttf" if bold else r"C:\Windows\Fonts\malgun.ttf",
                 r"C:\Windows\Fonts\malgun.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


F_TITLE, F_BODY, F_SMALL, F_CAR, F_CAPTION = font(30, True), font(22), font(17), font(20, True), font(25, True)
_EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF]")


def plain(text):
    """그림용 글꼴에 없는 이모지는 뺀다"""
    return _EMOJI.sub("", text).strip()


_sprites = {}


def car_sprite(px, color, letter, brake, blink_side, app):
    """위를 보고 있는 차 그림 (2배 크기로 그려 두고 돌릴 때 줄인다)"""
    key = (px, color, letter, brake, blink_side, app)
    if key in _sprites:
        return _sprites[key]
    k = 2
    w, l = int(CAR_W * px * k), int(CAR_L * px * k)
    pad = int(6 * k)
    img = Image.new("RGBA", (w + pad * 2, l + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x0, y0 = pad, pad
    if app:   # Car-ind CHAT 사용 차량: 파란 테두리
        d.rounded_rectangle((x0 - 4 * k, y0 - 4 * k, x0 + w + 4 * k, y0 + l + 4 * k), radius=int(w * 0.38), outline=(120, 190, 255, 255), width=2 * k)
    d.rounded_rectangle((x0, y0, x0 + w, y0 + l), radius=int(w * 0.3), fill=color + (255,))
    glass = (20, 26, 38, 230)
    d.rounded_rectangle((x0 + w * 0.12, y0 + l * 0.2, x0 + w * 0.88, y0 + l * 0.35), radius=int(w * 0.1), fill=glass)
    d.rounded_rectangle((x0 + w * 0.12, y0 + l * 0.7, x0 + w * 0.88, y0 + l * 0.8), radius=int(w * 0.1), fill=glass)
    r = w * 0.1
    head, tail = (255, 241, 184, 255), ((255, 60, 60, 255) if brake else (150, 40, 40, 255))
    for fx in (0.22, 0.78):
        d.ellipse((x0 + w * fx - r, y0 + l * 0.05 - r, x0 + w * fx + r, y0 + l * 0.05 + r), fill=head)
        rr = r * (1.5 if brake else 1.0)
        d.ellipse((x0 + w * fx - rr, y0 + l * 0.95 - rr, x0 + w * fx + rr, y0 + l * 0.95 + rr), fill=tail)
    if blink_side:
        fx = 0.04 if blink_side == "L" else 0.96
        rb = r * 1.5
        for fy in (0.1, 0.9):
            d.ellipse((x0 + w * fx - rb, y0 + l * fy - rb, x0 + w * fx + rb, y0 + l * fy + rb), fill=(255, 190, 0, 255))
    if letter:
        f = font(int(w * 0.62), True)
        tw = d.textlength(letter, font=f)
        d.text((x0 + (w - tw) / 2, y0 + l * 0.39), letter, font=f, fill=(255, 255, 255, 255))
    _sprites[key] = img
    return img


def paste_car(frame, sprite, cx, cy, course):
    rotated = sprite.rotate(-math.degrees(course), resample=Image.BICUBIC, expand=True)
    rotated = rotated.resize((max(1, rotated.width // 2), max(1, rotated.height // 2)), Image.LANCZOS)
    frame.alpha_composite(rotated, (int(cx - rotated.width / 2), int(cy - rotated.height / 2)))


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split(" "):
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=fnt) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + ([line] if line else [])


def bubble(draw, text, x, y, fill, color, fnt=None):
    fnt = fnt or F_BODY
    tw = draw.textlength(text, font=fnt)
    w, h = tw + 24, fnt.size + 18
    x = min(max(6, x - w / 2), WORLD_W - w - 6)
    y = max(96, y - h)
    draw.rounded_rectangle((x, y, x + w, y + h), radius=12, fill=fill)
    draw.text((x + 12, y + 7), text, font=fnt, fill=color)


class Renderer:
    def __init__(self, sc, events, preview, world_only=False):
        self.sc, self.events, self.preview = sc, events, preview
        self.world_only = world_only   # True 면 왼쪽 '실제 도로 상황'만 그린다 (실시간 창용)
        # 그릴 때는 궤적을 처음부터 다시 따라가므로 새 차 객체를 쓴다 (멈춘 차의 방향 기억 때문)
        fresh = SCENARIOS[sc.name]("", "")
        self.cars = fresh.cars
        self.px = sc.px_per_m
        self.ego_y = H * 0.62
        self.parked = []
        if sc.style == "alley":
            p0, p1 = sc.extras["pocket"]
            s = 60.0
            while s < 330:
                self.parked.append((s, -2.85, math.pi if int(s) % 3 == 0 else 0.0))
                if not (p0 - 2.5 <= s <= p1 + 2.5):
                    self.parked.append((s + 2.0, 2.85, 0.0))
                s += 6.1

    def frame(self, t):
        sc, px = self.sc, self.px
        img = Image.new("RGBA", (WORLD_W if self.world_only else W, H), BG + (255,))
        d = ImageDraw.Draw(img)
        states = {c.key: c.state(t) for c in self.cars}
        cam_s = states["A"][0]

        def to_xy(s, lat):
            return WORLD_W / 2 + lat * px, self.ego_y - (s - cam_s) * px

        # ---- 도로
        if sc.style == "avenue":
            d.rectangle((0, 0, WORLD_W, H), fill=(30, 40, 58))
            x_l, x_r = to_xy(0, -7.0)[0], to_xy(0, 7.0)[0]
            d.rectangle((x_l, 0, x_r, H), fill=(44, 52, 68))
            d.line((x_l, 0, x_l, H), fill=(240, 200, 60), width=4)        # 중앙선 쪽
            d.line((x_r, 0, x_r, H), fill=(210, 215, 225), width=3)       # 인도 쪽
            for lane_d in (-3.5, 0.0, 3.5):
                x = to_xy(0, lane_d)[0]
                first = math.floor((cam_s - 40) / 8.0) * 8.0
                s = first
                while s < cam_s + 50:
                    y0, y1 = to_xy(s, 0)[1], to_xy(s + 3.0, 0)[1]
                    d.line((x, y0, x, y1), fill=(200, 205, 215), width=2)
                    s += 8.0
            w0, w1, wd = sc.extras["work_zone"]
            xa, xb = to_xy(0, wd - 1.75)[0], to_xy(0, wd + 1.75)[0]
            ya, yb = to_xy(w0, 0)[1], to_xy(w1, 0)[1]
            if yb < H and ya > 0:
                d.rectangle((xa + 3, yb, xb - 3, ya), fill=(70, 62, 48))
                stripe = 0
                y = ya
                while y > yb:
                    d.rectangle((xa + 3, y - 10, xb - 3, y), fill=(255, 150, 30) if stripe % 2 == 0 else (240, 240, 240))
                    stripe += 1
                    y -= 46
                d.text((xa + 6, max(ya - 34, 100)), "공사", font=F_CAR, fill=(20, 20, 20))
            for i in range(6):   # 공사 구간 앞 라바콘 (점점 안쪽으로)
                cs = w0 - 5 - i * 6
                cx, cy = to_xy(cs, wd - 1.5 + i * 0.6)   # 막힌 차로 왼쪽 끝에서 시작해 멀수록 오른쪽으로
                if 0 < cy < H:
                    d.polygon((cx, cy - 8, cx - 6, cy + 6, cx + 6, cy + 6), fill=(255, 120, 20))
        else:
            d.rectangle((0, 0, WORLD_W, H), fill=(34, 44, 60))
            x_l, x_r = to_xy(0, -3.95)[0], to_xy(0, 3.95)[0]
            d.rectangle((x_l, 0, x_r, H), fill=(48, 55, 70))
            d.line((x_l, 0, x_l, H), fill=(120, 128, 140), width=3)
            d.line((x_r, 0, x_r, H), fill=(120, 128, 140), width=3)
            p0, p1 = sc.extras["pocket"]
            xa, ya = to_xy(p1 + 1.5, 1.75)
            xb, yb = to_xy(p0 - 1.5, 3.9)
            if ya < H and yb > 0:
                d.rounded_rectangle((xa, ya, xb, yb), radius=10, outline=(110, 200, 150), width=2)
                d.text((xa + 6, max(ya + 6, 100)), "빈자리", font=F_SMALL, fill=(110, 200, 150))
            for ps, pd, pc in self.parked:
                x, y = to_xy(ps, pd)
                if -80 < y < H + 80:
                    paste_car(img, car_sprite(px, DARK, "", False, None, False), x, y, pc)
            d = ImageDraw.Draw(img)

        # ---- 차
        blink_on = (t * 1.7) % 1.0 < 0.5
        for c in self.cars:
            s, lat, v, course, accel = states[c.key]
            x, y = to_xy(s, lat)
            if not (-90 < y < H + 90):
                continue
            side = c.blinking(t) if blink_on else None
            letter = c.key if c.kind != "plain" else ""
            paste_car(img, car_sprite(px, c.color, letter, accel < -0.3, side, c.kind != "plain"), x, y, course)
        d = ImageDraw.Draw(img)
        for c in self.cars:
            if c.kind == "plain":
                continue
            s, lat, v, course, _ = states[c.key]
            x, y = to_xy(s, lat)
            if 110 < y < H - 40:
                label = "%s  %d km/h" % (c.label, round(v * 3.6))
                tw = d.textlength(label, font=F_SMALL)
                lx = x - CAR_W * px / 2 - 12 - tw if x < WORLD_W * 0.42 else x + CAR_W * px / 2 + 12
                lx = min(max(6, lx), WORLD_W - tw - 6)
                d.rounded_rectangle((lx - 6, y - 14, lx + tw + 6, y + 12), radius=8, fill=(11, 18, 32, 215))
                d.text((lx, y - 13), label, font=F_SMALL, fill=(235, 240, 250))

        # ---- 말풍선 (보낸 뒤 3.8초)
        for et, a, b, text, _ in list(self.events):
            if et <= t <= et + 3.8:
                s, lat = states[a][0], states[a][1]
                x, y = to_xy(s, lat)
                bubble(d, "%s → %s  %s" % (a, b, plain(text)), x, y - CAR_L * px / 2 - 10, (255, 200, 87), (20, 20, 20))

        # ---- 왼쪽 위 설명
        d.rectangle((0, 0, WORLD_W, 92), fill=(11, 18, 32, 235))
        d.text((16, 8), "실제 도로 상황 · " + sc.title, font=F_SMALL, fill=(150, 165, 190))
        step = [text for st, text in sc.steps if st <= t][-1]
        for i, line in enumerate(wrap(d, step.replace('\"', '"'), F_CAPTION, WORLD_W - 32)[:2]):
            d.text((16, 30 + i * 30), line, font=F_CAPTION, fill=(255, 255, 255))
        d.rectangle((0, H - 34, WORLD_W, H), fill=(11, 18, 32, 235))
        d.text((16, H - 29), "파란 테두리 = Car-ind CHAT 사용 차량 · 회색 = 앱 없는 차 (앱 화면에 안 보임)", font=font(14), fill=(150, 165, 190))
        d.line((WORLD_W, 0, WORLD_W, H), fill=(40, 52, 74), width=2)
        if self.world_only:
            return img.convert("RGB")

        # ---- 오른쪽: 차마다 설명 + 앱 화면 자리
        for key in ("A", "B"):
            car = self.sc.car(key)
            other = "B" if key == "A" else "A"
            x0 = COL_X[key]
            ax = x0 + (COL_W - APP_W) // 2
            d.rounded_rectangle((ax - 3, APP_Y - 3, ax + APP_W + 3, APP_Y + APP_H + 3), radius=6, outline=car.color, width=3)
            if self.preview:
                d.text((ax + 120, APP_Y + APP_H / 2), "(%s 차량 앱 화면 녹화 자리)" % key, font=F_BODY, fill=(120, 132, 150))
            d.text((x0, 6), "%s 차량의 Car-ind CHAT 화면" % key, font=F_TITLE, fill=car.color)
            tw = d.textlength("%s 차량의 Car-ind CHAT 화면" % key, font=F_TITLE)
            d.text((x0 + tw + 14, 20), "%s · 실제 앱 녹화" % car.plate, font=F_SMALL, fill=(150, 165, 190))
            fwd, lat = relative(states[key], states[other])
            line = "%s까지  앞뒤 %+.1f m · 좌우 %+.1f m  →  \"%s\"" % (other, fwd, lat, cell_name(fwd, lat))
            d.text((x0, 52), line, font=F_BODY, fill=(235, 240, 250))
            shown = [e for e in self.events if e[0] <= t and key in (e[1], e[2])][-2:]
            if not shown:
                d.text((x0, 88), "위치: FusedLocation SDK → 서버 → 상대 위치 벡터", font=F_SMALL, fill=(110, 124, 148))
            for i, (et, a, b, text, _) in enumerate(shown):
                mine = a == key
                msg = ("보냄 → %s  \"%s\"" % (b, plain(text))) if mine else ("받음 ← %s  \"%s\"" % (a, plain(text)))
                col = (255, 200, 87) if t - et < 3.8 else (200, 208, 222)
                d.text((x0, 86 + i * 30), msg, font=F_BODY, fill=col)
        return img.convert("RGB")


def render_background(sc, events, path, preview):
    r = Renderer(sc, events, preview)
    total = int((LEAD_IN + sc.duration + 1.0) * FPS)
    ff = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H),
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", path],
        stdin=subprocess.PIPE)
    # 멈춘 차의 방향 기억을 맞추려고 미리 달리기 구간부터 따라간다
    t = -PRE_ROLL
    while t < -LEAD_IN:
        for c in r.cars:
            c.state(t)
        t += 0.1
    for i in range(total):
        ff.stdin.write(r.frame(-LEAD_IN + i / FPS).tobytes())
        if i % (FPS * 5) == 0:
            print("  그리는 중 %d/%d" % (i, total))
    ff.stdin.close()
    ff.wait()
    return total / FPS


def compose(bg, apps, offsets, out, seconds):
    """왼쪽 그림 영상 위에 두 앱 녹화를 자리 맞춰 얹는다"""
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", bg]
    filters, last = [], "[0:v]"
    for i, key in enumerate(("A", "B"), start=1):
        if offsets[key] >= 0:
            cmd += ["-ss", "%.2f" % offsets[key], "-i", apps[key]]
        else:
            cmd += ["-itsoffset", "%.2f" % -offsets[key], "-i", apps[key]]
        # 1920x1080 녹화에서 앱의 왼쪽 '도로 화면' 부분만 (전체 2560x1440 기준 앱 영역 853,90 에서 폭 732 · 높이 1210)
        filters.append("[%d:v]crop=549:906:640:68,scale=%d:%d,fps=%d[v%d]" % (i, APP_W, APP_H, FPS, i))
        filters.append("%s[v%d]overlay=%d:%d:eof_action=repeat[o%d]" % (last, i, COL_X[key] + (COL_W - APP_W) // 2, APP_Y, i))
        last = "[o%d]" % i
    cmd += ["-filter_complex", ";".join(filters), "-map", last, "-t", "%.2f" % seconds,
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", out]
    subprocess.run(cmd, check=True)


# 에뮬레이터 화면(2560x1440)에서 앱의 '도로 화면'이 있는 자리 (x, y, 폭, 높이)
APP_RECT = (853, 90, 732, 1210)


def start_mirror(serial, title, left, top, height):
    """에뮬레이터 화면을 실시간으로 받아 창으로 보여 준다 (adb screenrecord → ffplay). 보기 전용이고 한 번에 3분까지"""
    k = 0.5   # 1280x720 으로 받아서 그중 도로 화면만 잘라 낸다
    x, y, w, h = (int(v * k) for v in APP_RECT)
    cmd = ('"%s" -s %s exec-out screenrecord --output-format=h264 --size 1280x720 --bit-rate 6000000 --time-limit 180 - | '
           'ffplay -loglevel error -f h264 -framerate 60 -probesize 32 -analyzeduration 0 -fflags nobuffer -flags low_delay '
           '-framedrop -sync video -vf "setpts=0,crop=%d:%d:%d:%d,scale=-2:%d" -window_title "%s" -left %d -top %d -'
           % (ADB, serial, w, h, x, y, height, title, left, top))
    return subprocess.Popen(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_process_tree(proc):
    if proc.poll() is None:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)


def live_view(name, args, server, work):
    """영상으로 만들지 않고, 지금 에뮬레이터에서 돌아가는 모습을 그대로 본다.
    창 1 = 실제 도로 상황 (이 스크립트가 지금 에뮬레이터 GPS 에 넣고 있는 위치 그대로)
    창 2·3 = 두 에뮬레이터의 앱 화면 (--no-mirror 로 끄고 Android Studio 의 Running Devices 에서 봐도 된다)"""
    import tkinter as tk
    from PIL import ImageTk

    root = tk.Tk()
    root.title("실제 도로 상황 · Car-ind CHAT 시연")
    view_h = min(H, root.winfo_screenheight() - 110)
    view_w = int(WORLD_W * view_h / H)
    root.geometry("%dx%d+10+10" % (view_w, view_h))
    root.configure(bg="#0b1220")
    label = tk.Label(root, bd=0, bg="#0b1220", fg="#c8d0de", font=("Malgun Gothic", 14), text="에뮬레이터 준비 중…")
    label.pack(fill="both", expand=True)
    state = {"sc": None, "renderer": None, "done": False}
    stop = threading.Event()
    mirrors = []

    def open_mirrors():
        for proc in mirrors:
            stop_process_tree(proc)
        mirrors.clear()
        if args.no_mirror:
            return
        app_w = int(APP_RECT[2] * view_h / APP_RECT[3])
        for i, (key, serial) in enumerate((("A", args.a), ("B", args.b))):
            mirrors.append(start_mirror(serial, "%s 차량의 Car-ind CHAT 화면 (실제 앱)" % key,
                                        10 + view_w + 16 + i * (app_w + 16), 10, view_h))

    def runner():
        while not stop.is_set():
            sc = SCENARIOS[name](args.a, args.b)
            sc.t0_wall = None
            events = []
            state["renderer"] = Renderer(sc, events, preview=False, world_only=True)
            state["sc"] = sc
            open_mirrors()
            try:
                run_live(sc, server, work, record=False, events=events, stop=stop, with_actions=not args.hands)
            except Exception as e:
                print("  ! 실행 중 오류:", e)
                break
            if not args.loop:
                break
            for _ in range(20):
                if stop.is_set():
                    break
                time.sleep(0.1)
        state["done"] = True

    def tick():
        sc, renderer = state["sc"], state["renderer"]
        t0 = getattr(sc, "t0_wall", None) if sc else None
        if t0:
            t = max(-PRE_ROLL, min(time.time() - t0, sc.duration + 1.0))
            img = renderer.frame(t)
            if view_h != H:
                img = img.resize((view_w, view_h), Image.BILINEAR)
            photo = ImageTk.PhotoImage(img)
            label.configure(image=photo, text="")
            label.image = photo
        root.after(30, tick)

    def close():
        stop.set()
        for proc in mirrors:
            stop_process_tree(proc)
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    threading.Thread(target=runner, daemon=True).start()
    root.after(30, tick)
    root.mainloop()
    stop.set()
    for proc in mirrors:
        stop_process_tree(proc)


def main():
    ap = argparse.ArgumentParser(description="Car-ind CHAT 주행 시나리오 시연")
    ap.add_argument("scenario", choices=sorted(SCENARIOS))
    ap.add_argument("--a", default="emulator-5554", help="A 차량 에뮬레이터 (앱 번호 99허 0000)")
    ap.add_argument("--b", default="emulator-5556", help="B 차량 에뮬레이터 (앱 번호 12가 3690)")
    ap.add_argument("--server", default="http://127.0.0.1:8090", help="Car-ind CHAT 서버 (에뮬레이터 앱은 10.0.2.2:같은 포트)")
    ap.add_argument("--preview", action="store_true", help="에뮬레이터 없이 왼쪽 상황 그림만 만든다")
    ap.add_argument("--no-record", action="store_true", help="에뮬레이터를 움직이기만 하고 영상은 만들지 않는다")
    ap.add_argument("--live", action="store_true", help="영상 대신 지금 돌아가는 모습을 창으로 본다")
    ap.add_argument("--loop", action="store_true", help="--live 에서 끝나면 처음부터 다시")
    ap.add_argument("--hands", action="store_true", help="--live 에서 메시지를 자동으로 보내지 않는다 (직접 차를 끌어서 보내 보기)")
    ap.add_argument("--no-mirror", action="store_true", help="--live 에서 앱 화면 창을 따로 띄우지 않는다")
    args = ap.parse_args()

    sc = SCENARIOS[args.scenario](args.a, args.b)
    work = os.path.join(ROOT, "build", "scenario", sc.name)
    os.makedirs(work, exist_ok=True)
    out_dir = os.path.join(ROOT, "docs", "demo")
    os.makedirs(out_dir, exist_ok=True)
    bg = os.path.join(work, "background.mp4")

    if args.preview:
        events = [(t + 1.9, a, b, RADIAL_MESSAGES[i], "미리 보기") for t, a, b, i in sc.actions]
        render_background(sc, events, bg, preview=True)
        out = os.path.join(out_dir, "scenario_%s_preview.mp4" % sc.name)
        shutil.copyfile(bg, out)
        print("완료:", out)
        return

    server = Server(args.server)
    try:
        server.call("GET", "/health")
    except Exception:
        if not args.live:
            raise
        # 창으로 볼 때는 서버가 꺼져 있으면 이 PC 에서 바로 켠다 (창을 닫아도 서버는 남는다)
        port = args.server.rsplit(":", 1)[-1].strip("/")
        print("서버가 꺼져 있어서 켭니다 (포트 %s)" % port)
        subprocess.Popen([sys.executable, os.path.join(ROOT, "server", "roadtalk_server.py"), port],
                         creationflags=getattr(subprocess, "CREATE_NEW_CONSOLE", 0))
        time.sleep(2.0)
        server.call("GET", "/health")
    if args.live:
        live_view(args.scenario, args, server, work)
        return
    events, offsets = run_live(sc, server, work, record=not args.no_record)
    if args.no_record:
        return
    seconds = render_background(sc, events, bg, preview=False)
    out = os.path.join(out_dir, "scenario_%s.mp4" % sc.name)
    compose(bg, {k: os.path.join(work, "app_%s.mp4" % k) for k in ("A", "B")}, offsets, out, seconds)
    print("완료:", out)


if __name__ == "__main__":
    main()
