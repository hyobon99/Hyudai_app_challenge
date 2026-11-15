"""
시연 영상용 장면 촬영: 에뮬레이터 두 대의 화면을 2560x1440 으로 녹화하면서 장면마다 정해진 동작을 시킨다.

    python -X utf8 tools/scenario/record_shots.py <저장할 폴더> R1 R2 R4 R5 R6 R7 R3

 - 서버(server/roadtalk_server.py)와 에뮬레이터 두 대가 켜져 있고, 디버그 빌드의 앱이 떠 있어야 한다
 - 장면마다 <폴더>/raw/R?_A.webm · R?_B.webm 이 생긴다 (A = emulator-5554 · 99허 0000, B = emulator-5556 · 12가 3690).
   에뮬레이터 자체 녹화라 2560x1440 · 초당 30프레임이고, 에뮬레이터에서 나는 소리(TTS)도 담긴다.
   편집용 mp4 는 `record_shots.py <폴더> --convert` 로 만든다 (ffmpeg 필요)
 - 음성은 마이크 대신 앱에 넣어 둔 음성 파일을 STT SDK 에 넣는다 (stt_test). 인식 → AI 해석 → 전송은 실제와 같다
 - 화면을 누르고 끄는 동작은 adb input 으로 한다. 좌표는 2560x1440 화면 기준
"""
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_scenario as rs  # noqa: E402

A_DEV, B_DEV = "emulator-5554", "emulator-5556"
A_PLATE, B_PLATE = "99허 0000", "12가 3690"
FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
# 주행 속도 배율: 1 이면 큰길 시속 36km · 골목 16km. 3D 화면에서는 풍경이 빨리 지나가 급해 보여서 영상용으로는 낮춘다
PACE = float(os.environ.get("PACE", "0.6"))
# 앱의 도로 화면(왼쪽 패널)이 2560x1440 화면에서 차지하는 자리: 가로, 세로, 왼쪽, 위
ROAD_PANEL = (686, 1124, 872, 158)
# 앱 화면 전체(도로 화면 + 오른쪽 패널)가 차지하는 자리. 오른쪽 패널을 조작하는 장면은 이 범위로 자른다
APP_AREA = (1682, 1210, 853, 90)
# 변환해서 만드는 두 가지 영상: (폴더 이름, 자르는 자리)
VERSIONS = (("1_도로화면", ROAD_PANEL), ("2_도로화면+지도", APP_AREA))
# 골목 길가에 세워 둔 차: 길 가운데에서 떨어진 거리, 앞뒤 간격 (m)
PARKED_SIDE_M = 2.75
PARKED_EVERY_M = 6.2
# 골목(시나리오 yield 의 길)에 옆길이 만나는 자리 (도로 방향 거리 m)
SIDE_STREETS_AT = (0.0, 157.9, 289.8)
# R3 에서 두 차가 마주 선 채로 기다릴 수 있는 시간 (초). 음성 인식과 답장이 끝나면 남은 시간은 건너뛴다
R3_WAIT = 20.0
MERGE_EARLIER_M = 30.0
# 차가 달리는 장면, 그 장면의 가상 차가 서버에서 지워지기까지 걸리는 시간 (초)
DRIVING_SHOTS = ("R1", "R2", "R3")
VIRTUAL_GONE_SECONDS = 34.0

# 화면 좌표 (2560x1440)
CAR_BUTTON = (1504, 246)          # 도로 화면 오른쪽 위: 내 차 고르기
BUBBLE_BUTTON = (1504, 304)       # 도로 화면 오른쪽 위: 메시지 함
LIST_X = 1792                     # 메시지 목록의 줄
ROW_FUEL_Y = 684                  # '주유구가 열려 있어요'
SLOT_TOP = (2256, 410)            # 기본 메시지 함의 위쪽 칸 (끌 때 위 방향)
PANEL_CLOSE = (2448, 200)
GARAGE_ROADSTER = (2416, 566)
GARAGE_VAN = (2140, 370)
GARAGE_YELLOW = (1746, 1120)
GARAGE_GREEN = (1810, 1120)
GARAGE_APPLY = (2396, 1122)
GARAGE_CLOSE = (2420, 200)


