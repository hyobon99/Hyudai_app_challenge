package com.roadtalk.app.ui.scene3d

import kotlin.math.hypot

/**
 * 지도 도로를 '바닥에 깐 띠' 삼각형으로 만든다. 좌표는 내 차 기준 (x = 오른쪽 m, z = −앞 m).
 *
 * 내가 달리는 길(차도 + 양옆 인도)을 가로지르는 띠는 자리마다 높이를 달리 깐다:
 *   차도 위   → 차선 바로 위. 교차로 안에서는 차선·가장자리 선이 가려진다
 *   인도 위   → 인도보다 살짝 높게. 인도가 끊겨 길이 뚫린 것처럼 보인다
 *   그 바깥   → 땅 높이
 * 내 길과 나란한 띠(맞은편 차도 등)는 내 길 바깥 부분만 깐다 (내 인도가 중앙 분리대처럼 남는다).
 * 내 길의 차도·인도가 어디까지인지는 [setRoad] 로 알려 준다 (차로 수에 따라 달라진다).
 * 정점은 x y z · 법선(위) · u v(0) 의 8개 값, 삼각형 3개씩 [data] 에 [size] 만큼 쌓인다.
 */
class StripBuilder {
    var data = FloatArray(16_384)
        private set
    var size = 0
        private set

    /** 내 길의 차도(lanes)와 인도 바깥(walk)의 좌우 끝 (x, m) */
    var lanesMin = -5.64f
        private set
    var lanesMax = 5.64f
        private set
    var walkMin = -7.31f
        private set
    var walkMax = 7.31f
        private set

    private val quad = FloatArray(8)
    private var poly = FloatArray(32)
    private var temp = FloatArray(32)

    fun setRoad(lanesMin: Float, lanesMax: Float, walkMin: Float, walkMax: Float) {
        this.lanesMin = lanesMin
        this.lanesMax = lanesMax
        this.walkMin = walkMin
        this.walkMax = walkMax
    }

    fun reset() {
        size = 0
    }

    /**
     * (ax, az) → (bx, bz) 를 중심선으로 하는 폭 2 × halfWidth 의 띠. 꺾이는 곳이 비지 않게 양 끝을 조금 늘인다.
     * crossing = 내 길을 가로지르는 길인지 (아니면 내 길 바깥 부분만 깐다)
     */
    fun strip(ax: Float, az: Float, bx: Float, bz: Float, halfWidth: Float, crossing: Boolean = true) {
        val length = hypot(bx - ax, bz - az)
        if (length < 0.05f) return
        val dx = (bx - ax) / length
        val dz = (bz - az) / length
        val ext = halfWidth * 0.6f
        val sx = ax - dx * ext
        val sz = az - dz * ext
        val ex = bx + dx * ext
        val ez = bz + dz * ext
        val nx = -dz * halfWidth
        val nz = dx * halfWidth
        quad[0] = sx + nx; quad[1] = sz + nz
        quad[2] = ex + nx; quad[3] = ez + nz
        quad[4] = ex - nx; quad[5] = ez - nz
        quad[6] = sx - nx; quad[7] = sz - nz
        band(-FAR, walkMin, GROUND_Y)
        band(walkMax, FAR, GROUND_Y)
        if (crossing) {
            band(walkMin, lanesMin, OVER_WALK_Y)
            band(lanesMin, lanesMax, ON_LANES_Y)
            band(lanesMax, walkMax, OVER_WALK_Y)
        }
    }

    /** 그 x 자리에서 띠가 깔리는 높이 */
    fun heightAt(x: Float): Float =
        if (x in lanesMin..lanesMax) ON_LANES_Y else if (x in walkMin..walkMax) OVER_WALK_Y else GROUND_Y

    private fun band(lo: Float, hi: Float, y: Float) {
        val first = clipSide(quad, 4, temp, lo, keepGreater = true)
        if (first < 3) return
        val count = clipSide(temp, first, poly, hi, keepGreater = false)
        if (count >= 3) fan(count, y)
    }

    private fun clipSide(source: FloatArray, count: Int, target: FloatArray, edge: Float, keepGreater: Boolean): Int {
        var n = 0
        for (i in 0 until count) {
            val j = (i + 1) % count
            val x0 = source[i * 2]
            val z0 = source[i * 2 + 1]
            val x1 = source[j * 2]
            val z1 = source[j * 2 + 1]
            val in0 = if (keepGreater) x0 >= edge else x0 <= edge
            val in1 = if (keepGreater) x1 >= edge else x1 <= edge
            if (in0) {
                target[n * 2] = x0; target[n * 2 + 1] = z0
                n++
            }
            if (in0 != in1) {
                val t = (edge - x0) / (x1 - x0)
                target[n * 2] = edge; target[n * 2 + 1] = z0 + (z1 - z0) * t
                n++
            }
        }
        return n
    }

    private fun fan(count: Int, y: Float) {
        val needed = size + (count - 2) * 24
        if (needed > data.size) data = data.copyOf(maxOf(data.size * 2, needed))
        for (k in 1 until count - 1) {
            vertex(poly[0], y, poly[1])
            vertex(poly[k * 2], y, poly[k * 2 + 1])
            vertex(poly[k * 2 + 2], y, poly[k * 2 + 3])
        }
    }

    private fun vertex(x: Float, y: Float, z: Float) {
        data[size] = x; data[size + 1] = y; data[size + 2] = z
        data[size + 3] = 0f; data[size + 4] = 1f; data[size + 5] = 0f
        data[size + 6] = 0f; data[size + 7] = 0f
        size += 8
    }

    companion object {
        const val ON_LANES_Y = 0.04f
        const val OVER_WALK_Y = 0.18f
        const val GROUND_Y = 0f
        private const val FAR = 1e6f
    }
}
