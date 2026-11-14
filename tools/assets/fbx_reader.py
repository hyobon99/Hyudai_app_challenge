"""바이너리 FBX (7.x) 를 읽어 삼각형 메시로 바꾼다. 외부 라이브러리 없이 표준 라이브러리만 쓴다.

읽는 것: Geometry(정점·면·UV·법선), Model 의 위치/회전/크기(Lcl, PreRotation, Geometric*), 연결(Connections).
결과: load(path) → [Mesh(name, positions[(x,y,z)], normals[(x,y,z)], uvs[(u,v)], material)], 정점은 면마다 따로 (인덱스 없음, 삼각형 3개씩).
좌표는 FBX 파일의 단위·축 그대로 (cm, Y-up 등) 이고, 호출하는 쪽에서 맞춘다. global_settings(path) 로 축·단위를 볼 수 있다.
"""
import math
import struct
import zlib


class Node:
    __slots__ = ("name", "props", "children")

    def __init__(self, name, props, children):
        self.name, self.props, self.children = name, props, children

    def find(self, name):
        for c in self.children:
            if c.name == name:
                return c
        return None

    def all(self, name):
        return [c for c in self.children if c.name == name]


def _read_array(data, pos, fmt, size):
    length, encoding, clen = struct.unpack_from("<III", data, pos)
    pos += 12
    raw = data[pos:pos + clen]
    if encoding == 1:
        raw = zlib.decompress(raw)
    return list(struct.unpack("<%d%s" % (length, fmt), raw[:length * size])), pos + clen


def _read_prop(data, pos):
    t = chr(data[pos])
    pos += 1
    if t == "Y":
        return struct.unpack_from("<h", data, pos)[0], pos + 2
    if t == "C":
        return data[pos] != 0, pos + 1
    if t == "I":
        return struct.unpack_from("<i", data, pos)[0], pos + 4
    if t == "F":
        return struct.unpack_from("<f", data, pos)[0], pos + 4
    if t == "D":
        return struct.unpack_from("<d", data, pos)[0], pos + 8
    if t == "L":
        return struct.unpack_from("<q", data, pos)[0], pos + 8
    if t == "f":
        return _read_array(data, pos, "f", 4)
    if t == "d":
        return _read_array(data, pos, "d", 8)
    if t == "l":
        return _read_array(data, pos, "q", 8)
    if t == "i":
        return _read_array(data, pos, "i", 4)
    if t == "b":
        return _read_array(data, pos, "b", 1)
    if t in "SR":
        n = struct.unpack_from("<I", data, pos)[0]
        raw = data[pos + 4:pos + 4 + n]
        return (raw.decode("utf-8", "replace") if t == "S" else raw), pos + 4 + n
    raise ValueError("unknown FBX property type %r" % t)


def _read_node(data, pos, wide):
    if wide:
        end, nprops, _plen = struct.unpack_from("<QQQ", data, pos)
        pos += 24
    else:
        end, nprops, _plen = struct.unpack_from("<III", data, pos)
        pos += 12
    name_len = data[pos]
    pos += 1
    if end == 0:
        return None, pos
    name = data[pos:pos + name_len].decode("ascii", "replace")
    pos += name_len
    props = []
    for _ in range(nprops):
        value, pos = _read_prop(data, pos)
        props.append(value)
    children = []
    while pos < end:
        child, pos = _read_node(data, pos, wide)
        if child is not None:
            children.append(child)
    return Node(name, props, children), end


def parse(path):
    data = open(path, "rb").read()
    if not data.startswith(b"Kaydara FBX Binary"):
        raise ValueError("binary FBX 가 아니에요: %s" % path)
    version = struct.unpack_from("<I", data, 23)[0]
    wide = version >= 7500
    pos = 27
    children = []
    while pos < len(data) - 160:
        node, pos = _read_node(data, pos, wide)
        if node is None:
            break
        children.append(node)
    return Node("", [version], children)


# ---- 행렬 (4x4, 행 우선) ----
def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def _ident():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def _translate(v):
    m = _ident()
    m[0][3], m[1][3], m[2][3] = v
    return m


