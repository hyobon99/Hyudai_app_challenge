package com.roadtalk.app.ui.scene3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * '내 차 고르기' 쇼룸의 3D 부분: 어두운 전시장 바닥의 회전 받침 위에서 고른 차가 천천히 돈다.
 * 차종 카드에 넣을 작은 그림도 여기서 만든다 (같은 모델을 화면 밖에서 한 번씩 그려 [onThumbnails] 로 넘김. 색이 바뀌면 다시 만든다).
 * 무엇을 보여 줄지는 [type], [skin] 에 넣는다.
 */
class GarageView(context: Context) : GLSurfaceView(context) {

    @Volatile var type = 0
    @Volatile var skin = 0
    /** (차종 번호 순서대로) 카드용 그림이 준비되면 메인 스레드에서 불린다 */
    var onThumbnails: ((List<Bitmap>) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        setRenderer(GarageRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class GarageRenderer : Renderer {
        private val kit = GlKit(context)
        private var cars = emptyList<GlKit.Mesh>()
        private var skins = IntArray(0)
        private var quad: GlKit.Mesh? = null
        private var floorTexture = 0
        private var discTexture = 0
        private var width = 1
        private var height = 1
        private var lastNanos = 0L
        private var angle = 200f
        private var thumbnailSkin = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            kit.init()
            val loaded = kit.loadCars()
            cars = loaded.first
            skins = loaded.second
            quad = kit.groundQuad()
            floorTexture = kit.loadTexture(spotBitmap(0xFF1C2B48.toInt(), 0x000B1220), smooth = true)
            discTexture = kit.loadTexture(discBitmap(), smooth = true)
            thumbnailSkin = -1
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
            lastNanos = now
            angle = (angle + 22f * dt) % 360f
            val currentSkin = skin
            if (thumbnailSkin != currentSkin && cars.isNotEmpty()) {
                thumbnailSkin = currentSkin
                renderThumbnails(currentSkin)
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(BACKDROP[0], BACKDROP[1], BACKDROP[2], 1f)
            GLES20.glClearStencil(0)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

            val mesh = cars.getOrNull(type)
            // 큰 차(버스·트럭)도 화면에 들어오도록 차 길이에 맞춰 물러난다. 차는 화면 가운데보다 조금 아래에 놓는다
            val length = (mesh?.halfZ ?: 2.3f) * 2f
            val distance = maxOf(13.5f, length * 2.5f)
            val eye = floatArrayOf(0f, distance * 0.30f, distance)
            val target = floatArrayOf(0f, distance * 0.075f, 0f)
            val aspect = width.toFloat() / height
            val vp = Mat4.multiply(Mat4.perspective(34f, aspect, 0.5f, 200f), Mat4.lookAt(eye, target))
            kit.begin(eye, GlKit.LIGHT, GlKit.NO_FOG, GlKit.NO_FOG * 2, BACKDROP)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glDepthMask(false)
            // 바닥의 은은한 조명과 회전 받침
            kit.draw(quad, floorTexture, vp, Mat4.scale(distance * 3.2f, 1f, distance * 3.2f), GlKit.WHITE, unlit = true)
            val disc = maxOf(length, 4.2f) * 1.45f
            kit.draw(quad, discTexture, vp, Mat4.multiply(Mat4.translation(0f, 0.01f, 0f), Mat4.multiply(Mat4.rotationY(angle), Mat4.scale(disc, 1f, disc))), GlKit.WHITE, unlit = true)
            // 차 그림자
            val model = Mat4.rotationY(angle)
            GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            GLES20.glStencilFunc(GLES20.GL_EQUAL, 0, 0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR)
            kit.draw(mesh, kit.white, vp, Mat4.multiply(Mat4.shadow(SHOWROOM_LIGHT, 0.02f), model), SHADOW, unlit = true)
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)

            kit.draw(mesh, if (skins.isEmpty()) kit.white else skins[currentSkin.mod(skins.size)], vp, model, GlKit.WHITE)
        }

        /** 차종마다 비스듬히 앞에서 본 작은 그림 (배경 투명) 을 화면 밖 버퍼에 그려 Bitmap 으로 만든다 */
        private fun renderThumbnails(skinIndex: Int) {
            val w = THUMB_W
            val h = THUMB_H
            val ids = IntArray(3)
            GLES20.glGenFramebuffers(1, ids, 0)
            GLES20.glGenTextures(1, ids, 1)
            GLES20.glGenRenderbuffers(1, ids, 2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[1])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, ids[2])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[1], 0)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, ids[2])
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(GlKit.TAG, "카드용 그림을 만들 수 없어요 (framebuffer)")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                return
            }
            GLES20.glViewport(0, 0, w, h)
            val texture = if (skins.isEmpty()) kit.white else skins[skinIndex.mod(skins.size)]
            val flip = Matrix().apply { postScale(1f, -1f) }
            val out = ArrayList<Bitmap>()
            for (mesh in cars) {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                // 차 크기에 맞춰 거리를 잡는다 (비스듬히 앞 오른쪽 위에서)
                val radius = maxOf(mesh.halfZ, mesh.halfX * 1.4f, mesh.height * 0.9f)
                val d = radius * 3.3f
                val center = floatArrayOf(0f, mesh.height * 0.42f, 0f)
                val eye = floatArrayOf(d * 0.62f, d * 0.34f + center[1], -d * 0.72f)
                val vp = Mat4.multiply(Mat4.perspective(30f, w.toFloat() / h, 0.3f, 200f), Mat4.lookAt(eye, center))
                kit.begin(eye, THUMB_LIGHT, GlKit.NO_FOG, GlKit.NO_FOG * 2, BACKDROP)
                kit.draw(mesh, texture, vp, Mat4.identity(), GlKit.WHITE)
                val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(pixels)
                out += Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)   // OpenGL 은 아래부터 읽으므로 뒤집는다
                raw.recycle()
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, ids, 0)
            GLES20.glDeleteTextures(1, ids, 1)
            GLES20.glDeleteRenderbuffers(1, ids, 2)
            post { onThumbnails?.invoke(out) }
        }

        /** 가운데가 밝고 가장자리로 갈수록 사라지는 둥근 빛 (전시장 바닥 조명) */
        private fun spotBitmap(center: Int, edge: Int): Bitmap {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(size / 2f, size / 2f, size / 2f, intArrayOf(center, edge), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            return bitmap
        }

        /** 회전 받침: 어두운 원판 + 밝은 테두리 고리 + 돌아가는 게 보이도록 눈금 */
        private fun discBitmap(): Bitmap {
            val size = 512
            val c = size / 2f
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(c, c, c * 0.9f, intArrayOf(0xFF22324F.toInt(), 0xFF111A2B.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(c, c, c * 0.9f, fill)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 22f
                color = 0x5539C6F4
                maskFilter = android.graphics.BlurMaskFilter(16f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(c, c, c * 0.88f, glow)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = 0xFF39C6F4.toInt()
            }
            canvas.drawCircle(c, c, c * 0.88f, ring)
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 5f
                color = 0x9939C6F4.toInt()
            }
            for (i in 0 until 24) {
                canvas.save()
                canvas.rotate(i * 15f, c, c)
                canvas.drawLine(c, c - c * 0.84f, c, c - c * (if (i % 6 == 0) 0.70f else 0.78f), tick)
                canvas.restore()
            }
            return bitmap
        }
    }

    companion object {
        const val THUMB_W = 320
        const val THUMB_H = 208
        private val BACKDROP = floatArrayOf(0.043f, 0.071f, 0.125f)
        private val SHADOW = floatArrayOf(0f, 0f, 0f, 0.45f)
        private val SHOWROOM_LIGHT: FloatArray = Mat4.normalize(floatArrayOf(0.2f, 1f, 0.25f))
        private val THUMB_LIGHT: FloatArray = Mat4.normalize(floatArrayOf(0.5f, 0.9f, -0.5f))
    }
}
