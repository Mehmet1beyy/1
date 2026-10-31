package com.mapcast.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Bir sanal ekran (VirtualDisplay) oluşturur ve çıkışını doğrudan bir H.264
 * donanım kodlayıcısının (MediaCodec) giriş Surface'ine bağlar.
 * Böylece telefon ekranı değil, SADECE sanal ekranın içeriği kodlanır.
 */
class VirtualDisplayStreamer(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val sink: FrameSink
) {
    interface FrameSink {
        /** SPS/PPS (codec config) baytları */
        fun onFormat(csd: ByteArray)
        /** Tek bir kodlanmış NAL/erişim birimi (Annex-B) */
        fun onFrame(data: ByteArray, isKeyframe: Boolean)
    }

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var running = false
    private var drainThread: Thread? = null

    val display: Display? get() = virtualDisplay?.display

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val vd = dm.createVirtualDisplay(
            "MapCast",
            width, height, dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )

        encoder = codec
        inputSurface = surface
        virtualDisplay = vd
        running = true

        drainThread = Thread({ drainLoop(codec) }, "mapcast-encoder").also { it.start() }
        Log.i(TAG, "Streamer started ${width}x$height @ ${fps}fps")
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
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (isConfig) {
                            sink.onFormat(data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            sink.onFrame(data, key)
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "drain error: ${e.message}")
                break
            }
        }
    }

    fun stop() {
        running = false
        try { drainThread?.join(500) } catch (_: InterruptedException) {}
        drainThread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        virtualDisplay = null
        encoder = null
        inputSurface = null
        Log.i(TAG, "Streamer stopped")
    }

    companion object { private const val TAG = "MapCast/Streamer" }
}