def adb(dev, *args, timeout=40):
    return subprocess.run([rs.ADB, "-s", dev, *args], capture_output=True, timeout=timeout)


def broadcast(dev, *extras):
    adb(dev, "shell", "am", "broadcast", "--user", "10", "-a", "com.roadtalk.app.STT_TEST", *extras)


def clip(dev, name):
    """앱에 넣어 둔 음성 파일을 STT SDK 로 인식시킨다"""
    broadcast(dev, "--es", "stt_test", name)


def tap(dev, at):
    adb(dev, "shell", "input", "tap", str(at[0]), str(at[1]))


def swipe(dev, start, end, millis):
    adb(dev, "shell", "input", "swipe", str(start[0]), str(start[1]), str(end[0]), str(end[1]), str(millis))


def car_on_screen(dev, plate, tries=6):
    """그 화면에서 상대 차가 그려진 자리"""
    for _ in range(tries):
        found = rs.on_screen_position(dev, plate)
        if found:
            return found
        time.sleep(0.4)
    raise RuntimeError("%s 화면에서 %s 차를 못 찾음" % (dev, plate))


class Recording:
    """
    두 에뮬레이터 화면을 같이 녹화한다. 에뮬레이터 자체의 녹화 기능(콘솔의 screenrecord)을 쓴다:
    화면 크기 그대로(2560x1440) 초당 30프레임으로, 에뮬레이터에서 나는 소리(TTS · 알림음)까지 webm 파일에 담긴다.
    (adb shell screenrecord 는 이 크기에서 초당 7프레임쯤밖에 안 나오고 소리도 없다)
    """

    def __init__(self, out_dir, name, devices=(("A", A_DEV), ("B", B_DEV))):
        # 두 대를 한꺼번에 녹화하면 PC 가 버거워 주행 장면에서 음성 인식이 10초 넘게 늦어진다.
        # 환경 변수 REC=A 또는 REC=B 로 한 대만 녹화할 수 있다 (같은 장면을 두 번 돌려 한 대씩 찍는다)
        only = os.environ.get("REC", "AB")
        devices = tuple(d for d in devices if d[0] in only)
        self.raw = os.path.join(out_dir, "raw")
        os.makedirs(self.raw, exist_ok=True)
        self.name = name
        self.devices = devices
        self.files = {}
        for key, dev in devices:
            local = os.path.join(self.raw, "%s_%s.webm" % (name, key))
            if os.path.exists(local):
                os.remove(local)
            self.files[key] = local
            adb(dev, "emu", "screenrecord", "start", "--time-limit", "170", "--fps", "30", "--bit-rate", "20000000", local)

    def stop(self):
        for _, dev in self.devices:
            adb(dev, "emu", "screenrecord", "stop")
        # 파일이 다 써질 때까지 (크기가 더 늘지 않을 때까지) 기다린다
        deadline = time.time() + 30
        last = None
        while time.time() < deadline:
            time.sleep(1.0)
            sizes = [os.path.getsize(f) if os.path.exists(f) else 0 for f in self.files.values()]
            if sizes == last and all(sizes):
                break
            last = sizes
        for local in self.files.values():
            print("  저장: %s (%.1f MB)" % (local, os.path.getsize(local) / 1e6 if os.path.exists(local) else 0))


