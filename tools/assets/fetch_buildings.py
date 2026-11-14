"""시연 위치 둘레의 지도 자료(OpenStreetMap: 건물 윤곽·도로·횡단보도·신호등)를 받아 앱에 넣어 둔다.

앱은 평소에 내 위치 둘레의 자료를 Overpass API 에서 바로 받는다. 그런데 공개 서버라 바쁠 때는 실패하기도 해서,
시연하는 곳의 자료를 미리 받아 두면 그 구역 안에서는 이 자료를 쓴다 (앱의 map/BuildingsSource.kt).

사용법:
    python tools/assets/fetch_buildings.py [위도 경도 반경m]      # 기본: 37.5005 127.0310 750 (시연 경로 일대)

결과: app/src/main/assets/buildings_demo.json
    {"origin": {"lat", "lon"}, "radius", "elements": [...]}   elements 는 Overpass 응답과 같은 모양에서 쓰는 것만 남긴 것:
      건물    {"type": "way", "id", "geometry": [{"lat", "lon"}...], "tags": {"building": "yes", "height"?, "building:levels"?}}
      도로    {"type": "way", "id", "geometry": [...], "tags": {"highway": 등급, "lanes"?, "oneway"?}}
      횡단보도 {"type": "node", "id", "lat", "lon", "tags": {"highway": "crossing"}}
      신호등   {"type": "node", "id", "lat", "lon", "tags": {"highway": "traffic_signals"}}
지도 자료 © OpenStreetMap contributors (ODbL).
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ENDPOINTS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "buildings_demo.json")
ROADS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"


def query(lat, lng, radius, timeout=40):
    """앱의 map/OsmBuildings.query 와 같은 질의문"""
    around = "(around:%d,%s,%s)" % (radius, lat, lng)
    return ('[out:json][timeout:%d];(way["building"]%s;way["highway"~"^(%s)$"]%s;node["highway"~"^(crossing|traffic_signals)$"]%s;);out tags geom;'
            % (timeout, around, ROADS, around, around))


def fetch(lat, lng, radius):
    body = ("data=" + urllib.parse.quote(query(lat, lng, radius))).encode()
    for attempt in range(16):
        url = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "CarIndChat-tools/1.0", "Content-Type": "application/x-www-form-urlencoded"})
            return json.loads(urllib.request.urlopen(req, timeout=70).read())
        except Exception as e:
            print("  실패 (%s): %s → 잠시 뒤 다시" % (url.split("/")[2], e), flush=True)
            time.sleep(8)
    raise SystemExit("지도 자료를 받지 못했어요. 조금 뒤에 다시 해 보세요.")


def main():
    lat, lng, radius = (float(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) >= 4 else (37.5005, 127.0310, 750)
    data = fetch(lat, lng, radius)
    elements = []
    counts = {"건물": 0, "도로": 0, "횡단보도": 0, "신호등": 0}
    for e in data.get("elements", []):
        tags = e.get("tags", {})
        if e.get("type") == "node":
            if tags.get("highway") in ("crossing", "traffic_signals"):
                elements.append({"type": "node", "id": e["id"], "lat": round(e["lat"], 6), "lon": round(e["lon"], 6), "tags": {"highway": tags["highway"]}})
                counts["횡단보도" if tags["highway"] == "crossing" else "신호등"] += 1
            continue
        if "geometry" not in e:
            continue
        geometry = [{"lat": round(p["lat"], 6), "lon": round(p["lon"], 6)} for p in e["geometry"]]
        if "highway" in tags:
            keep = {k: v for k, v in tags.items() if k in ("highway", "lanes", "oneway")}
            counts["도로"] += 1
        else:
            keep = {k: v for k, v in tags.items() if k in ("height", "building:levels")}
            keep["building"] = "yes"
            counts["건물"] += 1
        elements.append({"type": "way", "id": e["id"], "geometry": geometry, "tags": keep})
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"origin": {"lat": lat, "lon": lng}, "radius": radius, "elements": elements}, f, separators=(",", ":"))
    print("%s → %s (%.0f KB)" % (", ".join("%s %d" % kv for kv in counts.items()), os.path.normpath(OUT), os.path.getsize(OUT) / 1024))


if __name__ == "__main__":
    main()
