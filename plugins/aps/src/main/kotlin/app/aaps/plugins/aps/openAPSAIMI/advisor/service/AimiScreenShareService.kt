package app.aaps.plugins.aps.openAPSAIMI.advisor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AimiScreenShareService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val EXTRA_TOKEN = "EXTRA_TOKEN" // JWT Token for LiveKit

        private const val NOTIFICATION_CHANNEL_ID = "aimi_screen_share_channel"
        private const val NOTIFICATION_ID = 2005
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var liveKitRoom: Room? = null

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
                    val roomId = it.getStringExtra(EXTRA_ROOM_ID) ?: "default_room"
                    val token = it.getStringExtra(EXTRA_TOKEN) ?: "dummy_token"
                    
                    if (resultCode != 0 && resultData != null) {
                        startScreenShare(resultCode, resultData, roomId, token)
                    } else {
                        stopSelf()
                    }
                }
                ACTION_STOP -> {
                    stopScreenShare()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startScreenShare(resultCode: Int, resultData: Intent, roomId: String, token: String) {
        val notification = createNotification("Partage d'écran en cours (Room: $roomId)...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            try {
                // Initialize LiveKit Room
                // Replace with actual Signaling Server URL
                val wsUrl = "wss://your-livekit-server.com" 
                
                liveKitRoom = LiveKit.create(applicationContext, RoomOptions(adaptiveStream = true))
                val room = liveKitRoom ?: return@launch
                
                // Connect to Room (will need an actual generated token from a backend)
                room.connect(wsUrl, token)

                // Publish the track to the room
                room.localParticipant.setScreenShareEnabled(true, resultData)

            } catch (e: Exception) {
                e.printStackTrace()
                stopScreenShare()
            }
        }
    }

    private fun stopScreenShare() {
        scope.launch {
            try {
                liveKitRoom?.localParticipant?.setScreenShareEnabled(false)
                liveKitRoom?.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Replace with explicit AAPS icon later
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenShare()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
