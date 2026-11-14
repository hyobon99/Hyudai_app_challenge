"""
앱 아이콘 만들기: 원본 그림 한 장(둥근 네모 모양, 바깥 귀퉁이는 흰색)에서 안드로이드 아이콘 파일들을 만든다.

    python tools/assets/make_icon.py 원본그림

만드는 것 (app/src/main/res):
  - mipmap-*/ic_launcher.webp        둥근 네모 (귀퉁이 투명)
  - mipmap-*/ic_launcher_round.webp  동그라미
  - mipmap-xxxhdpi/ic_launcher_art.webp  런처가 제 모양(동그라미·둥근 네모 등)으로 잘라 쓰는 그림.
    귀퉁이까지 배경색을 채우고 가장자리에 여유를 둬서, 어떤 모양으로 잘라도 차와 말풍선이 잘리지 않는다
원본 그림은 저장소에 넣지 않는다 (만들어진 파일만 들어간다).
"""
import os
import sys

import numpy as np
from PIL import Image

RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
WORK = 1024
# 원본의 둥근 귀퉁이 반지름 (그림 폭에 대한 비율). 원본 맨 윗줄에서 색이 시작되는 자리로 잰 값
CORNER = 0.22
# 런처가 잘라 쓰는 그림에서 원본이 차지하는 크기 (가운데 2/3 만 보이므로 여유를 둔다)
ART_SCALE = 0.80


def fill_corners(rgb):
    """귀퉁이 바깥(흰색)을 가장 가까운 안쪽 색으로 채운다 → 네모 전체가 배경색인 그림"""
    h, w, _ = rgb.shape
    r = CORNER * w
    inset = 6.0                      # 경계의 흰색이 섞인 줄을 피한다
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    cx = np.clip(xs, r, w - 1 - r)   # 가장 가까운 '귀퉁이 원의 중심' (가운데 영역에서는 자기 자신)
    cy = np.clip(ys, r, h - 1 - r)
    dx, dy = xs - cx, ys - cy
    dist = np.hypot(dx, dy)
    scale = np.where(dist > r - inset, (r - inset) / np.maximum(dist, 1e-6), 1.0)
    sx = np.clip(np.rint(cx + dx * scale), 0, w - 1).astype(int)
    sy = np.clip(np.rint(cy + dy * scale), 0, h - 1).astype(int)
    out = rgb[sy, sx]
    # 네 변의 맨 바깥 줄도 흰색이 섞여 있을 수 있어 안쪽 줄로 덮는다
    m = 4
    out[:m], out[-m:], out[:, :m], out[:, -m:] = out[m:m + 1], out[-m - 1:-m], out[:, m:m + 1], out[:, -m - 1:-m]
    return out


def rounded_mask(size, radius):
    """둥근 네모 모양 (안쪽 255, 바깥 0). 4배 크기로 그려 줄여서 가장자리를 매끈하게"""
    big = size * 4
    ys, xs = np.mgrid[0:big, 0:big].astype(np.float32)
    r = radius * 4
    cx = np.clip(xs, r, big - 1 - r)
    cy = np.clip(ys, r, big - 1 - r)
    inside = (np.hypot(xs - cx, ys - cy) <= r).astype(np.uint8) * 255
    return Image.fromarray(inside).resize((size, size), Image.LANCZOS)


def circle_mask(size):
    big = size * 4
    ys, xs = np.mgrid[0:big, 0:big].astype(np.float32)
    c = (big - 1) / 2
    inside = (np.hypot(xs - c, ys - c) <= big / 2).astype(np.uint8) * 255
    return Image.fromarray(inside).resize((size, size), Image.LANCZOS)


def main():
    source = Image.open(sys.argv[1]).convert("RGB").resize((WORK, WORK), Image.LANCZOS)
    full = Image.fromarray(fill_corners(np.asarray(source).copy()))

    for density, size in SIZES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        small = full.resize((size, size), Image.LANCZOS)
        square = small.copy()
        square.putalpha(rounded_mask(size, CORNER * size))
        square.save(os.path.join(folder, "ic_launcher.webp"), lossless=True)
        round_icon = small.copy()
        round_icon.putalpha(circle_mask(size))
        round_icon.save(os.path.join(folder, "ic_launcher_round.webp"), lossless=True)

    # 런처가 잘라 쓰는 그림: 108dp 중 가운데 72dp 만 보인다. xxxhdpi 에서 432px
    art_size = 432
    inner = int(round(art_size * ART_SCALE))
    pad = (art_size - inner) // 2
    middle = np.asarray(full.resize((inner, inner), Image.LANCZOS))
    art = np.pad(middle, ((pad, art_size - inner - pad), (pad, art_size - inner - pad), (0, 0)), mode="edge")
    Image.fromarray(art).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_art.webp"), quality=95)
    print("ok")


if __name__ == "__main__":
    main()