class Drive:
    """시나리오의 차 위치를 에뮬레이터 GPS 와 서버(가상 차)에 계속 넣어 준다. now() = 시나리오 시각(초)"""

    def __init__(self, sc, server, start_at):
        self.sc = sc
        self.server = server
        self.t0 = time.time() - start_at
        self.on = threading.Event()
        self.on.set()
        self.emus = [c for c in sc.cars if c.kind == "emulator"]
        self.virtuals = [c for c in sc.cars if c.kind == "virtual"]
        self.consoles = {c.key: rs.EmulatorConsole(c.device) for c in self.emus}
        self.threads = [threading.Thread(target=self._gps, daemon=True), threading.Thread(target=self._virtual, daemon=True)]
        for th in self.threads:
            th.start()

    def now(self):
        return time.time() - self.t0

    def _gps(self):
        while self.on.is_set():
            started = time.time()
            t = self.now() + rs.GPS_FEED_SECONDS / 2
            for c in self.emus:
                s, d, _, _, _ = c.state(t)
                lat, lng = self.sc.road.to_latlng(s, d)
                self.consoles[c.key].geo_fix(lat, lng)
            time.sleep(max(0.01, rs.GPS_FEED_SECONDS - (time.time() - started)))

    def _virtual(self):
        turn = 0
        while self.on.is_set():
            t = self.now()
            turn += 1
            for c in self.virtuals:
                # 길가에 세워 둔 차는 움직이지 않으니 5초에 한 번만 보고한다 (서버는 30초 동안 기억한다)
                if getattr(c, "parked", False) and turn % 10 != 1:
                    continue
                s, d, v, course, _ = c.state(t)
                lat, lng = self.sc.road.to_latlng(s, d)
                heading = (rs.math.degrees(self.sc.road.bearing + course) + 360) % 360
                try:
                    self.server.presence(c.plate, lat, lng, heading, v)
                except Exception as e:
                    print("  ! 가상 차 위치 보고 실패:", e)
            time.sleep(0.5)

    def jump_to(self, t):
        """시나리오 시각을 t 로 옮긴다. 차가 모두 서 있는 구간 안에서만 쓴다 (기다리는 시간을 건너뛰어도 화면에는 티가 나지 않는다)"""
        self.t0 = time.time() - t

    def wait_until(self, t):
        while self.now() < t:
            time.sleep(0.02)

    def stop(self):
        self.on.clear()
        for th in self.threads:
            th.join(timeout=2)
        for console in self.consoles.values():
            try:
                console.sock.close()
            except OSError:
                pass


def run_timeline(drive, actions, end_at):
    for at, what, fn in sorted(actions, key=lambda a: a[0]):
        drive.wait_until(at)
        print("  %5.1fs  %s" % (drive.now(), what))
        try:
            fn()
        except Exception as e:
            print("  ! 실패:", e)
    drive.wait_until(end_at)


def wait_recognised(dev, timeout):
    """그 기기에서 음성 인식 결과가 나올 때까지 기다린다 (기다리기 전에 logcat 을 비워 둘 것). 걸린 시간(초)을 돌려준다"""
    started = time.time()
    while time.time() - started < timeout:
        time.sleep(0.5)
        if "STT final" in adb(dev, "logcat", "-d", "-s", "RoadTalkVoice").stdout.decode("utf-8", "replace"):
            break
    return time.time() - started


def warm_up(rounds=2):
    """
    앱을 새로 띄운 뒤 처음 한두 번은 음성 인식이 20초 넘게 걸린다. 촬영 전에 두 에뮬레이터에서 미리 두 번씩 인식시켜 둔다.
    (보낼 수 없는 말을 넣는다 → '전송에 실패했습니다' 안내까지 끝난 뒤에 촬영을 시작한다)
    """
    for n in range(rounds):
        for dev in (A_DEV, B_DEV):
            adb(dev, "logcat", "-c")
            clip(dev, "unrelated")
        started = time.time()
        waiting = {A_DEV, B_DEV}
        while waiting and time.time() - started < 70:
            time.sleep(1.0)
            for dev in list(waiting):
                log = adb(dev, "logcat", "-d", "-s", "RoadTalkVoice").stdout.decode("utf-8", "replace")
                if "STT final" in log:
                    waiting.discard(dev)
        print("  음성 인식 예열 %d: %.0f초%s" % (n + 1, time.time() - started, " (끝나지 않은 기기 있음)" if waiting else ""))
        time.sleep(8.0)      # 실패 안내 음성과 상자가 사라질 때까지


