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
import com.roadtalk.app.map.CityMesh
import com.roadtalk.app.map.OsmRoads
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 내 차 뒤 위에서 본 3D 도로 화면 (OpenGL ES 2.0, 추가 라이브러리 없음).
 * 그리는 것:
 *  - 내가 달리는 길: 지도에 적힌 차로 수대로 곧게 (양방향 도로면 가운데 노란 중앙선 왼쪽이 맞은편 차로), 인도의 나무·가로등
 *  - 지도(OpenStreetMap)에서 가져온 것: 내 길과 만나는 길·주변 길, 횡단보도, 신호등, 실제 윤곽 모양대로 세운 건물
 *  - 내 차, 주변 Car-ind CHAT 차, 바닥 그림자, 선택한 차의 빛 고리
 * 차·도로·나무·신호등 모델은 assets/scene3d 의 .mesh 파일 (tools/assets/convert_assets.py 가 만든다).
 * 화면에 무엇을 그릴지는 [frame] 에 넣어 주면 다음 프레임부터 반영된다.
 */
class Scene3DView(context: Context) : GLSurfaceView(context) {

    /** 주변 차 한 대. lateral/forward = 내 차 기준 오른쪽/앞 (m), headingDeg = 내 진행 방향 기준 시계 방향 각도 */
    data class Car(val lateral: Float, val forward: Float, val headingDeg: Float, val type: Int, val skin: Int, val selected: Boolean)

    /** 지도 자료와 같은 기준점에서 본 내 차 위치 (m) · 방위 · 속도 · 그 값이 측정된 시각 */
    data class Ego(val east: Float, val north: Float, val headingDeg: Float, val speedMps: Float, val atMillis: Long)

    data class Frame(
        val cars: List<Car> = emptyList(),
        val myType: Int = 0,
        val mySkin: Int = 0,
        /** 화면 아래쪽에 들어와야 하는 뒤쪽 거리 (m). 카메라 높이를 정한다 */
        val rearM: Float = 7f,
        /** 내 차 속도 (m/s). 도로가 이 속도로 뒤로 흘러간다. 모르면 0 */
        val speedMps: Float = 0f,
        /** 지도에서 가져온 건물 · 도로 · 횡단보도 · 신호등 (기준점 기준 좌표). 그리려면 [ego] 가 있어야 한다 */
        val city: CityMesh? = null,
        val roads: List<OsmRoads.Road> = emptyList(),
        val crossings: List<OsmRoads.Spot> = emptyList(),
        val signals: List<OsmRoads.Spot> = emptyList(),
        val ego: Ego? = null,
    )

    @Volatile var frame = Frame()

    /**
     * 지금 그리고 있는 내 길의 차로 배치: 길 가운데가 내 차에서 오른쪽으로 몇 m 인지, 차로 수.
     * 주변 차를 차로 가운데에 맞춰 놓는 쪽([Lanes])이 읽는다 (그리는 스레드가 프레임마다 써 넣는다)
     */
    @Volatile var laneCentre = 0f
        private set
    @Volatile var laneCount = DEFAULT_LANES
        private set

