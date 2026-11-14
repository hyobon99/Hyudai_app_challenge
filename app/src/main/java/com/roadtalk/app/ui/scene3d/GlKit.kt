package com.roadtalk.app.ui.scene3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D 화면들([Scene3DView] 도로, [GarageView] 차 고르기)이 같이 쓰는 OpenGL ES 2.0 도구.
 * 셰이더 하나(팔레트 그림 색 × 간단한 조명 + 안개), .mesh 파일 읽기, 그림 올리기, 한 물체 그리기.
 * OpenGL 자원은 화면(GL 컨텍스트)마다 따로라서, 화면마다 하나씩 만들어 onSurfaceCreated 에서 [init] 한다.
 */
internal class GlKit(private val context: Context) {

    /** 정점 묶음 + 크기 (m). half* = 가운데에서 좌우·앞뒤 끝까지, height = 바닥에서 꼭대기까지 */
    class Mesh(val vbo: Int, val vertexCount: Int, val halfX: Float, val height: Float, val halfZ: Float)

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUv = 0
    private var uMvp = 0
    private var uModel = 0
    private var uLight = 0
    private var uEye = 0
    private var uFog = 0
    private var uFogColor = 0
    private var uTint = 0
    private var uUnlit = 0
    private var uTex = 0

    var white = 0
        private set

    fun init() {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uEye = GLES20.glGetUniformLocation(program, "uEye")
        uFog = GLES20.glGetUniformLocation(program, "uFog")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uUnlit = GLES20.glGetUniformLocation(program, "uUnlit")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        white = loadTexture(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, -1) })
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** 한 장면을 그리기 전에: 카메라 위치, 빛 방향, 안개 (fogStart 를 아주 크게 주면 안개 없음) */
    fun begin(eye: FloatArray, light: FloatArray, fogStart: Float, fogEnd: Float, fogColor: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glUniform3fv(uLight, 1, light, 0)
        GLES20.glUniform3fv(uEye, 1, eye, 0)
        GLES20.glUniform2f(uFog, fogStart, fogEnd)
        GLES20.glUniform3fv(uFogColor, 1, fogColor, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
    }

    /** unlit = 조명 없이 그림 색 그대로 (그림자, 바닥 무늬 등) */
    fun draw(mesh: Mesh?, texture: Int, viewProjection: FloatArray, model: FloatArray, tint: FloatArray, unlit: Boolean = false) {
        mesh ?: return
        GLES20.glUniformMatrix4fv(uMvp, 1, false, Mat4.multiply(viewProjection, model), 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4fv(uTint, 1, tint, 0)
        GLES20.glUniform1f(uUnlit, if (unlit) 1f else 0f)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
    }

    /** assets/scene3d 의 .mesh 파일 ("RTM1", 정점 수, 정점마다 x y z nx ny nz u v) */
    fun loadMesh(name: String): Mesh {
        val bytes = context.assets.open("$DIR/$name").use { it.readBytes() }
        require(bytes.size >= 8 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == 'M'.code.toByte()) { "잘못된 메시 파일: $name" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(buffer.getInt(4) * 8)
        buffer.position(8)
        buffer.asFloatBuffer().get(floats)
        return upload(floats)
    }

    fun upload(vertices: FloatArray): Mesh {
        var halfX = 0f
        var height = 0f
        var halfZ = 0f
        for (i in vertices.indices step 8) {
            halfX = maxOf(halfX, kotlin.math.abs(vertices[i]))
            height = maxOf(height, vertices[i + 1])
            halfZ = maxOf(halfZ, kotlin.math.abs(vertices[i + 2]))
        }
        val data = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(vertices).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, data, GLES20.GL_STATIC_DRAW)
        return Mesh(ids[0], vertices.size / 8, halfX, height, halfZ)
    }

    /** 바닥에 눕힌 1m × 1m 사각형 (바닥면, 바닥 무늬에 쓴다) */
    fun groundQuad(): Mesh = upload(
        floatArrayOf(
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f,
            -0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f,
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f,
            0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f,
        )
    )

    fun assetBitmap(name: String): Bitmap = context.assets.open("$DIR/$name").use { BitmapFactory.decodeStream(it) }

    /** 팔레트 그림은 칸마다 한 가지 색이라, 색이 번지지 않게 가장 가까운 점만 읽는다 (smooth = false) */
    fun loadTexture(bitmap: Bitmap, smooth: Boolean = false): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val filter = if (smooth) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return ids[0]
    }

    /** 차 모델 10종과 색 6종. 파일이 없거나 깨졌으면 빈 목록 (앱은 계속 돈다) */
    fun loadCars(): Pair<List<Mesh>, IntArray> = try {
        (0 until CarLook.TYPE_COUNT).map { loadMesh("car_$it.mesh") } to IntArray(CarLook.SKIN_COUNT) { loadTexture(assetBitmap("skin_$it.png")) }
    } catch (e: Exception) {
        Log.e(TAG, "차 모델을 읽지 못했어요 (assets/$DIR). README '3D 에셋' 참고", e)
        emptyList<Mesh>() to IntArray(0)
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "program: " + GLES20.glGetProgramInfoLog(id))
        return id
    }

    companion object {
        const val TAG = "RoadTalkScene"
        const val DIR = "scene3d"
        private const val STRIDE = 32
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        val LIGHT: FloatArray = Mat4.normalize(floatArrayOf(0.35f, 0.85f, 0.4f))
        const val NO_FOG = 1e9f

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform vec3 uLight;
            uniform vec3 uEye;
            uniform vec2 uFog;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying float vLight;
            varying float vFog;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                gl_Position = uMvp * vec4(aPos, 1.0);
                vec3 n = normalize(mat3(uModel) * aNormal);
                vLight = 0.6 + 0.4 * max(dot(n, uLight), 0.0);
                vFog = clamp((distance(world.xyz, uEye) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
                vUv = aUv;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec4 uTint;
            uniform float uUnlit;
            uniform vec3 uFogColor;
            varying vec2 vUv;
            varying float vLight;
            varying float vFog;
            void main() {
                vec4 c = texture2D(uTex, vUv) * uTint;
                float light = mix(vLight, 1.0, uUnlit);
                gl_FragColor = vec4(mix(c.rgb * light, uFogColor, vFog), c.a * (1.0 - vFog * uUnlit));
            }
        """
    }
}