def prepare():
    for key, dev in (("A", A_DEV), ("B", B_DEV)):
        adb(dev, "shell", "am", "start", "--user", "10", "-n", "com.roadtalk.app/.MainActivity")
        broadcast(dev, "--ez", "garage", "false", "--ez", "messages", "false")
        adb(dev, "logcat", "-c")


# ------------------------------------------------------------------ 장면

def merge_scenario():
    """큰길(테헤란로) 장면. 시나리오보다 30m 앞당겨 달려서, 두 차가 목적지로 꺾는 골목을 지나치지 않고 그 앞에서 끝나게 한다
    (지나치면 지도의 경로가 한 바퀴 돌아오는 길로 바뀐다)"""
    sc = rs.scenario_merge(A_DEV, B_DEV, pace=PACE)
    for car in sc.cars:
        car.s0 -= MERGE_EARLIER_M
    return sc


def shot_r1(out, server):
    """R1: A·B 가 같은 길을 달린다. 옆 차로의 차(B), 뒤에서 와서 왼쪽으로 지나가는 차(C)가 실시간으로 보인다"""
    sc = merge_scenario()
    a, b = sc.car("A"), sc.car("B")
    a.speed = rs.Line([(0, 10.0 * PACE), (60, 10.0 * PACE)])
    b.s0 = a.s0 + 11.0
    b.lateral = rs.Ease([(0, 1.75)])
    b.blink = []
    # 세 번째 차는 바로 왼쪽 차로에서 뒤에서 와서 두 차를 차례로 지나간다 (영상이 시작할 때 화면 아래쪽에 막 들어와 있다)
    c = sc.car("C")
    c.s0 = a.s0 - 1.9
    c.lateral = rs.Ease([(0, -5.25)])
    c.speed = rs.Line([(0, 10.0 * PACE * 1.15), (60, 10.0 * PACE * 1.15)])
    drive = Drive(sc, server, -11.0)
    drive.wait_until(-4.0)
    rec = Recording(out, "R1")
    run_timeline(drive, [], 22.0)
    rec.stop()
    drive.stop()


def shot_r2(out, server):
    """R2: 옆 차로의 차(B 에뮬레이터)가 앞으로 끼어들고 → 뒤차(A 에뮬레이터)가 속도를 줄여 양보 → 끼어든 차가 말로 감사 인사"""
    sc = merge_scenario()
    # 앱을 쓰는 세 번째 차는 뺀다 (이 장면에서는 줄곧 뒤쪽 화면 밖에 있어서, 화면 아래 끝에 걸쳐 그려지기만 한다)
    sc.cars = [c for c in sc.cars if c.key != "C"]
    cursor = server.cursor(A_PLATE)
    drive = Drive(sc, server, -11.0)
    drive.wait_until(-4.0)
    rec = Recording(out, "R2")
    run_timeline(drive, [
        (11.0, "끼어든 차(B)가 말한다: '뒷차한테 땡큐 보내줘' (음성 파일 → STT)", lambda: clip(B_DEV, "rear_thankyou")),
    ], 17.0)
    # 음성 인식에 걸리는 시간이 그때그때 다르다. 인사가 A 에 도착한 뒤 말풍선과 읽어 주는 소리가 끝날 때까지(6초) 찍는다.
    # 다만 두 차가 목적지로 꺾는 골목을 지나치기 전(28초)에는 끝낸다
    while drive.now() < 22.0:
        try:
            if server.arrived(A_PLATE, B_PLATE, cursor):
                break
        except Exception:
            pass
        time.sleep(0.2)
    print("  %5.1fs  B 의 인사 도착" % drive.now())
    drive.wait_until(min(28.0, max(23.0, drive.now() + 6.0)))
    rec.stop()
    drive.stop()