    init {
        setEGLContextClientVersion(2)
        // 깊이 24비트: 도로 위에 얇게 겹쳐 까는 것(차선·교차로·횡단보도)이 멀리서 깜빡이지 않게. 스텐실: 그림자가 겹쳐 진해지지 않게
        setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        setRenderer(SceneRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class SceneRenderer : Renderer {
        private val kit = GlKit(context)
        private var cars = emptyList<GlKit.Mesh>()
        private var skins = IntArray(0)
        /** 도로 10m 토막의 부품: 왼쪽 인도 쪽, 차로 사이(점선), 차로 사이(선 없음), 오른쪽 인도 쪽 */
        private var roadLeft: GlKit.Mesh? = null
        private var roadDash: GlKit.Mesh? = null
        private var roadPlain: GlKit.Mesh? = null
        private var roadRight: GlKit.Mesh? = null
        /** 나무는 줄기와 잎을 따로 그린다 (잎 색이 계절마다 다르다) */
        private var treeTrunk: GlKit.Mesh? = null
        private var treeLeaves: GlKit.Mesh? = null
        private var lamp: GlKit.Mesh? = null
        private var signal: GlKit.Mesh? = null
        private var quad: GlKit.Mesh? = null
        private var cityTexture = 0
        private var ringTexture = 0
        private var windowTexture = 0
        private var zebraTexture = 0

        /** 지도 건물 (구역이 바뀌면 다시 올린다) */
        private var cityMesh: GlKit.Mesh? = null
        private var uploadedCity: CityMesh? = null
        /** 지도 도로 (내 위치 기준이라 매 프레임 다시 만든다) */
        private var stripMesh: GlKit.Mesh? = null
        private val strips = StripBuilder()
        /** 내 길을 가로지르는 띠들 (ax az dx dz 길이 반폭). 이 자리의 나무·가로등은 그리지 않는다 */
        private var openings = FloatArray(6 * 32)
        private var openingCount = 0

        /** 내가 달리는 길: 차로 수, 중앙선이 몇 번째 차로 경계인지 (0 = 중앙선 없음: 일방통행), 길 가운데가 내 차에서 오른쪽으로 몇 m 인지 */
        private var myLanes = DEFAULT_LANES
        private var myDivider = 0
        private var myCenter = 0f
        private var myRoadKnown = false
        /** 내 차가 있는 차로 (왼쪽부터 0). 내 차는 언제나 이 차로의 가운데에 그린다 */
        private var myLane: Int? = null

        private var width = 1
        private var height = 1
        private var lastNanos = 0L
        /** 화면이 얼마나 자주 그려지는지 (5초마다 로그로 남긴다) */
        private var frames = 0
        private var framesSince = 0L
        private var buildingsDrawn = 0
        /** 도로가 뒤로 흘러간 거리 (m, 0 ~ SCROLL_PERIOD) */
        private var scroll = 0f
        /** 지도 기준 내 차의 부드럽게 이어 붙인 위치·방위 (GPS 는 1초에 한 번이라 그대로 쓰면 지도가 뚝뚝 끊긴다) */
        private var egoEast = 0f
        private var egoNorth = 0f
        private var egoHeading = 0f
        private var egoReady = false
        private var ch = 1f
        private var sh = 0f
        /** 내 길 위에 걸친 건물을 어느 쪽으로 밀어냈는지 (한번 정하면 그대로 둬서 좌우로 튀지 않게) */
        private val pushedSide = HashMap<Long, Float>()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            kit.init()
            val loaded = kit.loadCars()
            cars = loaded.first
            skins = loaded.second
            try {
                roadLeft = kit.loadMesh("road_left.mesh")
                roadDash = kit.loadMesh("road_dash.mesh")
                roadPlain = kit.loadMesh("road_plain.mesh")
                roadRight = kit.loadMesh("road_right.mesh")
                treeTrunk = kit.loadMesh("tree_trunk.mesh")
                treeLeaves = kit.loadMesh("tree_leaves.mesh")
                lamp = kit.loadMesh("lamp.mesh")
                signal = kit.loadMesh("signal.mesh")
                cityTexture = kit.loadTexture(kit.assetBitmap("city.png"))
            } catch (e: Exception) {
                Log.e(GlKit.TAG, "도로 모델을 읽지 못했어요 (README '3D 에셋' 참고)", e)
            }
            ringTexture = kit.loadTexture(ringBitmap(), smooth = true)
            windowTexture = kit.loadTexture(windowBitmap(), smooth = true, tiled = true)
            zebraTexture = kit.loadTexture(zebraBitmap(), smooth = true)
            quad = kit.groundQuad()
            stripMesh = kit.dynamicMesh()
            cityMesh = null
            uploadedCity = null
            Log.d(GlKit.TAG, "3D scene ready: ${cars.size} car models, ${skins.size} skins")
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
                if (framesSince != 0L) {
                    Log.d(GlKit.TAG, "fps=%.0f buildings=%d roadTriangles=%d lanes=%d divider=%d center=%.1f".format(
                        frames * 1e9f / (now - framesSince), buildingsDrawn, strips.size / 24, myLanes, myDivider, myCenter))
                }
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

            // 내가 달리는 길의 차로 수와 자리를 지도에서 찾고, 지도의 다른 길과 횡단보도를 깐다
            val onMap = updateEgo(f, dt)
            if (onMap) findMyRoad(f, dt)
            laneCentre = myCenter
            laneCount = myLanes
            val lanesHalf = myLanes * LANE_M / 2
            strips.setRoad(myCenter - lanesHalf - CURB_IN_M, myCenter + lanesHalf + CURB_IN_M, myCenter - lanesHalf - WALK_OUT_M, myCenter + lanesHalf + WALK_OUT_M)
            openingCount = 0
            strips.reset()
            if (onMap) {
                buildStrips(f, camera)
                kit.fill(stripMesh!!, strips.data, strips.size)
                kit.draw(stripMesh, kit.white, vp, Mat4.identity(), ASPHALT)
                drawCrossings(f, camera, vp)
            }

            // 내 길 · 나무 · 가로등: 카메라 뒤부터 안개 끝까지, 20m 무늬가 scroll 만큼 뒤로 흘러간다
            val zNear = ceil((camera.eye[2] + 10f) / SCROLL_PERIOD) * SCROLL_PERIOD
            val zFar = -camera.fogEnd
            val scenery = ArrayList<Pair<GlKit.Mesh?, FloatArray>>()
            fun plant(mesh: GlKit.Mesh?, x: Float, z: Float, turned: Boolean) {
                if (inOpening(x, z)) return       // 교차하는 길 위에는 세우지 않는다
                val at = Mat4.translation(x, CURB_Y, z)
                scenery += mesh to (if (turned) Mat4.multiply(at, Mat4.rotationY(180f)) else at)
            }
            val treeLeft = strips.walkMin + TREE_INSET_M
            val treeRight = strips.walkMax - TREE_INSET_M
            val lampLeft = strips.walkMin + LAMP_INSET_M
            val lampRight = strips.walkMax - LAMP_INSET_M
            var z = zNear + scroll
            while (z > zFar) {
                drawRoadRow(vp, z)
                drawRoadRow(vp, z - 10f)
                plant(treeTrunk, treeLeft, z - 3f, false)
                plant(treeTrunk, treeRight, z - 8f, false)
                plant(treeTrunk, treeLeft, z - 13f, false)
                plant(treeTrunk, treeRight, z - 18f, false)
                plant(lamp, lampLeft, z, false)
                plant(lamp, lampRight, z - 10f, true)
                z -= SCROLL_PERIOD
            }
            if (myDivider > 0) {
                // 중앙선: 노란 줄 두 개 (왼쪽이 맞은편 차로). 교차로 안에서는 교차하는 길에 가려 끊긴다
                val x = myCenter + (myDivider - myLanes / 2f) * LANE_M
                for (side in floatArrayOf(-0.13f, 0.13f)) {
                    val line = Mat4.multiply(Mat4.translation(x + side, 0.016f, (zNear + zFar) / 2), Mat4.scale(0.13f, 1f, zNear - zFar))
                    kit.draw(quad, kit.white, vp, line, YELLOW)
                }
            }
            // 나무는 줄기 자리에 잎을 같이 그린다. 잎은 계절 색 (여름은 모델의 원래 초록)
            val leafTint = Seasons.leafTint(java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1)
            scenery.forEach { (mesh, model) ->
                kit.draw(mesh, cityTexture, vp, model, GlKit.WHITE)
                if (mesh === treeTrunk) {
                    if (leafTint == null) kit.draw(treeLeaves, cityTexture, vp, model, GlKit.WHITE) else kit.draw(treeLeaves, kit.white, vp, model, leafTint)
                }
            }

            buildingsDrawn = 0
            if (onMap) {
                drawSignals(f, camera, vp)
                drawBuildings(f, camera, vp)
            }

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
            val roadShadow = Mat4.shadow(GlKit.LIGHT, SHADOW_Y)
            val curbShadow = Mat4.shadow(GlKit.LIGHT, CURB_Y + 0.03f)
            kit.draw(cars.getOrNull(f.myType), kit.white, vp, Mat4.multiply(roadShadow, myModel), SHADOW, unlit = true)
            others.forEach { (car, model) -> kit.draw(cars.getOrNull(car.type), kit.white, vp, Mat4.multiply(roadShadow, model), SHADOW, unlit = true) }
            scenery.forEach { (mesh, model) ->
                val flat = Mat4.multiply(curbShadow, model)
                kit.draw(mesh, kit.white, vp, flat, SHADOW, unlit = true)
                if (mesh === treeTrunk) kit.draw(treeLeaves, kit.white, vp, flat, SHADOW, unlit = true)
            }
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)

            // 선택한 차 아래의 파란 빛 고리
            others.filter { it.first.selected }.forEach { (car, model) ->
                val mesh = cars.getOrNull(car.type)
                val w = (mesh?.halfX ?: 1f) * 2f + 2.2f
                val l = (mesh?.halfZ ?: 2.3f) * 2f + 3f
                val ring = Mat4.multiply(model, Mat4.multiply(Mat4.translation(0f, SHADOW_Y + 0.02f, 0f), Mat4.scale(w, 1f, l)))
                kit.draw(quad, ringTexture, vp, ring, GlKit.WHITE, unlit = true)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)

            kit.draw(cars.getOrNull(f.myType), skinTexture(f.mySkin), vp, myModel, GlKit.WHITE)
            others.forEach { (car, model) -> kit.draw(cars.getOrNull(car.type), skinTexture(car.skin), vp, model, GlKit.WHITE) }
        }

        /** 내 길 10m 한 줄: 왼쪽 부품, 차로 경계마다 점선(중앙선 자리는 선 없는 부품), 오른쪽 부품 */
        private fun drawRoadRow(vp: FloatArray, z: Float) {
            val n = myLanes
            kit.draw(roadLeft, cityTexture, vp, Mat4.translation(myCenter - (n - 1) * LANE_M / 2, 0f, z), GlKit.WHITE)
            for (i in 1 until n) {
                val part = if (i == myDivider) roadPlain else roadDash
                kit.draw(part, cityTexture, vp, Mat4.translation(myCenter + (i - n / 2f) * LANE_M, 0f, z), GlKit.WHITE)
            }
            kit.draw(roadRight, cityTexture, vp, Mat4.translation(myCenter + (n - 1) * LANE_M / 2, 0f, z), GlKit.WHITE)
        }

        /**
         * 지도 위의 내 위치를 부드럽게 이어 붙인다: 마지막 측정 뒤 달린 만큼 앞으로 옮긴 자리를 목표로 따라간다.
         * 지도 자료나 내 위치가 없으면 false (지도는 그리지 않는다)
         */
        private fun updateEgo(f: Frame, dt: Float): Boolean {
            val ego = f.ego ?: return false
            if (f.city == null && f.roads.isEmpty()) return false
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
            ch = cos(heading).toFloat()
            sh = sin(heading).toFloat()
            return true
        }

        /** 지도 좌표 (동, 북) → 내 차 기준 오른쪽 (x) */
        private fun rightOf(east: Float, north: Float) = (east - egoEast) * ch - (north - egoNorth) * sh

        /** 지도 좌표 (동, 북) → 내 차 기준 앞 (화면 좌표로는 z = −앞) */
        private fun forwardOf(east: Float, north: Float) = (north - egoNorth) * ch + (east - egoEast) * sh

        /**
         * 내가 달리는 길을 지도에서 찾는다: 내 진행 방향과 거의 나란하고(약 25도 안), 내 곁을 지나고, 중심선이 가장 가까운 길.
         * 그 길의 차로 수와 일방통행 여부로 내 길을 그리고, 중심선이 내 차에서 떨어진 만큼에 가장 가까운 차로에 내 차가 오도록
         * 길을 옆으로 놓는다 (내 차는 언제나 차로 가운데에 놓인다). 못 찾으면 마지막에 찾은 모습을 그대로 둔다.
         */
        private fun findMyRoad(f: Frame, dt: Float) {
            var best: OsmRoads.Road? = null
            var bestX = 0f
            var bestAbs = Float.MAX_VALUE
            for (road in f.roads) {
                val p = road.points
                var ax = rightOf(p[0], p[1])
                var az = -forwardOf(p[0], p[1])
                for (i in 1 until p.size / 2) {
                    val bx = rightOf(p[i * 2], p[i * 2 + 1])
                    val bz = -forwardOf(p[i * 2], p[i * 2 + 1])
                    val sx = ax
                    val sz = az
                    ax = bx
                    az = bz
                    if (minOf(sz, bz) > ALONGSIDE_M || maxOf(sz, bz) < -ALONGSIDE_M) continue       // 내 옆을 지나지 않는다
                    val length = hypot(bx - sx, bz - sz)
                    if (length < 0.5f || abs((bx - sx) / length) >= PARALLEL_SIN) continue
                    // 이 구간이 내 바로 옆(z = 0)을 지날 때의 좌우 위치
                    val t = if (abs(bz - sz) < 0.01f) 0.5f else ((0f - sz) / (bz - sz)).coerceIn(0f, 1f)
                    val x = sx + (bx - sx) * t
                    if (abs(x) < bestAbs && abs(x) < road.width / 2 + MY_ROAD_MARGIN_M) {
                        best = road
                        bestX = x
                        bestAbs = abs(x)
                    }
                }
            }
            val road = best ?: return
            val lanes = road.lanes.coerceIn(1, MAX_LANES)
            val divider = if (road.oneway || lanes < 2) 0 else lanes / 2
            // 내 차는 차선을 밟지 않고 차로 가운데로 달린다: 지도상의 내 자리와 가장 가까운 차로를 고르고
            // (양방향 도로면 중앙선 오른쪽 차로 중에서), 그 차로의 가운데가 내 차 자리에 오도록 길을 옆으로 놓는다
            val lane = Lanes.laneOf(0f, bestX, lanes, myLane.takeIf { myRoadKnown && lanes == myLanes }, first = divider)
            myLane = lane
            myLanes = lanes
            myDivider = divider
            val target = Lanes.roadCentreFor(lane, lanes)
            if (!myRoadKnown) {
                myCenter = target
                myRoadKnown = true
            } else {
                myCenter += (target - myCenter) * (1f - exp(-dt / 0.6f))
            }
        }

        /**
         * 지도 도로를 바닥 띠로 만든다. 내가 달리는 길(나란하고 내 길 차도 안을 지나는 구간)은 이미 그리고 있으니 빼고,
         * 내 길과 만나는 길은 인도·차선을 덮어 깔고, 나란한 다른 길(맞은편 차도 등)은 내 길 바깥에만 깐다.
         */
        private fun buildStrips(f: Frame, camera: Camera3D) {
            val far = camera.fogEnd
            val behind = camera.eye[2] + 40f
            val mine = myLanes * LANE_M / 2 + 1.5f
            for (road in f.roads) {
                val p = road.points
                val half = road.width / 2
                var x0 = rightOf(p[0], p[1])
                var z0 = -forwardOf(p[0], p[1])
                for (i in 1 until p.size / 2) {
                    val x1 = rightOf(p[i * 2], p[i * 2 + 1])
                    val z1 = -forwardOf(p[i * 2], p[i * 2 + 1])
                    val ax = x0
                    val az = z0
                    x0 = x1
                    z0 = z1
                    // 화면 범위 밖
                    if ((az > behind && z1 > behind) || (az < -far && z1 < -far) || (ax > far && x1 > far) || (ax < -far && x1 < -far)) continue
                    val length = hypot(x1 - ax, z1 - az)
                    if (length < 0.05f) continue
                    val dx = (x1 - ax) / length
                    val dz = (z1 - az) / length
                    if (abs(dx) < PARALLEL_SIN) {
                        val a = ax - myCenter
                        val b = x1 - myCenter
                        if (minOf(abs(a), abs(b)) < mine || a * b < 0f) continue     // 내가 달리는 길
                        strips.strip(ax, az, x1, z1, half, crossing = false)
                        continue
                    }
                    strips.strip(ax, az, x1, z1, half, crossing = true)
                    // 내 길의 인도까지 닿는 띠는 '뚫린 자리' 로 적어 둔다
                    if (minOf(ax, x1) - half < strips.walkMax + 0.7f && maxOf(ax, x1) + half > strips.walkMin - 0.7f && openingCount < openings.size / 6) {
                        val o = openingCount * 6
                        openings[o] = ax; openings[o + 1] = az; openings[o + 2] = dx; openings[o + 3] = dz
                        openings[o + 4] = length; openings[o + 5] = half
                        openingCount++
                    }
                }
            }
        }

        private fun inOpening(x: Float, z: Float): Boolean {
            for (k in 0 until openingCount) {
                val o = k * 6
                val px = x - openings[o]
                val pz = z - openings[o + 1]
                val along = px * openings[o + 2] + pz * openings[o + 3]
                val across = abs(-px * openings[o + 3] + pz * openings[o + 2])
                val half = openings[o + 5]
                if (along > -half && along < openings[o + 4] + half && across < half + 1.6f) return true
            }
            return false
        }

        /** 지도의 한 점(횡단보도·신호등)이 내가 달리는 길 위의 것인지: 그 도로가 내 진행 방향과 나란하고 내 길 곁에 있다 */
        private fun onMyRoad(spot: OsmRoads.Spot, x: Float): Boolean {
            val relative = spot.bearingDeg - egoHeading
            return abs(sin(Math.toRadians(relative.toDouble()))) < PARALLEL_SIN &&
                x > strips.walkMin - MY_ROAD_MARGIN_M && x < strips.walkMax + MY_ROAD_MARGIN_M
        }

        /**
         * 횡단보도. 내 길을 건너는 것은 내 차도 폭에 맞춰 가로로 깔고, 다른 길의 것은 그 길의 방향·폭에 맞춘다.
         * 흰 줄은 차가 달리는 방향과 나란하다.
         */
        private fun drawCrossings(f: Frame, camera: Camera3D, vp: FloatArray) {
            val far = camera.fogEnd
            val behind = camera.eye[2] + 20f
            for (c in f.crossings) {
                val x = rightOf(c.east, c.north)
                val z = -forwardOf(c.east, c.north)
                if (z > behind || z < -far || abs(x) > far) continue
                val model = if (onMyRoad(c, x)) {
                    Mat4.multiply(Mat4.translation(myCenter, StripBuilder.ON_LANES_Y + 0.012f, z), Mat4.scale(myLanes * LANE_M, 1f, CROSSING_DEPTH_M))
                } else {
                    Mat4.multiply(
                        Mat4.translation(x, strips.heightAt(x) + 0.012f, z),
                        Mat4.multiply(Mat4.rotationY(-(c.bearingDeg - egoHeading)), Mat4.scale(c.roadWidth, 1f, CROSSING_DEPTH_M)),
                    )
                }
                kit.draw(quad, zebraTexture, vp, model, GlKit.WHITE)
            }
        }

        /**
         * 신호등: 지도에 표시된 자리 가운데 내가 달리는 길 위의 것만, 오른쪽 인도에 기둥을 세우고 팔을 차도 위로 뻗는다.
         * 지도에는 위치만 있고 지금 무슨 색인지는 없어서 불은 꺼진 모습이다. 한 교차로에 여러 개 표시된 것은 하나만 세운다.
         */
        private fun drawSignals(f: Frame, camera: Camera3D, vp: FloatArray) {
            val mesh = signal ?: return
            val far = camera.fogEnd
            var placedCount = 0
            for (s in f.signals) {
                val x = rightOf(s.east, s.north)
                val z = -forwardOf(s.east, s.north)
                // 교차로의 신호등은 지도에서 어느 길 위의 점으로 적혔는지와 상관없이, 내 길 곁에 있으면 내 길의 신호등으로 본다
                if (z > camera.eye[2] || z < -far || x < strips.walkMin - SIGNAL_REACH_M || x > strips.walkMax + SIGNAL_REACH_M) continue
                var duplicate = false
                for (k in 0 until placedCount) if (abs(placedZ[k] - z) < SIGNAL_GAP_M) duplicate = true
                if (duplicate || placedCount >= placedZ.size) continue
                placedZ[placedCount++] = z
                // 모델은 기둥이 왼쪽(−x), 팔이 오른쪽으로 뻗고 등이 +z(다가오는 쪽)를 본다 → 좌우를 뒤집어 기둥을 오른쪽 인도에 둔다
                val pole = strips.lanesMax + 0.8f
                val model = Mat4.multiply(Mat4.translation(pole - mesh.halfX, CURB_Y, z), Mat4.scale(-1f, 1f, 1f))
                kit.draw(mesh, cityTexture, vp, model, GlKit.WHITE)
            }
        }

        private val placedZ = FloatArray(24)

        /**
         * 지도 건물을 실제 윤곽 모양대로, 실제 자리에 세운다. 우리 '내 길' 은 곧게 그리기 때문에 실제 도로와 어긋날 수 있어서,
         * 그 위에 걸치는 건물만 걸친 만큼 길가로 밀어낸다.
         */
        private fun drawBuildings(f: Frame, camera: Camera3D, vp: FloatArray) {
            val city = f.city ?: return
            if (city !== uploadedCity) {
                kit.delete(cityMesh)
                cityMesh = if (city.vertices.isEmpty()) null else kit.upload(city.vertices)
                uploadedCity = city
                pushedSide.clear()
                Log.d(GlKit.TAG, "city mesh: ${city.buildings.size} buildings, ${city.vertices.size / 24} triangles")
            }
            val mesh = cityMesh ?: return
            // 지도 좌표 (x = 동, z = −북) → 내 차 기준: 내 위치를 원점으로 옮기고 내 방위만큼 돌린다
            val base = Mat4.multiply(Mat4.rotationY(egoHeading), Mat4.translation(-egoEast, 0f, egoNorth))
            val far = camera.fogEnd
            val behind = camera.eye[2] + 30f
            val lo = strips.walkMin - 0.3f
            val hi = strips.walkMax + 0.3f
            var drawn = 0
            for (b in city.buildings) {
                val forward = forwardOf(b.east, b.north)
                val right = rightOf(b.east, b.north)
                if (forward - b.radius > far || forward + b.radius < -behind || abs(right) - b.radius > far) continue
                var shift = 0f
                if (right - b.radius < hi && right + b.radius > lo) {
                    // 윤곽이 내 길에 얼마나 걸치는지 꼭짓점으로 잰다
                    var minRight = Float.MAX_VALUE
                    var maxRight = -Float.MAX_VALUE
                    val ring = b.ring
                    for (i in 0 until ring.size / 2) {
                        val r = rightOf(ring[i * 2], ring[i * 2 + 1])
                        minRight = minOf(minRight, r)
                        maxRight = maxOf(maxRight, r)
                    }
                    if (minRight < hi && maxRight > lo) {
                        val side = pushedSide.getOrPut(b.id) { if (right >= myCenter) 1f else -1f }
                        shift = if (side > 0f) hi - minRight else lo - maxRight
                    }
                }
                val model = if (shift == 0f) base else Mat4.multiply(Mat4.translation(shift, 0f, 0f), base)
                val tone = TONES[((b.id and 0x7FFFFFFF) % TONES.size).toInt()]
                kit.draw(mesh, windowTexture, vp, model, tone, first = b.first, count = b.wallCount)
                kit.draw(mesh, kit.white, vp, model, ROOF, first = b.first + b.wallCount, count = b.roofCount)
                if (++drawn >= MAX_BUILDINGS) break
            }
            buildingsDrawn = drawn
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

        /** 건물 벽에 되풀이해 까는 무늬: 가로 4m · 세로 한 층(3.2m) 칸마다 창문 하나. 바탕은 흰색이라 건물마다 다른 색을 입힐 수 있다 */
        private fun windowBitmap(): Bitmap {
            val size = 64
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFFFFFFF.toInt())
            val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C7F96.toInt() }
            canvas.drawRect(12f, 14f, 52f, 46f, glass)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8DA0B6.toInt() }
            canvas.drawRect(12f, 14f, 52f, 22f, shine)
            return bitmap
        }

        /** 횡단보도: 아스팔트 바탕에 흰 줄 8개 (줄은 그림의 세로 방향 = 차가 달리는 방향) */
        private fun zebraBitmap(): Bitmap {
            val w = 256
            val h = 32
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF4A4C50.toInt())
            val white = Paint().apply { color = 0xFFF0F0EA.toInt() }
            for (i in 0 until 8) canvas.drawRect(i * 32f + 7f, 0f, i * 32f + 25f, h.toFloat(), white)
            return bitmap
        }
    }

    companion object {
        private const val SCROLL_PERIOD = 20f
        /** 차로 폭 (m) 과, 지도에서 내 길을 못 찾았을 때 그리는 차로 수, 그릴 수 있는 최대 차로 수 */
        private const val LANE_M = 3.5f
        private const val DEFAULT_LANES = 3
        private const val MAX_LANES = 10
        /** 차도 끝에서 인도 턱까지, 인도 바깥 끝까지 (m). 도로 부품(road_left/right)의 모양과 맞춘 값 */
        private const val CURB_IN_M = 0.39f
        private const val WALK_OUT_M = 2.06f
        /** 인도 높이, 나무·가로등을 인도 바깥 끝에서 얼마나 안쪽에 세우는지 (m) */
        private const val CURB_Y = 0.15f
        private const val TREE_INSET_M = 0.71f
        private const val LAMP_INSET_M = 1.31f
        private const val MAX_BUILDINGS = 320
        /** 내 진행 방향과 이 각(약 25도)보다 덜 벌어진 지도 도로는 '나란한 길' 로 본다 */
        private const val PARALLEL_SIN = 0.42f
        /** 내 길을 찾을 때: 내 앞뒤 이 거리 안을 지나는 구간만 보고, 중심선이 '그 길 폭의 절반 + 이 여유' 안에 있어야 한다 (m) */
        private const val ALONGSIDE_M = 20f
        private const val MY_ROAD_MARGIN_M = 8f
        /** 횡단보도의 두께, 한 교차로의 신호등으로 묶는 거리 (m) */
        private const val CROSSING_DEPTH_M = 4f
        private const val SIGNAL_GAP_M = 30f
        private const val SIGNAL_REACH_M = 14f
        /** 차 그림자를 까는 높이: 교차로 띠와 횡단보도보다 위 */
        private const val SHADOW_Y = 0.07f
        private val SKY = floatArrayOf(0.925f, 0.933f, 0.949f)
        private val GROUND = floatArrayOf(0.86f, 0.87f, 0.885f, 1f)
        private val SHADOW = floatArrayOf(0f, 0f, 0f, 0.26f)
        /** 내 길 토막의 아스팔트와 같은 색 */
        private val ASPHALT = floatArrayOf(0.29f, 0.298f, 0.314f, 1f)
        private val YELLOW = floatArrayOf(0.98f, 0.80f, 0.20f, 1f)
        private val ROOF = floatArrayOf(0.80f, 0.81f, 0.83f, 1f)
        /** 건물 벽 색 (건물마다 하나) */
        private val TONES = arrayOf(
            floatArrayOf(0.93f, 0.94f, 0.96f, 1f),
            floatArrayOf(0.95f, 0.92f, 0.87f, 1f),
            floatArrayOf(0.84f, 0.87f, 0.92f, 1f),
            floatArrayOf(0.90f, 0.90f, 0.90f, 1f),
            floatArrayOf(0.88f, 0.91f, 0.88f, 1f),
            floatArrayOf(0.97f, 0.95f, 0.93f, 1f),
        )
    }
}
