package com.recordhelper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 截屏前台服务
 * 通过 MediaProjection + ImageReader + VirtualDisplay 获取屏幕截图
 * captureBitmap() 是挂起函数，可直接在协程中调用
 */
class CaptureForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: CaptureForegroundService? = null
            private set

        private var mediaProjection: MediaProjection? = null
        private var resultCode: Int = 0
        private var resultData: Intent? = null

        fun setProjectionData(code: Int, data: Intent) {
            resultCode = code
            resultData = data
        }
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("记录助手")
            .setContentText("截屏服务运行中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        initProjection()
        return START_STICKY
    }

    private fun initProjection() {
        val data = resultData ?: return
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RecordHelper",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    /**
     * 截取当前屏幕，挂起函数可在协程中直接调用
     */
    suspend fun captureBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        val reader = imageReader ?: run {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 短暂延迟确保画面刷新
        android.os.Handler(mainLooper).postDelayed({
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // 裁剪掉多余的 padding
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    if (cropped != bitmap) bitmap.recycle()
                    cont.resume(cropped)
                } else {
                    cont.resume(null)
                }
            } catch (e: Exception) {
                cont.resume(null)
            }
        }, 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截屏服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