def parked_cars(road_from, road_to, pocket, seed=7):
    """
    골목 양쪽 길가에 세워 둔 차들 (서버에 위치만 보고하는 가상 차). 도로 방향 거리 road_from ~ road_to 사이에 줄지어 세우고,
    오른쪽 줄의 pocket = (시작, 끝) 구간은 비워 둔다 (마주 온 차를 보내 주려고 비켜 설 자리).
    """
    import random
    rnd = random.Random(seed)
    cars = []
    letters = "가나다라마거너더러머버서어저고노도로모보소오조구누두루무부수우주"
    for side, d in (("L", -PARKED_SIDE_M), ("R", PARKED_SIDE_M)):
        s = road_from + rnd.uniform(0.0, 2.0)
        while s < road_to:
            # 가끔 한 자리씩 비어 있다. 옆길이 만나는 곳에는 세우지 않는다
            if rnd.random() > 0.14 and not (side == "R" and pocket[0] <= s <= pocket[1]) and all(abs(s - x) > 7.0 for x in SIDE_STREETS_AT):
                plate = "%02d%s %04d" % (rnd.randint(10, 69), rnd.choice(letters), rnd.randint(1000, 9999))
                # 왼쪽 줄은 맞은편을 보고, 오른쪽 줄은 내 쪽과 같은 방향을 보고 서 있다. 줄이 살짝 들쭉날쭉하다
                car = rs.Car("P%s%d" % (side, len(cars)), plate, "virtual", s, rs.Line([(0, 0.0)]),
                             rs.Ease([(0, d + rnd.uniform(-0.08, 0.08))]), direction=-1 if side == "L" else 1)
                car.parked = True
                cars.append(car)
            s += PARKED_EVERY_M + rnd.uniform(0.0, 0.9)
    return cars


def shot_r3(out, server):
    """R3: 양쪽에 차가 주차된 골목에서 마주 서고 → A 가 말로 '먼저 가세요' → B 가 A 차를 눌러 끌어서 '감사합니다' → A 가 오른쪽 빈자리로 비켜 주고 B 가 먼저 지나간다"""
    sc = rs.scenario_yield(A_DEV, B_DEV, pace=PACE)
    # 음성 인식에 걸리는 시간이 그때그때 달라서(4~10초), 마주 서 있는 시간을 시나리오보다 6초 늘려 잡는다
    slow = 4.5 * PACE
    a, b = sc.car("A"), sc.car("B")
    a_stop = a.s0 + slow * 5 + slow * 3.5 / 2      # A 가 처음 멈추는 곳
    # A 는 B 의 답을 받은 뒤 오른쪽 빈자리로 비켜 서서, B 가 지나갈 때까지 기다린다 (영상은 B 가 지나간 데서 끝난다).
    # 3.6초에 걸쳐 2.5m 를 옆으로 옮기며 9m 쯤 나아간다 (너무 급하게 꺾으면 차가 길을 가로지르는 것처럼 보인다)
    # 마주 선 뒤의 움직임은 모두 R3_WAIT 만큼 늦춰 두고, B 의 답장이 끝나면 그 시간을 건너뛴다 (→ reply)
    h = R3_WAIT
    a.speed = rs.Line([(0, slow), (5, slow), (8.5, 0), (h + 21.5, 0), (h + 22.5, 2.4), (h + 24.9, 2.4), (h + 26.5, 0), (h + 60, 0)])
    a.lateral = rs.Ease([(0, 0), (h + 21.7, 0), (h + 25.3, 2.7), (h + 60, 2.7)])
    # B 는 A 앞 18m 에 마주 서 있다가, A 가 비켜 서면 길 가운데로 지나간다
    b.s0 = a_stop + 18.0 + (slow * 5 + slow * 3.5 / 2)
    b.speed = rs.Line([(0, slow), (5, slow), (8.5, 0), (h + 27, 0), (h + 29, slow * 0.78), (h + 60, slow * 0.78)])
    b.lateral = rs.Ease([(0, 0), (h + 27, 0), (h + 29, -0.1), (h + 33, -0.1), (h + 36, 0)])
    # 길 양쪽에 줄지어 세워 둔 차. A 가 멈추는 곳의 오른쪽만 비어 있다
    sc.cars += parked_cars(max(8.0, a.s0 - 50.0), min(sc.road.length - 8.0, b.s0 + 45.0), (a_stop - 4.5, a_stop + 14.5))
    cursor = server.cursor(B_PLATE)
    # 다른 곳에 있다가 이 골목으로 옮겨 오므로, 지도의 경로가 새 위치 · 방향에 맞춰질 때까지 넉넉히 미리 달린다
    drive = Drive(sc, server, -15.0)
    drive.wait_until(-4.0)
    rec = Recording(out, "R3")

    def reply():
        # A 의 '먼저 가세요' 가 B 에 도착한 것을 확인하고, 2초쯤 읽을 틈을 둔 뒤 답한다
        while drive.now() < h + 12.0:
            try:
                if server.arrived(B_PLATE, A_PLATE, cursor):
                    break
            except Exception:
                pass
            time.sleep(0.2)
        print("  %5.1fs  A 의 메시지 도착" % drive.now())
        time.sleep(3.0)
        x, y = car_on_screen(B_DEV, A_PLATE)
        swipe(B_DEV, (x, y), (x, y - 190), 1300)      # 위쪽 = 기본 메시지 함 1번 '감사합니다'
        # '감사합니다' 가 A 에 뜨고 읽히는 4.5초 뒤에 A 가 움직이기 시작하도록, 남은 기다림을 건너뛴다
        if drive.now() < h + 17.0:
            drive.jump_to(h + 17.0)
        print("  B 의 답장 전송 → 시각 %.1fs 로" % drive.now())

    run_timeline(drive, [
        (9.0, "A 가 말한다: '앞차한테 먼저 가시라고 전해줘' (음성 파일 → STT)", lambda: clip(A_DEV, "front_go_first")),
        (11.0, "B 가 메시지를 받으면 A 차를 누른 채 위로 끌어 '감사합니다' 전송", reply),
    ], h + 37.0)
    rec.stop()
    drive.stop()