def _scale(v):
    m = _ident()
    m[0][0], m[1][1], m[2][2] = v
    return m


def _rotate_xyz(deg):
    """FBX 기본 회전 순서 (eEulerXYZ): R = Rz * Ry * Rx"""
    x, y, z = (math.radians(a) for a in deg)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    ry = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    rz = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return _mul(rz, _mul(ry, rx))


def _apply(m, p):
    x, y, z = p
    return (m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3],
            m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
            m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3])


def _apply_dir(m, p):
    x, y, z = p
    return (m[0][0] * x + m[0][1] * y + m[0][2] * z,
            m[1][0] * x + m[1][1] * y + m[1][2] * z,
            m[2][0] * x + m[2][1] * y + m[2][2] * z)


def _props70(node):
    out = {}
    p70 = node.find("Properties70")
    if p70:
        for p in p70.all("P"):
            out[p.props[0]] = p.props[4:]
    return out


def global_settings(path_or_root):
    root = parse(path_or_root) if isinstance(path_or_root, str) else path_or_root
    gs = root.find("GlobalSettings")
    return {k: (v[0] if len(v) == 1 else v) for k, v in _props70(gs).items()} if gs else {}


class Mesh:
    def __init__(self, name, positions, normals, uvs, material):
        self.name, self.positions, self.normals, self.uvs, self.material = name, positions, normals, uvs, material


def _layer(geom, layer_name, values_name, index_name, width):
    """레이어 값을 '폴리곤 정점마다 하나' 로 풀어 주는 함수를 돌려준다 (없으면 None)"""
    layer = geom.find(layer_name)
    if layer is None or layer.find(values_name) is None:
        return None
    values = layer.find(values_name).props[0]
    mapping = layer.find("MappingInformationType").props[0]
    ref = layer.find("ReferenceInformationType").props[0]
    index = layer.find(index_name).props[0] if (ref != "Direct" and layer.find(index_name) is not None) else None

    def get(poly_vertex, control_point, polygon):
        if mapping == "ByPolygonVertex":
            i = poly_vertex
        elif mapping in ("ByVertice", "ByVertex", "ByControlPoint"):
            i = control_point
        elif mapping == "ByPolygon":
            i = polygon
        else:   # AllSame
            i = 0
        if index is not None:
            i = index[i]
        return tuple(values[i * width:i * width + width])
    return get


