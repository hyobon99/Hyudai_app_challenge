"""시연 위치 둘레의 건물 자료(OpenStreetMap)를 받아 앱에 넣어 둔다.

앱은 평소에 내 위치 둘레의 건물을 Overpass API 에서 바로 받는다. 그런데 공개 서버라 바쁠 때는 실패하기도 해서,
시연하는 곳의 자료를 미리 받아 두면 그럴 때 대신 쓴다 (앱의 map/BuildingsSource.kt).

사용법:
    python tools/assets/fetch_buildings.py [위도 경도 반경m]      # 기본: 37.5005 127.0310 750 (시연 경로 일대)

결과: app/src/main/assets/buildings_demo.json  {"origin": {"lat", "lon"}, "radius", "elements": [{"id", "geometry", "tags"}]}
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


def fetch(lat, lng, radius):
    query = '[out:json][timeout:40];way["building"](around:%d,%s,%s);out tags geom;' % (radius, lat, lng)
    body = ("data=" + urllib.parse.quote(query)).encode()
    for attempt in range(12):
        url = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "CarIndChat-tools/1.0", "Content-Type": "application/x-www-form-urlencoded"})
            return json.loads(urllib.request.urlopen(req, timeout=60).read())
        except Exception as e:
            print("  실패 (%s): %s → 잠시 뒤 다시" % (url.split("/")[2], e))
            time.sleep(8)
    raise SystemExit("건물 자료를 받지 못했어요. 조금 뒤에 다시 해 보세요.")


def main():
    lat, lng, radius = (float(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) >= 4 else (37.5005, 127.0310, 750)
    data = fetch(lat, lng, radius)
    elements = []
    for e in data.get("elements", []):
        if "geometry" not in e:
            continue
        tags = {k: v for k, v in e.get("tags", {}).items() if k in ("height", "building:levels")}
        geometry = [{"lat": round(p["lat"], 6), "lon": round(p["lon"], 6)} for p in e["geometry"]]
        elements.append({"id": e["id"], "geometry": geometry, "tags": tags})
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"origin": {"lat": lat, "lon": lng}, "radius": radius, "elements": elements}, f, separators=(",", ":"))
    print("건물 %d개 → %s (%.0f KB)" % (len(elements), os.path.normpath(OUT), os.path.getsize(OUT) / 1024))


if __name__ == "__main__":
    main()
