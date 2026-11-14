"""받아 둔 3D 에셋을 앱이 바로 읽는 형식으로 바꾼다.

사용법:
    python tools/assets/convert_assets.py <에셋을 풀어 둔 폴더> [--preview 미리보기.png]

<폴더> 안에 있어야 하는 것 (README 의 '3D 에셋' 참고):
    cars/Models/*.fbx, cars/Textures/Palette_*.png      ← Broken Vector "Low Poly Cars"
    road/GLB/SM_Road_Straight.glb 등, road/Textures/T_Palette.png   ← GGiedrius "Low Poly City & Traffic Pack: FREE Lite"

결과: app/src/main/assets/scene3d/
    car_<n>.mesh        차 10종 (0~5 승용차, 6 경찰차, 7 버스, 8·9 트럭)
    skin_<n>.png        차 색 6종 (0 파랑, 1 빨강, 2 노랑, 3 초록, 4 보라, 5 은색)
    road_left/dash/plain/right.mesh   도로 10m 토막의 부품 (Road Straight 를 차선 폭 3.5m 로 넓혀 나눈 것. 앱이 차로 수에 맞춰 이어 붙인다)
    signal.mesh         신호등 (불이 꺼진 모습)
    tree_leaves.mesh, tree_trunk.mesh (나무: 잎은 앱이 계절 색으로 칠한다), lamp.mesh, city.png (도로·나무·가로등 색)

메시 파일: "RTM1", 정점 수(uint32), 정점마다 float32 8개 (x, y, z, nx, ny, nz, u, v). 삼각형 3개씩, 인덱스 없음.
좌표: 미터, Y 가 위, 차 앞쪽 = -Z, 오른쪽 = +X, 바닥 = y 0. UV 원점은 그림의 왼쪽 위.
"""
import os
import shutil
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fbx_reader
import glb_reader

CARS = ["Car 1", "Car 2", "Car 3", "Car 4", "Car 5", "Car 6", "Policecar", "Bus", "Truck 1", "Truck 2"]
SKINS = ["Blue", "Red", "Yellow", "Green", "Purple", "Silver"]
LANE_M = 3.5                 # 앱이 쓰는 차선 폭
TILE_LANE = 3.15             # 원본 도로 조각의 차선 폭 (가장자리 선 ~ 가운데 점선)
ROAD_Y = 0.1                 # 원본 도로 조각의 아스팔트 높이 → 0 으로 내린다
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "scene3d")


def write_mesh(path, verts):
    with open(path, "wb") as f:
        f.write(b"RTM1")
        f.write(struct.pack("<I", len(verts)))
        for v in verts:
            f.write(struct.pack("<8f", *v))


def convert_car(src, tail_color_of):
    """FBX 차 → 가운데 정렬, 미터, 앞쪽이 -Z. tail_color_of(uv) 가 True 면 그 면은 빨간 후미등"""
    meshes = fbx_reader.load(src)
    pts = [p for m in meshes for p in m.positions]
    cx = (min(p[0] for p in pts) + max(p[0] for p in pts)) / 2
    cz = (min(p[2] for p in pts) + max(p[2] for p in pts)) / 2
    by = min(p[1] for p in pts)
    # 차 길이 방향은 FBX 의 X. 후미등(빨강)이 있는 쪽이 뒤
    tail_x = [p[0] - cx for m in meshes for p, uv in zip(m.positions, m.uvs) if tail_color_of(uv)]
    rear_sign = 1.0 if (sum(tail_x) / len(tail_x) if tail_x else 1.0) > 0 else -1.0
    verts = []
    for m in meshes:
        for p, n, uv in zip(m.positions, m.normals, m.uvs):
            lx, ly, lz = (p[0] - cx) * 0.01, (p[1] - by) * 0.01, (p[2] - cz) * 0.01
            # FBX X(길이) → 앱 Z (뒤가 +Z), FBX Z(폭) → 앱 X. 회전만 하도록 (거울상이 되지 않게) 부호를 맞춘다
            x, z = -lz * rear_sign, lx * rear_sign
            nx, nz = -n[2] * rear_sign, n[0] * rear_sign
            verts.append((x, ly, z, nx, n[1], nz, uv[0], 1.0 - uv[1]))
    return verts


def _clip(tri, axis_value, keep_greater):
    """삼각형(정점 3개, 각 8개 값)을 x = axis_value 평면으로 자른다. 남는 다각형을 삼각형들로 돌려준다"""
    def inside(v):
        return v[0] >= axis_value if keep_greater else v[0] <= axis_value
    out = []
    for i in range(3):
        a, b = tri[i], tri[(i + 1) % 3]
        if inside(a):
            out.append(a)
        if inside(a) != inside(b):
            t = (axis_value - a[0]) / (b[0] - a[0])
            out.append(tuple(a[k] + (b[k] - a[k]) * t for k in range(8)))
    return [(out[0], out[i], out[i + 1]) for i in range(1, len(out) - 1)]


