package com.ivd.virtualdisplay.stream;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Gelen Bitmap karelerini H.264'e (MediaCodec) kodlar.
 *
 * Çizim, kendi render iş parçacığımızla OpenGL ES üzerinden DOĞRUDAN kodlayıcının
 * giriş yüzeyine yapılır (eglSwapBuffers her karede kodlayıcıyı besler). Böylece
 * kare üretimi vsync'e bağlı kalmaz; her cihazda çalışır.
 *
 * Kullanım: start() → her karede submitBitmap(bmp) → stop().
 */
public class StreamEncoder {

    public interface FrameSink {
        void onFormat(byte[] csd);
        void onFrame(byte[] data, boolean keyframe);
    }

    private static final String TAG = "IVD/StreamEncoder";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final int width;
    private final int height;
    private final int fps;
    private final int bitRate;
    private final FrameSink sink;

    private MediaCodec encoder;
    private Surface inputSurface;
    private volatile boolean running = false;
    private Thread renderThread;
    private Thread drainThread;

    // Yakalama iş parçacığından render iş parçacığına kare aktarımı
    private final Object frameLock = new Object();
    private Bitmap pending;

    // EGL/GL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texId;
    private int aPos;
    private int aTex;
    private boolean textureUploaded = false;
    private FloatBuffer quad;

    public StreamEncoder(int width, int height, int fps, int bitRate, FrameSink sink) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
        this.sink = sink;
    }

    public void start() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();

        running = true;
        final MediaCodec codec = encoder;
        drainThread = new Thread(() -> drainLoop(codec), "ivd-encoder");
        drainThread.start();
        renderThread = new Thread(this::renderLoop, "ivd-gl");
        renderThread.start();
        Log.i(TAG, "encoder started " + width + "x" + height + " @" + fps);
    }

    /** Yakalama iş parçacığından çağrılır; bitmap sahipliği devralınır. */
    public void submitBitmap(Bitmap b) {
        synchronized (frameLock) {
            if (pending != null && pending != b && !pending.isRecycled()) {
                pending.recycle();
            }
            pending = b;
        }
    }

    public void requestKeyframe() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            if (encoder != null) encoder.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "requestKeyframe failed: " + e.getMessage());
        }
    }

    // ---------------- Encoder çıktısı ----------------

    private void drainLoop(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buf.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            sink.onFormat(data);
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            sink.onFrame(data, key);
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                }
            } catch (IllegalStateException e) {
                if (running) Log.w(TAG, "drain error: " + e.getMessage());
                break;
            }
        }
    }

    // ---------------- GL render döngüsü ----------------

    private void renderLoop() {
        Bitmap rendering = null;
        try {
            initEgl(inputSurface);
            initGl();
            long startMs = SystemClock.uptimeMillis();
            long frameNs = 1_000_000_000L / fps;

            while (running) {
                long loopStart = System.nanoTime();

                Bitmap next;
                synchronized (frameLock) {
                    next = pending;
                    pending = null;
                }
                if (next != null) {
                    if (rendering != null && !rendering.isRecycled()) rendering.recycle();
                    rendering = next;
                }

                if (rendering != null && !rendering.isRecycled()) {
                    drawBitmap(rendering);
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                            (SystemClock.uptimeMillis() - startMs) * 1_000_000L);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                }

                long sleep = frameNs - (System.nanoTime() - loopStart);
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); }
                    catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "render loop error: " + e.getMessage(), e);
        } finally {
            if (rendering != null && !rendering.isRecycled()) rendering.recycle();
            releaseEgl();
        }
    }

    private void initEgl(Surface surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);

        int[] cfgAttr = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0);

        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);

        int[] surfAttr = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttr, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void initGl() {
        String vs = "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;"
                + "void main(){ gl_Position=vec4(aPos,0.0,1.0); vTex=aTex; }";
        String fs = "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;"
                + "void main(){ gl_FragColor=texture2D(uTex,vTex); }";
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glViewport(0, 0, width, height);

        // Tam ekran dörtgen: pos(x,y)+tex(u,v); V ters (bitmap üstten gelir).
        float[] data = {
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
        };
        quad = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(data);
    }

    private void drawBitmap(Bitmap bitmap) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        if (!textureUploaded) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            textureUploaded = true;
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }

        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader error: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    public void stop() {
        running = false;
        try { if (renderThread != null) renderThread.join(600); } catch (InterruptedException ignored) {}
        try { if (drainThread != null) drainThread.join(600); } catch (InterruptedException ignored) {}
        renderThread = null;
        drainThread = null;
        try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        encoder = null;
        inputSurface = null;
        synchronized (frameLock) {
            if (pending != null && !pending.isRecycled()) pending.recycle();
            pending = null;
        }
        Log.i(TAG, "encoder stopped");
    }
}
