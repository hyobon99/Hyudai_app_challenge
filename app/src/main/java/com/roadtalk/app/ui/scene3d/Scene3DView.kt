package com.roadtalk.app.ui.scene3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil

/**
 * 내 차 뒤 위에서 본 3D 도로 화면 (OpenGL ES 2.0, 추가 라이브러리 없음).
 * 그리는 것: 3차선 도로(10m 토막을 이어 붙임) · 인도의 나무와 가로등 · 내 차 · 주변 Car-ind CHAT 차 · 바닥 그림자 · 선택한 차의 빛 고리.
 * 모델은 assets/scene3d 의 .mesh 파일 (tools/assets/convert_assets.py 가 만든다), 색은 작은 팔레트 그림(skin_N.png, city.png)에서 가져온다.
 * 화면에 무엇을 그릴지는 [frame] 에 넣어 주면 다음 프레임부터 반영된다.
 */
class Scene3DView(context: Context) : GLSurfaceView(context) {

    /** 주변 차 한 대. lateral/forward = 내 차 기준 오른쪽/앞 (m), headingDeg = 내 진행 방향 기준 시계 방향 각도 */
    data class Car(val lateral: Float, val forward: Float, val headingDeg: Float, val type: Int, val skin: Int, val selected: Boolean)

    data class Frame(
        val cars: List<Car> = emptyList(),
        val myType: Int = 0,
        val mySkin: Int = 0,
        /** 화면 아래쪽에 들어와야 하는 뒤쪽 거리 (m). 카메라 높이를 정한다 */
        val rearM: Float = 7f,
        /** 내 차 속도 (m/s). 도로가 이 속도로 뒤로 흘러간다. 모르면 0 */
        val speedMps: Float = 0f,
    )