def load(path):
    root = parse(path)
    objects = root.find("Objects")
    geoms = {g.props[0]: g for g in objects.all("Geometry")}
    models = {m.props[0]: m for m in objects.all("Model")}
    materials = {m.props[0]: m.props[1].split("\x00")[0] for m in objects.all("Material")}
    parent = {}          # 자식 id → 부모 id
    geom_of = {}         # 모델 id → geometry id 목록
    mats_of = {}         # 모델 id → material 이름 목록
    for c in root.find("Connections").all("C"):
        if c.props[0] != "OO":
            continue
        child, par = c.props[1], c.props[2]
        if child in geoms and par in models:
            geom_of.setdefault(par, []).append(child)
        elif child in models:
            parent[child] = par
        elif child in materials and par in models:
            mats_of.setdefault(par, []).append(materials[child])

    def local(mid):
        p = _props70(models[mid])
        t = p.get("Lcl Translation", [0, 0, 0])
        r = p.get("Lcl Rotation", [0, 0, 0])
        s = p.get("Lcl Scaling", [1, 1, 1])
        pre = p.get("PreRotation", [0, 0, 0])
        post = p.get("PostRotation", [0, 0, 0])
        rp = p.get("RotationPivot", [0, 0, 0])
        sp = p.get("ScalingPivot", [0, 0, 0])
        roff = p.get("RotationOffset", [0, 0, 0])
        soff = p.get("ScalingOffset", [0, 0, 0])
        neg = lambda v: [-a for a in v]
        post_inv = [[_rotate_xyz(post)[j][i] for j in range(4)] for i in range(4)]   # 회전의 역 = 전치
        m = _translate(t)
        for part in (_translate(roff), _translate(rp), _rotate_xyz(pre), _rotate_xyz(r), post_inv, _translate(neg(rp)),
                     _translate(soff), _translate(sp), _scale(s), _translate(neg(sp))):
            m = _mul(m, part)
        return m

    def world(mid):
        m = local(mid)
        while mid in parent and parent[mid] in models:
            mid = parent[mid]
            m = _mul(local(mid), m)
        return m

    out = []
    for mid, gids in geom_of.items():
        p = _props70(models[mid])
        geo = _mul(_translate(p.get("GeometricTranslation", [0, 0, 0])),
                   _mul(_rotate_xyz(p.get("GeometricRotation", [0, 0, 0])), _scale(p.get("GeometricScaling", [1, 1, 1]))))
        m = _mul(world(mid), geo)
        name = models[mid].props[1].split("\x00")[0]
        for gid in gids:
            g = geoms[gid]
            verts = g.find("Vertices").props[0]
            pvi = g.find("PolygonVertexIndex").props[0]
            get_uv = _layer(g, "LayerElementUV", "UV", "UVIndex", 2)
            get_n = _layer(g, "LayerElementNormal", "Normals", "NormalsIndex", 3)
            get_mat = _layer(g, "LayerElementMaterial", "Materials", "Materials", 1)
            mat_names = mats_of.get(mid, [])
            by_mat = {}
            poly, polygon = [], 0
            for k, raw in enumerate(pvi):
                cp = raw if raw >= 0 else ~raw
                poly.append((k, cp))
                if raw < 0:
                    mat_layer = g.find("LayerElementMaterial")
                    mi = 0
                    if mat_layer is not None:
                        mapping = mat_layer.find("MappingInformationType").props[0]
                        mlist = mat_layer.find("Materials").props[0]
                        mi = mlist[polygon] if mapping == "ByPolygon" and polygon < len(mlist) else (mlist[0] if mlist else 0)
                    bucket = by_mat.setdefault(mi, ([], [], []))
                    for a in range(1, len(poly) - 1):       # 부채꼴로 삼각형화
                        for kk, cc in (poly[0], poly[a], poly[a + 1]):
                            bucket[0].append(_apply(m, tuple(verts[cc * 3:cc * 3 + 3])))
                            bucket[1].append(_apply_dir(m, get_n(kk, cc, polygon)) if get_n else None)
                            bucket[2].append(get_uv(kk, cc, polygon) if get_uv else (0.0, 0.0))
                    poly = []
                    polygon += 1
            for mi, (pos, nor, uv) in by_mat.items():
                # 법선이 없거나 크기 변환으로 길이가 달라졌으면 면 법선으로 다시 계산
                fixed = []
                for t in range(0, len(pos), 3):
                    a, b, c = pos[t], pos[t + 1], pos[t + 2]
                    ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
                    vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
                    fn = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
                    fl = math.sqrt(sum(q * q for q in fn)) or 1.0
                    fn = tuple(q / fl for q in fn)
                    for q in range(3):
                        n = nor[t + q]
                        if n is None:
                            fixed.append(fn)
                        else:
                            nl = math.sqrt(sum(w * w for w in n)) or 1.0
                            fixed.append(tuple(w / nl for w in n))
                out.append(Mesh(name, pos, fixed, uv, mat_names[mi] if mi < len(mat_names) else "material%d" % mi))
    return out


if __name__ == "__main__":
    import sys
    for path in sys.argv[1:]:
        print(path, global_settings(path).get("UpAxis"), global_settings(path).get("UnitScaleFactor"))
        for mesh in load(path):
            xs = [p[0] for p in mesh.positions]
            ys = [p[1] for p in mesh.positions]
            zs = [p[2] for p in mesh.positions]
            print("  %-22s mat=%-18s tris=%-5d x[%.1f..%.1f] y[%.1f..%.1f] z[%.1f..%.1f]" % (
                mesh.name, mesh.material, len(mesh.positions) // 3, min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))
