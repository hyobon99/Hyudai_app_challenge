"""GLB (glTF 2.0 binary) 를 읽어 삼각형 메시로 바꾼다. 표준 라이브러리만 쓴다.

load(path) → [Mesh(name, positions, normals, uvs, material)]  (노드 변환 적용, 삼각형 3개씩, 인덱스 없음)
좌표는 glTF 그대로 (m, Y-up, UV 원점 = 왼쪽 위).
"""
import json
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fbx_reader import Mesh, _apply, _apply_dir, _ident, _mul

_COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
_WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _read(path):
    data = open(path, "rb").read()
    magic, _version, _length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise ValueError("GLB 가 아니에요: %s" % path)
    pos, doc, blob = 12, None, b""
    while pos < len(data):
        clen, ctype = struct.unpack_from("<II", data, pos)
        chunk = data[pos + 8:pos + 8 + clen]
        if ctype == 0x4E4F534A:
            doc = json.loads(chunk.decode("utf-8"))
        elif ctype == 0x004E4942:
            blob = chunk
        pos += 8 + clen
    return doc, blob


def _accessor(doc, blob, index):
    acc = doc["accessors"][index]
    view = doc["bufferViews"][acc["bufferView"]]
    fmt, size = _COMPONENT[acc["componentType"]]
    width = _WIDTH[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride") or size * width
    out = []
    for i in range(acc["count"]):
        values = struct.unpack_from("<%d%s" % (width, fmt), blob, start + i * stride)
        out.append(values[0] if width == 1 else values)
    return out


def _node_matrix(node):
    if "matrix" in node:
        m = node["matrix"]          # 열 우선 → 행 우선
        return [[m[c * 4 + r] for c in range(4)] for r in range(4)]
    t = node.get("translation", [0, 0, 0])
    q = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    x, y, z, w = q
    r = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
         [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
         [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    m = _ident()
    for i in range(3):
        for j in range(3):
            m[i][j] = r[i][j] * s[j]
        m[i][3] = t[i]
    return m


def load(path):
    doc, blob = _read(path)
    out = []

    def visit(index, parent):
        node = doc["nodes"][index]
        m = _mul(parent, _node_matrix(node))
        if "mesh" in node:
            mesh = doc["meshes"][node["mesh"]]
            for prim in mesh["primitives"]:
                if prim.get("mode", 4) != 4:
                    continue
                attrs = prim["attributes"]
                pos = _accessor(doc, blob, attrs["POSITION"])
                nor = _accessor(doc, blob, attrs["NORMAL"]) if "NORMAL" in attrs else None
                uv = _accessor(doc, blob, attrs["TEXCOORD_0"]) if "TEXCOORD_0" in attrs else None
                idx = _accessor(doc, blob, prim["indices"]) if "indices" in prim else list(range(len(pos)))
                material = doc["materials"][prim["material"]].get("name", "") if "material" in prim else ""
                p_out, n_out, u_out = [], [], []
                for i in idx:
                    p_out.append(_apply(m, pos[i]))
                    if nor:
                        n = _apply_dir(m, nor[i])
                        length = math.sqrt(sum(a * a for a in n)) or 1.0
                        n_out.append(tuple(a / length for a in n))
                    else:
                        n_out.append((0.0, 1.0, 0.0))
                    u_out.append(tuple(uv[i]) if uv else (0.0, 0.0))
                out.append(Mesh(node.get("name", mesh.get("name", "")), p_out, n_out, u_out, material))
        for child in node.get("children", []):
            visit(child, m)

    scene = doc["scenes"][doc.get("scene", 0)]
    for root in scene["nodes"]:
        visit(root, _ident())
    return out


if __name__ == "__main__":
    import sys
    for path in sys.argv[1:]:
        print(path)
        for mesh in load(path):
            xs = [p[0] for p in mesh.positions]
            ys = [p[1] for p in mesh.positions]
            zs = [p[2] for p in mesh.positions]
            print("  %-26s mat=%-20s tris=%-5d x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]" % (
                mesh.name, mesh.material, len(mesh.positions) // 3, min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))