    @Volatile var frame = Frame()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)      // 그림자가 겹쳐 진해지지 않게 스텐실 버퍼를 쓴다
        setRenderer(SceneRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private class Mesh(val vbo: Int, val vertexCount: Int)

    private inner class SceneRenderer : Renderer {
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

        private var cars = emptyList<Mesh>()
        private var skins = IntArray(0)
        private var road: Mesh? = null
        private var tree: Mesh? = null
        private var lamp: Mesh? = null
        private var quad: Mesh? = null
        private var cityTexture = 0
        private var whiteTexture = 0
        private var ringTexture = 0

        private var width = 1
        private var height = 1
        private var lastNanos = 0L
        /** 도로가 뒤로 흘러간 거리 (m, 0 ~ SCROLL_PERIOD) */
        private var scroll = 0f
        private val light = Mat4.normalize(floatArrayOf(0.35f, 0.85f, 0.4f))

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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

            // 에셋 파일이 빠졌거나 깨졌어도 앱은 계속 돌게 한다 (그때는 하늘만 보인다. README '3D 에셋' 참고)
            try {
                cars = (0 until CAR_TYPES).map { loadMesh("car_$it.mesh") }
                skins = IntArray(SKIN_COUNT) { loadTexture(BitmapFactory.decodeStream(context.assets.open("$DIR/skin_$it.png"))) }
                road = loadMesh("road.mesh")
                tree = loadMesh("tree.mesh")
                lamp = loadMesh("lamp.mesh")
                cityTexture = loadTexture(BitmapFactory.decodeStream(context.assets.open("$DIR/city.png")))
            } catch (e: Exception) {
                Log.e(TAG, "3D 에셋을 읽지 못했어요 (assets/$DIR). tools/assets/convert_assets.py 를 먼저 돌려 주세요", e)
                cars = emptyList()
                skins = IntArray(0)
            }
            whiteTexture = loadTexture(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, -1) })
            ringTexture = loadTexture(ringBitmap(), smooth = true)
            // 바닥에 눕힌 1m × 1m 사각형 (바닥면, 빛 고리에 쓴다)
            quad = upload(
                floatArrayOf(
                    -0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f,
                    -0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f,
                    0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f,
                    -0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f,
                    0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f,
                    0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f,
                )
            )
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            Log.d(TAG, "3D scene ready: ${cars.size} car models, ${skins.size} skins")
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
            lastNanos = now
            val f = frame
            scroll = (scroll + f.speedMps.coerceIn(0f, 60f) * dt) % SCROLL_PERIOD

            val camera = Camera3D(width.toFloat(), height.toFloat(), f.rearM)
            val vp = camera.viewProjection
            GLES20.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
            GLES20.glClearStencil(0)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform3fv(uLight, 1, light, 0)
            GLES20.glUniform3fv(uEye, 1, camera.eye, 0)
            GLES20.glUniform2f(uFog, camera.fogStart, camera.fogEnd)
            GLES20.glUniform3fv(uFogColor, 1, SKY, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(uTex, 0)

            // 도로 밖 땅
            draw(quad, whiteTexture, vp, Mat4.multiply(Mat4.translation(0f, -0.03f, 0f), Mat4.scale(4000f, 1f, 4000f)), GROUND)

            // 도로 · 나무 · 가로등: 카메라 뒤부터 안개 끝까지, 20m 무늬가 scroll 만큼 뒤로 흘러간다
            val zNear = ceil((camera.eye[2] + 10f) / SCROLL_PERIOD) * SCROLL_PERIOD
            val zFar = -camera.fogEnd
            val scenery = ArrayList<Pair<Mesh?, FloatArray>>()
            var z = zNear + scroll
            while (z > zFar) {
                draw(road, cityTexture, vp, Mat4.translation(0f, 0f, z), WHITE)
                draw(road, cityTexture, vp, Mat4.translation(0f, 0f, z - 10f), WHITE)
                scenery += tree to Mat4.translation(-SIDE_X, CURB_Y, z - 3f)
                scenery += tree to Mat4.translation(SIDE_X, CURB_Y, z - 8f)
                scenery += tree to Mat4.translation(-SIDE_X, CURB_Y, z - 13f)
                scenery += tree to Mat4.translation(SIDE_X, CURB_Y, z - 18f)
                scenery += lamp to Mat4.translation(-LAMP_X, CURB_Y, z)
                scenery += lamp to Mat4.multiply(Mat4.translation(LAMP_X, CURB_Y, z - 10f), Mat4.rotationY(180f))
                z -= SCROLL_PERIOD
            }
            scenery.forEach { (mesh, model) -> draw(mesh, cityTexture, vp, model, WHITE) }

            // 차 위치
            val myModel = Mat4.identity()
            val others = f.cars.map { car ->
                car to Mat4.multiply(Mat4.translation(car.lateral, 0f, -car.forward), Mat4.rotationY(-car.headingDeg))
            }

            // 바닥 그림자: 모델을 빛 방향으로 바닥에 눌러 그린다. 스텐실로 한 번 칠한 곳은 다시 칠하지 않는다
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glDepthMask(false)
            GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            GLES20.glStencilFunc(GLES20.GL_EQUAL, 0, 0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR)
            val roadShadow = Mat4.shadow(light, 0.03f)
            val curbShadow = Mat4.shadow(light, CURB_Y + 0.03f)
            draw(cars.getOrNull(f.myType), whiteTexture, vp, Mat4.multiply(roadShadow, myModel), SHADOW)
            others.forEach { (car, model) -> draw(cars.getOrNull(car.type), whiteTexture, vp, Mat4.multiply(roadShadow, model), SHADOW) }
            scenery.forEach { (mesh, model) -> draw(mesh, whiteTexture, vp, Mat4.multiply(curbShadow, model), SHADOW) }
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)

            // 선택한 차 아래의 파란 빛 고리
            others.filter { it.first.selected }.forEach { (_, model) ->
                val ring = Mat4.multiply(model, Mat4.multiply(Mat4.translation(0f, 0.05f, 0f), Mat4.scale(4.4f, 1f, 7.6f)))
                draw(quad, ringTexture, vp, ring, WHITE, unlit = true)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)

            draw(cars.getOrNull(f.myType), skinTexture(f.mySkin), vp, myModel, WHITE)
            others.forEach { (car, model) -> draw(cars.getOrNull(car.type), skinTexture(car.skin), vp, model, WHITE) }
        }

        private fun skinTexture(skin: Int) = if (skins.isEmpty()) whiteTexture else skins[skin.mod(skins.size)]

        private fun draw(mesh: Mesh?, texture: Int, vp: FloatArray, model: FloatArray, tint: FloatArray, unlit: Boolean = false) {
            mesh ?: return
            GLES20.glUniformMatrix4fv(uMvp, 1, false, Mat4.multiply(vp, model), 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glUniform4fv(uTint, 1, tint, 0)
            GLES20.glUniform1f(uUnlit, if (unlit || tint === SHADOW) 1f else 0f)
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

        private fun loadMesh(name: String): Mesh {
            val bytes = context.assets.open("$DIR/$name").use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 8 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == 'M'.code.toByte()) { "잘못된 메시 파일: $name" }
            val count = buffer.getInt(4)
            val floats = FloatArray(count * 8)
            buffer.position(8)
            buffer.asFloatBuffer().get(floats)
            return upload(floats)
        }

        private fun upload(vertices: FloatArray): Mesh {
            val data = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            data.put(vertices).position(0)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, data, GLES20.GL_STATIC_DRAW)
            return Mesh(ids[0], vertices.size / 8)
        }

        /** 팔레트 그림은 칸마다 한 가지 색이라, 색이 번지지 않게 가장 가까운 점만 읽는다 (smooth = false) */
        private fun loadTexture(bitmap: Bitmap, smooth: Boolean = false): Int {
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

        /** 선택한 차 아래에 까는 파란 빛 고리 (둥근 사각형 테두리 + 번짐) */
        private fun ringBitmap(): Bitmap {
            val w = 128
            val h = 224
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val rect = RectF(26f, 26f, w - 26f, h - 26f)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 16f
                color = 0x993D8BFF.toInt()
                maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f
                color = 0xFF6FAAFF.toInt()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x333D8BFF }
            canvas.drawRoundRect(rect, 30f, 30f, fill)
            canvas.drawRoundRect(rect, 30f, 30f, glow)
            canvas.drawRoundRect(rect, 30f, 30f, line)
            return bitmap
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
    }

    companion object {
        private const val TAG = "RoadTalkScene"
        private const val DIR = "scene3d"
        /** 차 모델 수 (0~5 승용차, 6 경찰차, 7 버스, 8·9 트럭) 와 그중 주변 차에 쓰는 승용차 수 */
        const val CAR_TYPES = 10
        const val PASSENGER_TYPES = 6
        /** 차 색 수 (0 파랑, 1 빨강, 2 노랑, 3 초록, 4 보라, 5 은색) */
        const val SKIN_COUNT = 6
        private const val STRIDE = 32
        private const val SCROLL_PERIOD = 20f
        /** 인도 높이, 나무·가로등을 세우는 좌우 위치 (m) */
        private const val CURB_Y = 0.15f
        private const val SIDE_X = 6.6f
        private const val LAMP_X = 6.0f
        private val SKY = floatArrayOf(0.925f, 0.933f, 0.949f)
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        private val GROUND = floatArrayOf(0.86f, 0.87f, 0.885f, 1f)
        private val SHADOW = floatArrayOf(0f, 0f, 0f, 0.26f)

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
