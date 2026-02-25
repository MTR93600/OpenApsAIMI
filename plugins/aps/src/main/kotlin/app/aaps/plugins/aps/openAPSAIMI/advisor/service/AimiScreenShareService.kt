package app.aaps.plugins.aps.openAPSAIMI.advisor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * 📺 AIMI Local Screen Share Service
 *
 * Creates a local MJPEG HTTP server on port 8888 that streams the device screen.
 * The share URL is: http://<local_wifi_ip>:8888
 * The receiver only needs a browser or VLC to view the stream.
 *
 * No external server, no LiveKit, no DNS required.
 */
class AimiScreenShareService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val EXTRA_TOKEN = "EXTRA_TOKEN"

        const val EXTRA_LOCAL_URL = "EXTRA_LOCAL_URL"
        const val HTTP_PORT = 8888

        private const val NOTIFICATION_CHANNEL_ID = "aimi_screen_share_channel"
        private const val NOTIFICATION_ID = 2005
        private const val TAG = "AimiScreenShare"

        // Shared volatile frame for the MJPEG server
        @Volatile
        var latestFrame: ByteArray? = null
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        it.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                    if (resultCode != 0 && resultData != null) {
                        startStreaming(resultCode, resultData)
                    } else {
                        stopSelf()
                    }
                }
                ACTION_STOP -> stopStreaming()
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent) {
        val notification = createNotification("Streaming local en cours sur le port $HTTP_PORT...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            try {
                // 1. Initialize MediaProjection
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)

                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val screenDensity = displayMetrics.densityDpi

                // 2. Create ImageReader for capturing frames
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image: Image? = reader.acquireLatestImage()
                    image?.let {
                        try {
                            val planes = it.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * screenWidth

                            val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                            val baos = ByteArrayOutputStream()
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                            latestFrame = baos.toByteArray()

                            bitmap.recycle()
                            croppedBitmap.recycle()
                        } finally {
                            it.close()
                        }
                    }
                }, null)

                // 3. Create VirtualDisplay
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AimiCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )

                // 4. Start the MJPEG HTTP server
                startMjpegServer()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting screen share", e)
                stopStreaming()
            }
        }
    }

    private fun startMjpegServer() {
        try {
            serverSocket = ServerSocket(HTTP_PORT)
            Log.i(TAG, "MJPEG server listening on port $HTTP_PORT")

            while (!serverSocket!!.isClosed) {
                val client: Socket = serverSocket!!.accept()
                scope.launch {
                    handleMjpegClient(client)
                }
            }
        } catch (e: SocketException) {
            Log.i(TAG, "Server socket closed.")
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG Server error", e)
        }
    }

    private fun handleMjpegClient(socket: Socket) {
        try {
            val output: OutputStream = socket.getOutputStream()
            // HTTP headers for MJPEG stream
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--aimi_frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            output.write(headers.toByteArray())
            output.flush()

            while (!socket.isClosed) {
                val frame = latestFrame
                if (frame != null) {
                    val partHeader = "--aimi_frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                    output.write(partHeader.toByteArray())
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
                }
                Thread.sleep(100) // ~10 FPS
            }
        } catch (e: Exception) {
            // Client disconnected, normal behavior
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun stopStreaming() {
        try {
            serverSocket?.close()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            latestFrame = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AAPS AIMI Screen Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification pour le partage d'écran actif"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AAPS Mode Support")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
