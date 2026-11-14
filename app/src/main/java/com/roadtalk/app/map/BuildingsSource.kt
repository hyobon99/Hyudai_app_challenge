package com.roadtalk.app.map

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.hypot

/**
 * 내 위치 둘레의 건물을 지도(OpenStreetMap)에서 가져온다.
 *  1. 앱에 넣어 둔 구역(시연 위치 일대, assets/buildings_demo.json · tools/assets/fetch_buildings.py 가 만든다) 안이면 그 자료를 쓴다
 *  2. 그 밖이면 Overpass API 에서 반경 [RADIUS_M] 을 받고, 받은 곳에서 [REFETCH_M] 넘게 벗어나면 다시 받는다
 * 받지 못하면(인터넷 없음, 공개 서버가 바쁨) 건물 없이 도로만 보이고, 조금 뒤 다시 시도한다.
 * 참고: 2번은 내 위치(좌표)를 Overpass 서버에 보내게 된다 (지도 타일을 받을 때와 같다).
 *
 * @param bundled 앱에 넣어 둔 구역의 JSON 을 읽어 주는 함수 (없으면 null)
 */
class BuildingsSource(private val scope: CoroutineScope, private val bundled: () -> String?) {

    private val _area = MutableStateFlow<OsmBuildings.Area?>(null)
    val area: StateFlow<OsmBuildings.Area?> = _area.asStateFlow()

    /** 지금 구역의 기준점에서 이만큼(m) 벗어나기 전에는 다시 가져오지 않는다 */
    @Volatile private var cover = 0f
    @Volatile private var busy = false
    @Volatile private var retryAfter = 0L
    private var bundledArea: OsmBuildings.Area? = null
    private var bundledRadius = 0f
    private var bundledRead = false

    /** 내 위치가 바뀔 때마다 부른다. 필요할 때만 실제로 가져온다 */
    fun onLocation(lat: Double, lng: Double) {
        val current = _area.value
        if (current != null && distance(lat, lng, current) < cover) return
        if (busy || System.currentTimeMillis() < retryAfter) return
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                val packed = readBundled()
                if (packed != null && distance(lat, lng, packed) < bundledRadius - EDGE_M) {
                    cover = bundledRadius - EDGE_M
                    _area.value = packed
                    Log.d(TAG, "buildings: ${packed.boxes.size} from the bundled area")
                } else {
                    val boxes = OsmBuildings.parse(fetch(OsmBuildings.query(lat, lng, RADIUS_M)), lat, lng)
                    cover = REFETCH_M
                    _area.value = OsmBuildings.Area(lat, lng, boxes)
                    Log.d(TAG, "buildings: ${boxes.size} around $lat,$lng")
                }
            } catch (e: Exception) {
                Log.w(TAG, "buildings unavailable: ${e.javaClass.simpleName} ${e.message}")
                retryAfter = System.currentTimeMillis() + RETRY_MILLIS
            } finally {
                busy = false
            }
        }
    }

    private fun distance(lat: Double, lng: Double, area: OsmBuildings.Area): Float =
        hypot(OsmBuildings.east(lng, area.originLat, area.originLng), OsmBuildings.north(lat, area.originLat))

    private fun readBundled(): OsmBuildings.Area? {
        if (!bundledRead) {
            bundledRead = true
            try {
                val json = bundled() ?: return null
                val root = JSONObject(json)
                val origin = root.getJSONObject("origin")
                val lat = origin.getDouble("lat")
                val lng = origin.getDouble("lon")
                bundledRadius = root.optDouble("radius", 0.0).toFloat()
                bundledArea = OsmBuildings.Area(lat, lng, OsmBuildings.parse(json, lat, lng))
            } catch (e: Exception) {
                Log.w(TAG, "bundled buildings unreadable: ${e.message}")
            }
        }
        return bundledArea
    }

    private fun fetch(query: String): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "CarIndChat/1.0 (Android)")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "RoadTalkMap"
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val RADIUS_M = 320
        const val REFETCH_M = 150f
        /** 구역 가장자리에서 이만큼 안쪽까지만 그 구역을 쓴다 (앞쪽 건물이 비어 보이지 않게) */
        const val EDGE_M = 170f
        const val RETRY_MILLIS = 30_000L
    }
}
