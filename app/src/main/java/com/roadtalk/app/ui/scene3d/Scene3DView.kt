package com.roadtalk.app.ui.scene3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 내 차 뒤 위에서 본 3D 도로 화면 (OpenGL ES 2.0, 추가 라이브러리 없음).
 * 그리는 것: 3차선 도로(10m 토막을 이어 붙임) · 인도의 나무와 가로등 · 지도에서 가져온 주변 건물 · 내 차 · 주변 Car-ind CHAT 차 ·
 * 바닥 그림자 · 선택한 차의 빛 고리.
 * 모델은 assets/scene3d 의 .mesh 파일 (tools/assets/convert_assets.py 가 만든다), 색은 작은 팔레트 그림에서 가져온다.
 * 화면에 무엇을 그릴지는 [frame] 에 넣어 주면 다음 프레임부터 반영된다.
 */
class Scene3DView(context: Context) : GLSurfaceView(context) {

    /** 주변 차 한 대. lateral/forward = 내 차 기준 오른쪽/앞 (m), headingDeg = 내 진행 방향 기준 시계 방향 각도 */
    data class Car(val lateral: Float, val forward: Float, val headingDeg: Float, val type: Int, val skin: Int, val selected: Boolean)

    /**
     * 지도에서 가져온 건물 하나를 감싸는 네모 상자. east/north = 기준점에서 동쪽/북쪽으로 몇 m (상자 가운데),
     * bearingDeg = 상자의 가로(width) 변이 향하는 방위 (0 = 북, 90 = 동), height = 높이 (m)
     */
    data class Building(val id: Long, val east: Float, val north: Float, val width: Float, val depth: Float, val bearingDeg: Float, val height: Float)

    /** 건물과 같은 기준점에서 본 내 차 위치 (m) · 방위 · 속도 · 그 값이 측정된 시각 */
    data class Ego(val east: Float, val north: Float, val headingDeg: Float, val speedMps: Float, val atMillis: Long)

    data class Frame(
        val cars: List<Car> = emptyList(),
        val myType: Int = 0,
        val mySkin: Int = 0,
        /** 화면 아래쪽에 들어와야 하는 뒤쪽 거리 (m). 카메라 높이를 정한다 */
        val rearM: Float = 7f,
        /** 내 차 속도 (m/s). 도로가 이 속도로 뒤로 흘러간다. 모르면 0 */
        val speedMps: Float = 0f,
        val buildings: List<Building> = emptyList(),
        /** 건물을 그리려면 필요. 없으면 건물은 그리지 않는다 */
        val ego: Ego? = null,
    )

