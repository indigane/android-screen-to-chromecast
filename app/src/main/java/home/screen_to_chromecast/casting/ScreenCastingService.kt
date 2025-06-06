package home.screen_to_chromecast.casting

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import home.screen_to_chromecast.MainActivity
import home.screen_to_chromecast.R
import home.screen_to_chromecast.RendererHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.ILibVLC
// IMediaInput and H264StreamInput removed as per instructions
import java.io.IOException
// ArrayBlockingQueue, TimeUnit, and thread are no longer needed
// import java.util.concurrent.ArrayBlockingQueue
// import java.util.concurrent.TimeUnit
// import kotlin.concurrent.thread

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    // Removed virtualDisplay, mediaCodec, and inputSurface field declarations

    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    // Removed nalUnitQueue, encodingThread, spsPpsData
    @Volatile
    private var isCasting = false

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val libVlcArgs = ArrayList<String>()
        libVlcArgs.add("--no-sub-autodetect-file")
        try {
            libVLC = LibVLC(this, libVlcArgs)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error initializing LibVLC in Service: ${e.localizedMessage}", e)
            stopSelf()
            return
        }
        mediaPlayer = MediaPlayer(libVLC)
        createNotificationChannel()
        Log.d(TAG, "ScreenCastingService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand received with action: $action")

        when (action) {
            ACTION_START_CASTING -> {
                if (isCasting) {
                    Log.w(TAG, "Already casting, ignoring START_CASTING command.")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                val targetName = RendererHolder.selectedRendererName
                val targetType = RendererHolder.selectedRendererType

                if (resultCode != Activity.RESULT_OK || resultData == null || targetName == null || targetType == -1) {
                    Log.e(TAG, "Invalid data for starting cast (resultCode=$resultCode, resultDataPresent=${resultData!=null}, targetName=$targetName, targetType=$targetType). Stopping service.")
                    RendererHolder.selectedRendererName = null
                    RendererHolder.selectedRendererType = -1
                    stopSelf()
                    return START_NOT_STICKY
                }

                var foundRendererItem: RendererItem? = null
                val availableRenderers = mediaPlayer?.getAvailableRenderers()
                if (availableRenderers.isNullOrEmpty()) {
                    Log.w(TAG, "Service's MediaPlayer has no available renderers. Cannot find: $targetName")
                } else {
                    for (renderer in availableRenderers) {
                        // targetName is guaranteed non-null here.
                        // renderer.name could be null. Kotlin's == handles this safely.
                        if (renderer.name == targetName && renderer.type == targetType) {
                            foundRendererItem = renderer
                            val foundRendererName = renderer.name ?: "Unknown (matched target)"
                            Log.d(TAG, "Found matching renderer in service: $foundRendererName (Type: ${renderer.type})")
                            break
                        }
                    }
                }

                currentRendererItem = foundRendererItem

                if (currentRendererItem == null) {
                    Log.e(TAG, "Could not find renderer '$targetName' (type $targetType) using service's LibVLC instance. Stopping cast.")
                    updateNotification("Failed to connect to $targetName")
                    RendererHolder.selectedRendererName = null
                    RendererHolder.selectedRendererType = -1
                    // stopCastingInternals() // Called by stopSelf() through onDestroy if service stops
                    stopSelf()
                    return START_NOT_STICKY
                }

                val rendererDisplayName = currentRendererItem?.displayName ?: currentRendererItem?.name ?: "Unknown Device"
                startForeground(NOTIFICATION_ID, createNotification("Starting cast to $rendererDisplayName..."))
                isCasting = true

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(MediaProjectionCallback(), null)

                mediaPlayer?.setRenderer(currentRendererItem)
                // mediaPlayer?.play() // This would need a valid media.

                updateNotification("Connecting to $rendererDisplayName...")
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
            }
        }
        return START_NOT_STICKY
    }

    // Removed startScreenCaptureAndEncode, processEncodedData, startVLCStreaming, getSpsPpsData

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            if (isCasting) {
                stopCastingInternals()
            }
        }
    }

    private fun stopCastingInternals() {
        if (!isCasting && mediaProjection == null && (mediaPlayer == null || mediaPlayer?.isPlaying == false)) {
             if (isCasting) {
                isCasting = false
            } else {
                 if (this::class.java.simpleName == "ScreenCastingService") {
                     stopForeground(true)
                     stopSelf()
                 }
                 return
            }
        }
        if (!isCasting) {
             stopForeground(true)
             stopSelf()
             return
        }

        Log.d(TAG, "Stopping casting internals...")
        isCasting = false

        // Removed encodingThread, nalUnitQueue, spsPpsData logic
        // Removed virtualDisplay, mediaCodec, inputSurface logic
        // These were related to screen capture encoding, which is no longer done by this service.

        // Removed mediaCodec, inputSurface, and virtualDisplay cleanup logic

        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping MediaProjection",e) }
        mediaProjection = null

        mediaPlayer?.apply {
            if (this.isPlaying) {
                this.stop()
            }
            this.setEventListener(null)
        }

        currentRendererItem = null
        RendererHolder.selectedRendererName = null
        RendererHolder.selectedRendererType = -1

        Log.d(TAG, "Casting internals stopped.")
        stopForeground(true)
        stopSelf()
    }

    private fun stopCastingAndSelf() {
        stopCastingInternals()
    }

    override fun onDestroy() {
        Log.d(TAG, "ScreenCastingService onDestroy.")
        stopCastingInternals()
        mediaPlayer?.release()
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        RendererHolder.selectedRendererName = null
        RendererHolder.selectedRendererType = -1
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_description)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val stopCastIntent = Intent(this, ScreenCastingService::class.java).apply {
            action = ACTION_STOP_CASTING
        }
        val stopCastPendingIntent = PendingIntent.getService(this, 1, stopCastIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.casting_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_cast, getString(R.string.stop_casting_action), stopCastPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCastingSvc"
        private const val TAG_VLC_EVENT = "ScreenCastingSvc_VLCEvt"
        const val ACTION_START_CASTING = "home.screen_to_chromecast.action.START_CASTING"
        const val ACTION_STOP_CASTING = "home.screen_to_chromecast.action.STOP_CASTING"
        const val EXTRA_RESULT_CODE = "home.screen_to_chromecast.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "home.screen_to_chromecast.extra.RESULT_DATA"
        const val EXTRA_RENDERER_NAME = "home.screen_to_chromecast.extra.RENDERER_NAME"

        private const val NOTIFICATION_ID = 1237
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCastingChannel"

        // Removed unused video and encoding constants
        // private const val VIDEO_WIDTH = 1280
        // private const val VIDEO_HEIGHT = 720
        // private const val VIDEO_BITRATE = 2 * 1024 * 1024 // 2 Mbps
        // private const val VIDEO_FRAME_RATE = 30
        // private const val I_FRAME_INTERVAL_SECONDS = 1
        // private const val CODEC_TIMEOUT_US = 10000L
        // private const val NAL_QUEUE_CAPACITY = 120
        // private const val NAL_QUEUE_TIMEOUT_MS = 100L
    }
}