def _slice(verts, x_min, x_max, shift):
    out = []
    for t in range(0, len(verts), 3):
        for tri in _clip(verts[t:t + 3], x_min, True):
            for tri2 in _clip(tri, x_max, False):
                out.extend((v[0] + shift,) + tuple(v[1:]) for v in tri2)
    return out


def convert_road(src):
    """2차선 Road Straight 를 차로 수에 상관없이 이어 붙일 수 있는 부품 네 개로 나눈다 (차선 폭 3.5m 로 넓혀서).
        left   왼쪽 인도 + 가장자리 선 + 첫 차로의 왼쪽 절반   (x = 0 이 첫 차로의 가운데)
        dash   차로와 차로 사이: 양쪽 차로의 절반씩 + 가운데 흰 점선   (x = 0 이 차로 경계)
        plain  dash 에서 점선만 뺀 것 (중앙선 자리. 노란 줄은 앱이 그린다)
        right  마지막 차로의 오른쪽 절반 + 가장자리 선 + 오른쪽 인도   (x = 0 이 마지막 차로의 가운데)
    앱은 차로 수 N 에 맞춰 left, (dash 또는 plain) × (N−1), right 를 나란히 놓는다."""
    verts = [(p[0], p[1] - ROAD_Y, p[2], n[0], n[1], n[2], uv[0], uv[1])
             for m in glb_reader.load(src) for p, n, uv in zip(m.positions, m.normals, m.uvs)]
    half = TILE_LANE / 2
    k = LANE_M / TILE_LANE

    def widen(part):
        return [(v[0] * k,) + tuple(v[1:]) for v in part]

    dash = _slice(verts, -half, half, 0.0)
    plain = []
    for t in range(0, len(dash), 3):
        tri = dash[t:t + 3]
        if all(v[1] > 0.005 and abs(v[0]) < 0.2 for v in tri):      # 가운데 점선 (아스팔트보다 살짝 위에 놓인 면)
            continue
        plain.extend(tri)
    return {"left": widen(_slice(verts, -99, -half, half)), "dash": widen(dash), "plain": widen(plain), "right": widen(_slice(verts, half, 99, -half))}


def convert_traffic_light(src):
    """신호등. 지도에는 위치만 있고 지금 무슨 색인지는 없으므로, 불빛 면을 몸통과 같은 어두운 색으로 바꿔 '꺼진' 모습으로 만든다"""
    meshes = glb_reader.load(src)
    body_uv = next(m.uvs[0] for m in meshes if "Emissive" not in m.material)
    out = []
    for m in meshes:
        for p, n, uv in zip(m.positions, m.normals, m.uvs):
            u, v = body_uv if "Emissive" in m.material else uv
            out.append((p[0], p[1], p[2], n[0], n[1], n[2], u, v))
    return out


