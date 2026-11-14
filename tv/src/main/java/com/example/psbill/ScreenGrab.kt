package com.example.psbill

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * One screen-capture display for the whole permission (Android 14+ allows only ONE
 * createVirtualDisplay per MediaProjection token). Between shots the display has no
 * surface, so the TV composes nothing extra: a shot attaches a small ImageReader for one
 * frame (~0.4 s) and detaches it again. The live stream borrows the same display.
 * All pixel work runs on its own background thread, never the UI thread.
 */
object ScreenGrab {
    private const val TAG = "ScreenGrab"
    const val W = 480
    const val H = 270
    private const val DPI = 160

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile var streaming = false
        private set
    private val thread by lazy { HandlerThread("screen-grab").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    val ready: Boolean get() = projection != null

    @Synchronized
    fun use(p: MediaProjection) {
        if (p === projection) return
        release()
        projection = p
        // Android 14+: a callback must be registered before the display is made
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { synchronized(this@ScreenGrab) { if (projection === p) { release(stopProjection = false) } } }
        }, handler)
    }

    @Synchronized
    private fun ensureDisplay(): VirtualDisplay? {
        display?.let { return it }
        val p = projection ?: return null
        return try {
            p.createVirtualDisplay("PSBillGrab", W, H, DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler).also { display = it }
        } catch (e: Exception) {
            Log.w(TAG, "display: ${e.message}")
            release()   // a used-up token: ask again at the next idle lock screen
            null
        }
    }

    /** The live stream: point the display at the encoder's surface. */
    @Synchronized
    fun attachStream(surface: Surface, w: Int, h: Int, dpi: Int): Boolean {
        val d = ensureDisplay() ?: return false
        return try { d.resize(w, h, dpi); d.surface = surface; streaming = true; true }
        catch (e: Exception) { Log.w(TAG, "stream: ${e.message}"); false }
    }

    @Synchronized
    fun detachStream() {
        streaming = false
        runCatching { display?.surface = null; display?.resize(W, H, DPI) }
    }

    /**
     * One small JPEG of the screen -> done(bytes, dark). dark = almost every pixel is black,
     * which is what a TV that hides the HDMI picture from capture returns. Skipped while streaming.
     */
    fun shoot(done: (ByteArray, Boolean) -> Unit) {
        handler.post {
            val d: VirtualDisplay
            val r: ImageReader
            synchronized(this) {
                if (streaming) return@post
                d = ensureDisplay() ?: return@post
                r = reader ?: ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2).also { reader = it }
                runCatching { d.surface = r.surface }.onFailure { return@post }
            }
            handler.postDelayed({
                var out: Pair<ByteArray, Boolean>? = null
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        val plane = img.planes[0]
                        val ps = plane.pixelStride
                        val bmp = Bitmap.createBitmap(plane.rowStride / ps, H, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(plane.buffer)
                        img.close()
                        val crop = Bitmap.createBitmap(bmp, 0, 0, W, H)
                        if (crop !== bmp) bmp.recycle()
                        val dark = isDark(crop)
                        val bos = ByteArrayOutputStream()
                        crop.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                        crop.recycle()
                        out = bos.toByteArray() to dark
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "shot: ${e.message}")
                } finally {
                    synchronized(this) { if (!streaming) runCatching { d.surface = null } }
                }
                out?.let { (b, dark) -> runCatching { done(b, dark) } }
            }, 400)
        }
    }

    /** Samples ~500 pixels: average brightness under 10 of 255 = a black frame. */
    private fun isDark(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        var y = 4
        while (y < b.height) {
            var x = 4
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
                n++
                x += 20
            }
            y += 12
        }
        return n > 0 && sum / n < 10
    }

    @Synchronized
    fun release(stopProjection: Boolean = true) {
        streaming = false
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        val p = projection
        projection = null
        if (stopProjection) runCatching { p?.stop() }
    }
}