def shot_r4(out, server):
    """R4: A 가 '아 쫌 빨리 가라' 라고 말한다 → '전송에 실패했습니다' → B 화면에는 아무 변화가 없다"""
    # 한동안 쓰지 않은 음성 인식은 다시 깨어나는 데 10초 넘게 걸릴 때가 있다. 찍기 직전에 한 번 인식시켜 깨워 둔다
    adb(A_DEV, "logcat", "-c")
    clip(A_DEV, "unrelated")
    print("  음성 인식 깨우기: %.0f초" % wait_recognised(A_DEV, 40.0))
    time.sleep(9.0)                      # 그 실패 안내(상자 5초 · 음성)가 끝날 때까지
    rec = Recording(out, "R4")
    time.sleep(5.0)
    print("  A 가 보낼 수 없는 말을 한다: '아 쫌 빨리 가라' (음성 파일 → STT)")
    adb(A_DEV, "logcat", "-c")
    clip(A_DEV, "rude_hurry")
    print("  인식까지 %.0f초" % wait_recognised(A_DEV, 30.0))
    time.sleep(9.0)                      # '전송에 실패했습니다' 상자(5초)와 안내 음성이 끝날 때까지
    rec.stop()


def shot_r5(out, server):
    """R5: 메시지 함을 열고 → 목록의 말을 칸에 담고 → 상대 차를 눌러 끌어서 그 말을 보낸다"""
    rec = Recording(out, "R5")
    time.sleep(5.0)
    print("  메시지 함 열기")
    tap(A_DEV, BUBBLE_BUTTON)
    time.sleep(2.5)
    print("  '주유구가 열려 있어요' 를 위쪽 칸으로 끌어다 놓기")
    swipe(A_DEV, (LIST_X, ROW_FUEL_Y), SLOT_TOP, 1600)
    time.sleep(2.0)
    x, y = car_on_screen(A_DEV, B_PLATE)
    print("  B 차를 누른 채 위로 끌어 그 말을 전송")
    swipe(A_DEV, (x, y), (x, y - 190), 1500)
    time.sleep(6.0)
    tap(A_DEV, PANEL_CLOSE)
    time.sleep(2.0)
    rec.stop()
    broadcast(A_DEV, "--ei", "slot", "0", "--es", "message", "감사합니다")      # 촬영 전 상태로


