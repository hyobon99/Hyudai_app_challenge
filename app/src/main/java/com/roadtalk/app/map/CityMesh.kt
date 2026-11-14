package com.roadtalk.app.map

import kotlin.math.hypot

/**
 * 지도 건물들을 3D 로 세운 모양: 윤곽을 따라 벽을 세우고, 윤곽 모양 그대로 지붕을 덮는다.
 * 좌표는 구역 기준점 기준 (x = 동쪽 m, y = 높이 m, z = −북쪽 m). 정점은 x y z · 법선 3 · u v 의 8개 값, 삼각형 3개씩.
 * 벽의 u v 는 창문 무늬를 되풀이해 깔 수 있게 '가로 4m · 세로 3.2m(한 층)' 를 1 로 잡았다.
 * 건물마다 [Entry] 가 자기 정점의 위치(first)와 개수를 갖고 있어서, 화면은 보이는 건물만 골라 그릴 수 있다.
 */
class CityMesh(val vertices: FloatArray, val buildings: List<Entry>) {

    /** first = 이 건물의 첫 정점 번호. 벽 wallCount 개 다음에 지붕 roofCount 개가 이어진다. east/north/radius = 윤곽을 감싸는 원 */
    class Entry(
        val id: Long,
        val first: Int,
        val wallCount: Int,
        val roofCount: Int,
        val east: Float,
        val north: Float,
        val radius: Float,
        val ring: FloatArray,
        val height: Float,
    )

    companion object {
        const val WALL_U_METERS = 4f
        const val WALL_V_METERS = 3.2f

        fun build(footprints: List<OsmBuildings.Footprint>): CityMesh {
            val out = FloatList(footprints.size * 480)
            val entries = ArrayList<Entry>(footprints.size)
            for (f in footprints) {
                val ring = f.points
                val n = ring.size / 2
                if (n < 3) continue
                val first = out.size / 8
                // 벽: 변마다 네모 하나 (삼각형 2개). 윤곽이 반시계 방향이라 변의 오른쪽이 바깥
                var along = 0f
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = ring[i * 2]
                    val ay = ring[i * 2 + 1]
                    val bx = ring[j * 2]
                    val by = ring[j * 2 + 1]
                    val length = hypot(bx - ax, by - ay)
                    if (length < 0.01f) continue
                    val nx = (by - ay) / length
                    val nz = (bx - ax) / length
                    val u0 = along / WALL_U_METERS
                    val u1 = (along + length) / WALL_U_METERS
                    val v = f.height / WALL_V_METERS
                    along += length
                    out.vertex(ax, 0f, -ay, nx, 0f, nz, u0, v)
                    out.vertex(bx, 0f, -by, nx, 0f, nz, u1, v)
                    out.vertex(bx, f.height, -by, nx, 0f, nz, u1, 0f)
                    out.vertex(ax, 0f, -ay, nx, 0f, nz, u0, v)
                    out.vertex(bx, f.height, -by, nx, 0f, nz, u1, 0f)
                    out.vertex(ax, f.height, -ay, nx, 0f, nz, u0, 0f)
                }
                val wallCount = out.size / 8 - first
                for (index in triangulate(ring)) {
                    out.vertex(ring[index * 2], f.height, -ring[index * 2 + 1], 0f, 1f, 0f, 0f, 0f)
                }
                val roofCount = out.size / 8 - first - wallCount
                var cx = 0f
                var cy = 0f
                for (i in 0 until n) {
                    cx += ring[i * 2]
                    cy += ring[i * 2 + 1]
                }
                cx /= n
                cy /= n
                var radius = 0f
                for (i in 0 until n) radius = maxOf(radius, hypot(ring[i * 2] - cx, ring[i * 2 + 1] - cy))
                entries += Entry(f.id, first, wallCount, roofCount, cx, cy, radius, ring, f.height)
            }
            return CityMesh(out.toArray(), entries)
        }

        /**
         * 다각형(반시계 방향, 오목해도 됨)을 삼각형으로 나눈다 ('귀 자르기'). 돌려주는 값은 꼭짓점 번호 3개씩.
         * 윤곽이 스스로 겹치는 등 이상해서 더 못 자르면 남은 부분은 부채꼴로 나눈다.
         */
        fun triangulate(ring: FloatArray): IntArray {
            val n = ring.size / 2
            if (n < 3) return IntArray(0)
            val left = ArrayList<Int>(n)
            for (i in 0 until n) left += i
            val out = ArrayList<Int>((n - 2) * 3)
            var guard = n * n + 8
            var i = 0
            while (left.size > 3 && guard-- > 0) {
                val a = left[(i + left.size - 1) % left.size]
                val b = left[i % left.size]
                val c = left[(i + 1) % left.size]
                if (isEar(ring, left, a, b, c)) {
                    out += a
                    out += b
                    out += c
                    left.removeAt(i % left.size)
                    i = 0
                } else {
                    i++
                    if (i >= left.size) break       // 더 자를 귀가 없다
                }
            }
            for (k in 1 until left.size - 1) {
                out += left[0]
                out += left[k]
                out += left[k + 1]
            }
            return out.toIntArray()
        }

        private fun isEar(ring: FloatArray, left: List<Int>, a: Int, b: Int, c: Int): Boolean {
            val ax = ring[a * 2]
            val ay = ring[a * 2 + 1]
            val bx = ring[b * 2]
            val by = ring[b * 2 + 1]
            val cx = ring[c * 2]
            val cy = ring[c * 2 + 1]
            if ((bx - ax) * (cy - by) - (by - ay) * (cx - bx) <= 1e-6f) return false     // 오목한 꼭짓점 (또는 일직선)
            for (p in left) {
                if (p == a || p == b || p == c) continue
                val px = ring[p * 2]
                val py = ring[p * 2 + 1]
                val d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
                val d2 = (cx - bx) * (py - by) - (cy - by) * (px - bx)
                val d3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx)
                if (d1 >= 0f && d2 >= 0f && d3 >= 0f) return false                       // 다른 꼭짓점이 삼각형 안에 있다
            }
            return true
        }
    }

    /** 크기가 늘어나는 Float 배열 */
    private class FloatList(capacity: Int) {
        private var data = FloatArray(maxOf(capacity, 64))
        var size = 0
            private set

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            if (size + 8 > data.size) data = data.copyOf(data.size * 2)
            data[size] = x; data[size + 1] = y; data[size + 2] = z
            data[size + 3] = nx; data[size + 4] = ny; data[size + 5] = nz
            data[size + 6] = u; data[size + 7] = v
            size += 8
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
