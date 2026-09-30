package com.example.psbill

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer

class StreamEncoder(
    private val mediaProjection: MediaProjection,
    private val webSocket: WebSocket,
    private val width: Int = 960,
    private val height: Int = 540,
    private val dpi: Int = 240
) {
    private val TAG = "StreamEncoder"
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRunning = false
    private var encoderThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        
        try {
            setupEncoder()
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "KioskScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaCodec!!.createInputSurface(),
                null, null
            )
            
            mediaCodec!!.start()
            startEncoderLoop()
            Log.d(TAG, "Screen capture streaming started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture: ${e.message}")
            stop()
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 800000) // 800 kbps
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds
        }
        
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun startEncoderLoop() {
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val codec = mediaCodec ?: break
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                    
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            
                            // Send binary H.264 data straight to WebSocket
                            webSocket.send(bytes.toByteString())
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encoder loop exception: ${e.message}")
                    break
                }
            }
        }.apply { start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.d(TAG, "Stopping screen capture stream")
        
        try {
            encoderThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Encoder thread join interrupted")
        }
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec: ${e.message}")
        } finally {
            mediaCodec = null
        }
        
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
