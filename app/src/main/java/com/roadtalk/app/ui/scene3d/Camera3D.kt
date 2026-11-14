package com.roadtalk.app.ui.scene3d

import kotlin.math.sqrt
import kotlin.math.tan

/** 4x4 행렬 (OpenGL 과 같은 열 우선 FloatArray(16)). 안드로이드 없이도 계산되도록 직접 구현했다 */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** a × b */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = sum
        }
        return r
    }

    fun translation(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun scale(x: Float, y: Float, z: Float) = identity().also { it[0] = x; it[5] = y; it[10] = z }

    /** Y 축 회전 (위에서 봤을 때 반시계 방향이 +) */
    fun rotationY(degrees: Float): FloatArray {
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    }

    fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
        return FloatArray(16).also {
            it[0] = f / aspect
            it[5] = f
            it[10] = (far + near) / (near - far)
            it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    fun lookAt(eye: FloatArray, target: FloatArray): FloatArray {
        val f = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = normalize(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(s, f)
        return floatArrayOf(
            s[0], u[0], -f[0], 0f,
            s[1], u[1], -f[1], 0f,
            s[2], u[2], -f[2], 0f,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1f,
        )
    }

    /** 빛 방향(light, 표면에서 빛 쪽)으로 y = planeY 평면에 납작하게 눌러 그림자를 만드는 행렬 */
    fun shadow(light: FloatArray, planeY: Float): FloatArray {
        val kx = light[0] / light[1]
        val kz = light[2] / light[1]
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            -kx, 0f, -kz, 0f,
            0f, 0f, 1f, 0f,
            kx * planeY, planeY, kz * planeY, 1f,
        )
    }

    fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(dot(v, v)).takeIf { it > 0f } ?: 1f
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

/**
 * 내 차 정수리 뒤 위에서 앞을 내려다보는 카메라.
 * 좌표: 내 차가 원점, 오른쪽 = +X, 위 = +Y, 앞 = -Z (m). 상대 위치 (앞 f, 오른쪽 l) 인 차는 (l, 0, -f) 에 있다.
 * [rearM] 만큼 뒤에 있는 차까지 화면 아래쪽에 들어오도록, 뒤 범위가 넓어지면 카메라가 더 높이·멀리 물러난다.
 */
class Camera3D(val width: Float, val height: Float, rearM: Float) {
    /** 기본(뒤 7m)일 때 1. 뒤 범위가 넓어질수록 커진다 */
    val scale = (rearM + 1.5f) / 8.5f
    val eye = floatArrayOf(0f, EYE_HEIGHT * scale, EYE_BACK * scale)
    val target = floatArrayOf(0f, 0f, -LOOK_AHEAD * scale)
    val fogStart = 45f * scale
    val fogEnd = 150f * scale
    private val aspect = if (height > 0f) width / height else 1f
    val viewProjection: FloatArray =
        Mat4.multiply(Mat4.perspective(FOV_Y, aspect, 1f, 600f * scale), Mat4.lookAt(eye, target))

    /** 화면 맨 위 가운데에 보이는 도로가 내 차에서 앞으로 몇 m 인지 (지평선이 보이면 안개 끝) */
    val frontM: Float get() = ground(width / 2f, 0f)?.first?.coerceAtMost(fogEnd) ?: fogEnd

    /** 3D 점이 화면 어디(px)에 그려지는지. 카메라 뒤쪽이면 null */
    fun project(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        val m = viewProjection
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 0.01f) return null
        return (cx / cw * 0.5f + 0.5f) * width to (1f - (cy / cw * 0.5f + 0.5f)) * height
    }

    /** 화면의 한 점(px)이 가리키는 도로 위 위치 (앞 m, 오른쪽 m). 하늘을 가리키면 null */
    fun ground(px: Float, py: Float): Pair<Float, Float>? {
        val f = Mat4.normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = Mat4.normalize(Mat4.cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = Mat4.cross(s, f)
        val t = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()
        val nx = (2f * px / width - 1f) * t * aspect
        val ny = (1f - 2f * py / height) * t
        val dx = f[0] + s[0] * nx + u[0] * ny
        val dy = f[1] + s[1] * nx + u[1] * ny
        val dz = f[2] + s[2] * nx + u[2] * ny
        if (dy >= -1e-4f) return null
        val k = -eye[1] / dy
        return -(eye[2] + dz * k) to (eye[0] + dx * k)
    }

    companion object {
        const val FOV_Y = 60f
        // 내 차 뒤 위에서 45도로 내려다본다 (처음에는 32도였다: 앞은 지평선까지 보이는데 뒤는 8.5m 라 앞뒤 차이가 너무 컸다).
        // 화면 맨 아래가 내 차 뒤 8.5m, 맨 위가 앞 약 46m 가 되도록 맞춘 값
        private const val EYE_HEIGHT = 15.71f
        private const val EYE_BACK = 12.71f
        private const val LOOK_AHEAD = 3f
    }
}