    @Volatile var frame = Frame()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)      // 그림자가 겹쳐 진해지지 않게 스텐실 버퍼를 쓴다
        setRenderer(SceneRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class SceneRenderer : Renderer {
        private val kit = GlKit(context)
        private var cars = emptyList<GlKit.Mesh>()
        private var skins = IntArray(0)
        private var road: GlKit.Mesh? = null
        private var tree: GlKit.Mesh? = null
        private var lamp: GlKit.Mesh? = null
        private var quad: GlKit.Mesh? = null
        private var cityTexture = 0
        private var ringTexture = 0
        /** 건물 모델 (바닥이 1m × 1m, height = 높이 ÷ 긴 바닥 변). 납작한 것부터 길쭉한 것 순. 없으면 건물은 안 그린다 */
        private var buildingMeshes = listOf<GlKit.Mesh>()
        private var buildingTexture = 0

        private var width = 1
        private var height = 1
        private var lastNanos = 0L
        /** 화면이 얼마나 자주 그려지는지 (5초마다 로그로 남긴다) */
        private var frames = 0
        private var framesSince = 0L
        private var buildingsDrawn = 0
        /** 도로가 뒤로 흘러간 거리 (m, 0 ~ SCROLL_PERIOD) */
        private var scroll = 0f
        /** 건물 기준 내 차의 부드럽게 이어 붙인 위치·방위 (GPS 는 1초에 한 번이라 그대로 쓰면 건물이 뚝뚝 끊긴다) */
        private var egoEast = 0f
        private var egoNorth = 0f
        private var egoHeading = 0f
        private var egoReady = false
        /** 도로 위에 걸친 건물을 어느 쪽으로 밀어냈는지 (한번 정하면 그대로 둬서 좌우로 튀지 않게) */
        private val pushedSide = HashMap<Long, Float>()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            kit.init()
            val loaded = kit.loadCars()
            cars = loaded.first
            skins = loaded.second
            try {
                road = kit.loadMesh("road.mesh")
                tree = kit.loadMesh("tree.mesh")
                lamp = kit.loadMesh("lamp.mesh")
                cityTexture = kit.loadTexture(kit.assetBitmap("city.png"))
            } catch (e: Exception) {
                Log.e(GlKit.TAG, "도로 모델을 읽지 못했어요 (README '3D 에셋' 참고)", e)
            }
            buildingMeshes = try {
                val names = context.assets.list(GlKit.DIR).orEmpty().filter { it.startsWith("bld_") }.sorted()
                val meshes = names.map { kit.loadMesh(it) }.sortedBy { it.height }
                if (meshes.isNotEmpty()) buildingTexture = kit.loadTexture(kit.assetBitmap("buildings.png"))
                meshes
            } catch (e: Exception) {
                Log.w(GlKit.TAG, "건물 모델이 없어 건물은 그리지 않아요")
                emptyList()
            }
            ringTexture = kit.loadTexture(ringBitmap(), smooth = true)
            quad = kit.groundQuad()
            Log.d(GlKit.TAG, "3D scene ready: ${cars.size} car models, ${skins.size} skins, ${buildingMeshes.size} building models")
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
            frames++
            if (now - framesSince > 5_000_000_000L) {
                if (framesSince != 0L) Log.d(GlKit.TAG, "fps=%.0f buildings=%d".format(frames * 1e9f / (now - framesSince), buildingsDrawn))
                frames = 0
                framesSince = now
            }
            val f = frame
            scroll = (scroll + f.speedMps.coerceIn(0f, 60f) * dt) % SCROLL_PERIOD

            val camera = Camera3D(width.toFloat(), height.toFloat(), f.rearM)
            val vp = camera.viewProjection
            GLES20.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
            GLES20.glClearStencil(0)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
            kit.begin(camera.eye, GlKit.LIGHT, camera.fogStart, camera.fogEnd, SKY)

            // 도로 밖 땅
            kit.draw(quad, kit.white, vp, Mat4.multiply(Mat4.translation(0f, -0.03f, 0f), Mat4.scale(4000f, 1f, 4000f)), GROUND)

            // 도로 · 나무 · 가로등: 카메라 뒤부터 안개 끝까지, 20m 무늬가 scroll 만큼 뒤로 흘러간다
            val zNear = ceil((camera.eye[2] + 10f) / SCROLL_PERIOD) * SCROLL_PERIOD
            val zFar = -camera.fogEnd
            val scenery = ArrayList<Pair<GlKit.Mesh?, FloatArray>>()
            var z = zNear + scroll
            while (z > zFar) {
                kit.draw(road, cityTexture, vp, Mat4.translation(0f, 0f, z), GlKit.WHITE)
                kit.draw(road, cityTexture, vp, Mat4.translation(0f, 0f, z - 10f), GlKit.WHITE)
                scenery += tree to Mat4.translation(-SIDE_X, CURB_Y, z - 3f)
                scenery += tree to Mat4.translation(SIDE_X, CURB_Y, z - 8f)
                scenery += tree to Mat4.translation(-SIDE_X, CURB_Y, z - 13f)
                scenery += tree to Mat4.translation(SIDE_X, CURB_Y, z - 18f)
                scenery += lamp to Mat4.translation(-LAMP_X, CURB_Y, z)
                scenery += lamp to Mat4.multiply(Mat4.translation(LAMP_X, CURB_Y, z - 10f), Mat4.rotationY(180f))
                z -= SCROLL_PERIOD
            }
            scenery.forEach { (mesh, model) -> kit.draw(mesh, cityTexture, vp, model, GlKit.WHITE) }

            drawBuildings(f, dt, camera, vp)

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
            val roadShadow = Mat4.shadow(GlKit.LIGHT, 0.03f)
            val curbShadow = Mat4.shadow(GlKit.LIGHT, CURB_Y + 0.03f)
            kit.draw(cars.getOrNull(f.myType), kit.white, vp, Mat4.multiply(roadShadow, myModel), SHADOW, unlit = true)
            others.forEach { (car, model) -> kit.draw(cars.getOrNull(car.type), kit.white, vp, Mat4.multiply(roadShadow, model), SHADOW, unlit = true) }
            scenery.forEach { (mesh, model) -> kit.draw(mesh, kit.white, vp, Mat4.multiply(curbShadow, model), SHADOW, unlit = true) }
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)

            // 선택한 차 아래의 파란 빛 고리
            others.filter { it.first.selected }.forEach { (car, model) ->
                val mesh = cars.getOrNull(car.type)
                val w = (mesh?.halfX ?: 1f) * 2f + 2.2f
                val l = (mesh?.halfZ ?: 2.3f) * 2f + 3f
                val ring = Mat4.multiply(model, Mat4.multiply(Mat4.translation(0f, 0.05f, 0f), Mat4.scale(w, 1f, l)))
                kit.draw(quad, ringTexture, vp, ring, GlKit.WHITE, unlit = true)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)

            kit.draw(cars.getOrNull(f.myType), skinTexture(f.mySkin), vp, myModel, GlKit.WHITE)
            others.forEach { (car, model) -> kit.draw(cars.getOrNull(car.type), skinTexture(car.skin), vp, model, GlKit.WHITE) }
        }

        /**
         * 지도 건물을 내 차 기준 자리에 세운다. 우리 도로는 실제 도로 모양이 아니라 내 진행 방향으로 곧게 그린 3차선이라,
         * 그 위에 걸치는 건물은 가까운 쪽 길가로 밀어낸다.
         */
        private fun drawBuildings(f: Frame, dt: Float, camera: Camera3D, vp: FloatArray) {
            buildingsDrawn = 0
            val ego = f.ego ?: return
            if (buildingMeshes.isEmpty() || f.buildings.isEmpty()) return
            // 마지막 위치 측정 뒤 달린 만큼 앞으로 옮긴 자리를 목표로, 부드럽게 따라간다
            val age = ((System.currentTimeMillis() - ego.atMillis) / 1000f).coerceIn(0f, 1.5f)
            val h = Math.toRadians(ego.headingDeg.toDouble())
            val targetEast = ego.east + sin(h).toFloat() * ego.speedMps * age
            val targetNorth = ego.north + cos(h).toFloat() * ego.speedMps * age
            if (!egoReady || abs(targetEast - egoEast) + abs(targetNorth - egoNorth) > 60f) {
                egoEast = targetEast
                egoNorth = targetNorth
                egoHeading = ego.headingDeg
                egoReady = true
            } else {
                val k = 1f - exp(-dt / 0.3f)
                egoEast += (targetEast - egoEast) * k
                egoNorth += (targetNorth - egoNorth) * k
                var dh = (ego.headingDeg - egoHeading) % 360f
                if (dh > 180f) dh -= 360f
                if (dh < -180f) dh += 360f
                egoHeading += dh * k
            }
            val heading = Math.toRadians(egoHeading.toDouble())
            val ch = cos(heading).toFloat()
            val sh = sin(heading).toFloat()
            val far = camera.fogEnd
            val near = camera.eye[2] + 30f
            var drawn = 0
            if (pushedSide.size > 4000) pushedSide.clear()
            for (b in f.buildings) {
                val de = b.east - egoEast
                val dn = b.north - egoNorth
                val forward = dn * ch + de * sh
                var right = de * ch - dn * sh
                if (forward > far || forward < -near || abs(right) > far) continue
                // 메시의 +X(가로) 가 건물의 가로 변 방위를 향하도록 돌린다
                var rotation = 90f - (b.bearingDeg - egoHeading)
                val r = Math.toRadians(rotation.toDouble())
                val halfAcross = (abs(cos(r)) * b.width / 2 + abs(sin(r)) * b.depth / 2).toFloat()
                if (abs(right) - halfAcross < ROAD_HALF_M) {
                    val side = pushedSide.getOrPut(b.id) { if (right >= 0f) 1f else -1f }
                    right = side * (ROAD_HALF_M + halfAcross)
                }
                // 건물 정면(메시의 +Z)이 도로 쪽을 보게: 네 방향 중 도로를 가장 똑바로 보는 쪽으로 돌린다 (90도 돌리면 가로·세로가 바뀐다)
                var across = b.width
                var along = b.depth
                var best = Float.MAX_VALUE
                var turns = 0
                for (k in 0 until 4) {
                    val facing = sin(Math.toRadians((rotation + 90f * k).toDouble())).toFloat() * (if (right >= 0f) 1f else -1f)
                    if (facing < best) {
                        best = facing
                        turns = k
                    }
                }
                rotation += 90f * turns
                if (turns % 2 == 1) {
                    across = b.depth
                    along = b.width
                }
                // 생김새(높이 ÷ 긴 변)가 비슷한 모델 몇 개 중에서 건물마다 하나를 정해 쓴다
                val shape = b.height / maxOf(b.width, b.depth)
                var nearest = 0
                for (i in buildingMeshes.indices) {
                    if (abs(buildingMeshes[i].height - shape) < abs(buildingMeshes[nearest].height - shape)) nearest = i
                }
                val pick = (nearest + ((b.id and 0x7FFFFFFF) % 3).toInt() - 1).coerceIn(0, buildingMeshes.size - 1)
                val mesh = buildingMeshes[pick]
                val model = Mat4.multiply(
                    Mat4.translation(right, 0f, -forward),
                    Mat4.multiply(Mat4.rotationY(rotation), Mat4.scale(across, b.height / mesh.height, along)),
                )
                kit.draw(mesh, buildingTexture, vp, model, GlKit.WHITE)
                buildingsDrawn = ++drawn
                if (drawn >= MAX_BUILDINGS) break
            }
        }

        private fun skinTexture(skin: Int) = if (skins.isEmpty()) kit.white else skins[skin.mod(skins.size)]

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
    }

    companion object {
        private const val SCROLL_PERIOD = 20f
        /** 인도 높이, 나무·가로등을 세우는 좌우 위치 (m) */
        private const val CURB_Y = 0.15f
        private const val SIDE_X = 6.6f
        private const val LAMP_X = 6.0f
        /** 도로 가운데에서 인도 바깥 끝까지 (m). 건물은 이보다 안쪽으로 들어오지 않는다 */
        private const val ROAD_HALF_M = 8.2f
        private const val MAX_BUILDINGS = 220
        private val SKY = floatArrayOf(0.925f, 0.933f, 0.949f)
        private val GROUND = floatArrayOf(0.86f, 0.87f, 0.885f, 1f)
        private val SHADOW = floatArrayOf(0f, 0f, 0f, 0.26f)
    }
}
