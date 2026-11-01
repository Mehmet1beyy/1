package com.mapcast.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Harita sahnesini bir H.264 kodlayıcıya (MediaCodec) çizip yayınlar.
 *
 * Önemli: Çizim, kendi render döngümüzle **OpenGL ES üzerinden doğrudan
 * kodlayıcının giriş yüzeyine** yapılır. Böylece sanal ekran/Presentation
 * vsync'ine hiç bağlı kalmadan her cihazda kare üretilir (MIUI vb. dahil).
 */
class VirtualDisplayStreamer(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val sink: FrameSink
) {
    interface FrameSink {
        fun onFormat(csd: ByteArray)
        fun onFrame(data: ByteArray, isKeyframe: Boolean)
    }

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    @Volatile private var running = false
    private var renderThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        encoder = codec
        inputSurface = surface
        running = true

        drainThread = Thread({ drainLoop(codec) }, "mapcast-encoder").also { it.start() }
        renderThread = Thread({ renderLoop(surface) }, "mapcast-gl").also { it.start() }
        Log.i(TAG, "Streamer started ${width}x$height @ ${fps}fps (GL)")
    }

    fun requestKeyframe() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyframe failed: ${e.message}")
        }
    }

    // ---------------- Encoder çıktısı ----------------

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sink.onFormat(data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            sink.onFrame(data, key)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "drain error: ${e.message}")
                break
            }
        }
    }

    // ---------------- GL render döngüsü ----------------

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var textureUploaded = false

    private fun renderLoop(surface: Surface) {
        try {
            initEgl(surface)
            initGl()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val startMs = SystemClock.uptimeMillis()
            val frameNs = 1_000_000_000L / fps

            while (running) {
                val loopStart = System.nanoTime()
                val t = (SystemClock.uptimeMillis() - startMs) / 1000.0

                MapScene.draw(canvas, width, height, t)
                drawBitmap(bitmap)

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                    (SystemClock.uptimeMillis() - startMs) * 1_000_000L)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                val elapsed = System.nanoTime() - loopStart
                val sleep = frameNs - elapsed
                if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (sleep % 1_000_000L).toInt())
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "render loop error: ${e.message}", e)
        } finally {
            releaseEgl()
        }
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

        val configAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, num, 0)

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

        val surfAttr = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttr, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGl() {
        val vs = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glViewport(0, 0, width, height)
    }

    // Tam ekran dörtgen: pos(x,y) + tex(u,v). V ters çevrilir (bitmap üstten gelir).
    private val quad: ByteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).apply {
            val data = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            asFloatBuffer().put(data)
            position(0)
        }

    private fun drawBitmap(bitmap: Bitmap) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        if (!textureUploaded) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureUploaded = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "shader error: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun stop() {
        running = false
        try { renderThread?.join(500) } catch (_: InterruptedException) {}
        try { drainThread?.join(500) } catch (_: InterruptedException) {}
        renderThread = null
        drainThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        encoder = null
        inputSurface = null
        Log.i(TAG, "Streamer stopped")
    }

    companion object {
        private const val TAG = "MapCast/Streamer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
