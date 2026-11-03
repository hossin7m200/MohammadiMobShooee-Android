package com.mohammadimobshooei.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_TEXT_READY = "com.mohammadimobshooei.app.TEXT_READY"
        const val EXTRA_TEXT = "recognized_text"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var captureStarted = false
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دستیار محمدی")
            .setContentText("در حال خواندن نوشته‌های صفحه…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null || resultCode == 0) {
            finishWithText("")
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                finishWithText("")
                return START_NOT_STICKY
            }
            startCapture()
        } catch (_: Exception) {
            finishWithText("")
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (captureStarted) return
        captureStarted = true

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        workerThread = HandlerThread("ScreenTextCapture").also { it.start() }
        val handler = Handler(workerThread!!.looper)
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = rowStride / pixelStride
                val buffer = plane.buffer
                val paddedBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val bitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    image.width,
                    image.height
                )
                paddedBitmap.recycle()
                image.close()
                imageReader?.setOnImageAvailableListener(null, null)
                recognizeText(bitmap)
            } catch (_: Exception) {
                image.close()
                finishWithText("")
            }
        }, handler)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finishWithText("")
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "MohammadiScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        handler.postDelayed({
            if (imageReader != null) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    image?.close()
                } catch (_: Exception) {
                    finishWithText("")
                }
            }
        }, 1200)
    }

    private fun recognizeText(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                finishWithText(result.text)
            }
            .addOnFailureListener {
                finishWithText("")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                recognizer.close()
            }
    }

    private fun finishWithText(text: String) {
        if (finished) return
        finished = true
        try {
            sendBroadcast(Intent(ACTION_TEXT_READY).setPackage(packageName).putExtra(EXTRA_TEXT, text))
        } catch (_: Exception) {
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        workerThread?.quitSafely()
        workerThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خواندن متن صفحه",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        workerThread?.quitSafely()
        super.onDestroy()
    }
}
