"""변환이 맞게 됐는지 눈으로 보기 위한 간단한 미리보기 (PIL 로 삼각형을 뒤에서부터 칠한다).

render(items, eye, target, size) : items = [(positions, normals, uvs, palette_image, uv_top_left)] 을 원근 투영해 Image 로.
"""
import math

from PIL import Image, ImageDraw

LIGHT = (0.35, 0.85, 0.4)      # 빛이 오는 방향 (정규화 전)


def _norm(v):
    length = math.sqrt(sum(a * a for a in v)) or 1.0
    return tuple(a / length for a in v)


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def sample(palette, uv, top_left):
    w, h = palette.size
    u = min(max(uv[0], 0.0), 0.9999)
    v = min(max(uv[1], 0.0), 0.9999)
    if not top_left:
        v = 0.9999 - v
    return palette.getpixel((int(u * w), int(v * h)))[:3]


def render(items, eye, target, size=(900, 600), fov_deg=45.0, background=(236, 238, 242), fog=None):
    w, h = size
    forward = _norm(_sub(target, eye))
    right = _norm(_cross(forward, (0, 1, 0)))
    up = _cross(right, forward)
    f = (h / 2) / math.tan(math.radians(fov_deg) / 2)
    light = _norm(LIGHT)
    tris = []
    for positions, normals, uvs, palette, top_left in items:
        for t in range(0, len(positions), 3):
            pts, depth, ok = [], 0.0, True
            for k in range(3):
                d = _sub(positions[t + k], eye)
                z = _dot(d, forward)
                if z < 0.3:
                    ok = False
                    break
                pts.append((w / 2 + _dot(d, right) / z * f, h / 2 - _dot(d, up) / z * f))
                depth += z
            if not ok:
                continue
            n = normals[t]
            shade = 0.55 + 0.45 * max(0.0, _dot(n, light))
            color = sample(palette, uvs[t], top_left)
            color = tuple(int(c * shade) for c in color)
            if fog:
                near, far = fog
                k = min(max((depth / 3 - near) / (far - near), 0.0), 1.0)
                color = tuple(int(c + (b - c) * k) for c, b in zip(color, background))
            tris.append((depth / 3, pts, color))
    tris.sort(key=lambda t: -t[0])
    im = Image.new("RGB", size, background)
    draw = ImageDraw.Draw(im)
    for _, pts, color in tris:
        draw.polygon(pts, fill=color)
    return im