def convert_prop(src):
    return [(p[0], p[1], p[2], n[0], n[1], n[2], uv[0], uv[1])
            for m in glb_reader.load(src) for p, n, uv in zip(m.positions, m.normals, m.uvs)]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 1
    base = args[0]
    preview_path = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    os.makedirs(OUT, exist_ok=True)

    from PIL import Image
    blue = Image.open(os.path.join(base, "cars", "Textures", "Palette_Blue.png")).convert("RGB")

    def is_tail(uv):
        w, h = blue.size
        r, g, b = blue.getpixel((min(int(uv[0] * w), w - 1), min(int((1 - uv[1]) * h), h - 1)))
        return r > 180 and g < 90 and b < 90

    cars = []
    for i, name in enumerate(CARS):
        verts = convert_car(os.path.join(base, "cars", "Models", name + ".fbx"), is_tail)
        write_mesh(os.path.join(OUT, "car_%d.mesh" % i), verts)
        xs = [v[0] for v in verts]
        zs = [v[2] for v in verts]
        ys = [v[1] for v in verts]
        print("car_%d  %-10s 삼각형 %4d  폭 %.2fm 길이 %.2fm 높이 %.2fm" % (i, name, len(verts) // 3, max(xs) - min(xs), max(zs) - min(zs), max(ys)))
        cars.append(verts)
    for i, name in enumerate(SKINS):
        shutil.copyfile(os.path.join(base, "cars", "Textures", "Palette_%s.png" % name), os.path.join(OUT, "skin_%d.png" % i))
    parts = convert_road(os.path.join(base, "road", "GLB", "SM_Road_Straight.glb"))
    for name, verts in parts.items():
        write_mesh(os.path.join(OUT, "road_%s.mesh" % name), verts)
    if os.path.exists(os.path.join(OUT, "road.mesh")):
        os.remove(os.path.join(OUT, "road.mesh"))
    # 미리보기용 3차선
    road = ([(v[0] - LANE_M,) + tuple(v[1:]) for v in parts["left"]] + [(v[0] - LANE_M / 2,) + tuple(v[1:]) for v in parts["dash"]]
            + [(v[0] + LANE_M / 2,) + tuple(v[1:]) for v in parts["dash"]] + [(v[0] + LANE_M,) + tuple(v[1:]) for v in parts["right"]])
    write_mesh(os.path.join(OUT, "signal.mesh"), convert_traffic_light(os.path.join(base, "road", "GLB", "SM_Traffic_Light.glb")))
    tree = convert_prop(os.path.join(base, "road", "GLB", "SM_Tree_Street.glb"))
    # 잎(팔레트에서 초록인 면)과 줄기를 나눠 둔다: 앱이 계절에 따라 잎 색만 바꾼다
    city_palette = Image.open(os.path.join(base, "road", "Textures", "T_Palette.png")).convert("RGB")

    def is_leaf(v):
        w, h = city_palette.size
        r, g, b = city_palette.getpixel((min(int(v[6] * w), w - 1), min(int(v[7] * h), h - 1)))
        return g > r * 1.12 and g > b * 1.12

    leaves = [v for t in range(0, len(tree), 3) if is_leaf(tree[t]) for v in tree[t:t + 3]]
    trunk = [v for t in range(0, len(tree), 3) if not is_leaf(tree[t]) for v in tree[t:t + 3]]
    write_mesh(os.path.join(OUT, "tree_leaves.mesh"), leaves)
    write_mesh(os.path.join(OUT, "tree_trunk.mesh"), trunk)
    if os.path.exists(os.path.join(OUT, "tree.mesh")):
        os.remove(os.path.join(OUT, "tree.mesh"))
    print("tree    잎 삼각형 %d, 줄기 %d" % (len(leaves) // 3, len(trunk) // 3))
    lamp = convert_prop(os.path.join(base, "road", "GLB", "SM_Street_Lamp.glb"))
    write_mesh(os.path.join(OUT, "lamp.mesh"), lamp)
    shutil.copyfile(os.path.join(base, "road", "Textures", "T_Palette.png"), os.path.join(OUT, "city.png"))
    xs = [v[0] for v in road]
    print("road    부품 %s  (3차선으로 이으면 폭 %.2f ~ %.2f m)" % ({k: len(v) // 3 for k, v in parts.items()}, min(xs), max(xs)))
    print("→", os.path.normpath(OUT))

    if preview_path:
        import preview
        city = Image.open(os.path.join(base, "road", "Textures", "T_Palette.png")).convert("RGB")
        skins = [Image.open(os.path.join(base, "cars", "Textures", "Palette_%s.png" % n)).convert("RGB") for n in SKINS]

        def place(verts, x, z, palette, flip=False):
            s = -1.0 if flip else 1.0
            pos = [(v[0] * s + x, v[1], v[2] * s + z) for v in verts]
            nor = [(v[3] * s, v[4], v[5] * s) for v in verts]
            return (pos, nor, [(v[6], v[7]) for v in verts], palette, True)

        items = []
        for k in range(-1, 9):
            items.append(place(road, 0, -k * 10.0, city))
            items.append(place(tree, -6.6, -k * 10.0 - 5, city))
            items.append(place(tree, 6.6, -k * 10.0, city))
            if k % 2 == 0:
                items.append(place(lamp, -6.0, -k * 10.0, city))
                items.append(place(lamp, 6.0, -k * 10.0 - 10, city, flip=True))
        items.append(place(cars[0], 0, 0, skins[1]))            # 내 차 (빨강)
        items.append(place(cars[1], -3.5, -9, skins[2]))        # 왼쪽 앞 (노랑)
        items.append(place(cars[4], 3.5, -8, skins[5]))         # 오른쪽 앞 (은색)
        items.append(place(cars[2], 0, 8, skins[0]))            # 뒤 (파랑)
        items.append(place(cars[3], 0, -22, skins[3]))          # 앞 멀리 (초록)
        im = preview.render(items, (0.0, 10.5, 13.5), (0.0, 0.0, -6.0), (640, 900), 46, background=(236, 238, 242), fog=(28, 85))
        im.save(preview_path)
        print("미리보기:", preview_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