def shot_r6(out, server):
    """R6: 내 차 고르기 화면에서 차종과 색을 바꾼다 (상대 화면에도 그 모습으로 보인다)"""
    rec = Recording(out, "R6")
    time.sleep(5.0)
    print("  내 차 고르기 열기")
    tap(A_DEV, CAR_BUTTON)
    time.sleep(3.0)
    for what, at, wait in (("밴", GARAGE_VAN, 2.2), ("초록", GARAGE_GREEN, 2.2), ("스포츠카", GARAGE_ROADSTER, 2.2),
                           ("노랑", GARAGE_YELLOW, 2.2), ("이 차로 달리기", GARAGE_APPLY, 4.5)):
        print("  누르기:", what)
        tap(A_DEV, at)
        time.sleep(wait)
    rec.stop()
    time.sleep(1.0)
    broadcast(A_DEV, "--ez", "garage", "false", "--ei", "car", "0", "--ei", "skin", "1")      # 촬영 전 상태로


def shot_r7(out, server):
    """R7: 앱 메인 화면"""
    rec = Recording(out, "R7")
    time.sleep(14.0)
    rec.stop()


SHOTS = {"R1": shot_r1, "R2": shot_r2, "R3": shot_r3, "R4": shot_r4, "R5": shot_r5, "R6": shot_r6, "R7": shot_r7}


def convert(out):
    """
    편집용 mp4 를 만든다 (H.264 + AAC, 초당 30프레임, 세로 1440). 장면마다 두 가지:
     - <폴더>/1_도로화면/R?_A.mp4 : 앱 왼쪽의 도로 화면(Car-ind Map)만 자른 것
     - <폴더>/2_도로화면+지도/R?_A.mp4 : 앱 화면 전체 (도로 화면 + 오른쪽 지도)
    """
    raw = os.path.join(out, "raw")
    for name in sorted(os.listdir(raw)):
        if not name.endswith(".webm"):
            continue
        source = os.path.join(raw, name)
        for folder, (w, h, x, y) in VERSIONS:
            os.makedirs(os.path.join(out, folder), exist_ok=True)
            target = os.path.join(out, folder, name[:-5] + ".mp4")
            # 녹화를 시작한 직후 0.7초는 화면이 멈칫해서 잘라 낸다. 소리는 빈 구간을 무음으로 채워 끊김 없이 만든다
            done = subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-ss", "0.7", "-i", source,
                                   "-vf", "crop=%d:%d:%d:%d,scale=-2:1440:flags=lanczos,fps=30" % (w, h, x, y),
                                   "-af", "aresample=async=1:first_pts=0",
                                   "-c:v", "libx264", "-preset", "veryfast", "-crf", "17", "-pix_fmt", "yuv420p",
                                   "-c:a", "aac", "-b:a", "160k", target])
            print("  변환:", target, "실패" if done.returncode else "%.1f MB" % (os.path.getsize(target) / 1e6))


def main():
    out = os.path.abspath(sys.argv[1])
    names = sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    if names == ["--convert"]:
        convert(out)
        return
    server = rs.Server("http://127.0.0.1:8090")
    prepare()
    time.sleep(2.0)
    if os.environ.get("WARM", "1") != "0":
        warm_up()
    last_drive = 0.0
    for name in names:
        print("== %s: %s" % (name, SHOTS[name].__doc__.strip()))
        if name not in DRIVING_SHOTS and time.time() - last_drive < VIRTUAL_GONE_SECONDS:
            # 앞 장면의 가상 차(서버에만 있는 차)는 위치 보고가 끊기고 30초 뒤에 사라진다. 서 있는 장면 도중에 사라지지 않게 그때까지 기다린다
            time.sleep(VIRTUAL_GONE_SECONDS - (time.time() - last_drive))
        SHOTS[name](out, server)
        if name in DRIVING_SHOTS:
            last_drive = time.time()
        time.sleep(3.0)


if __name__ == "__main__":
    main()
