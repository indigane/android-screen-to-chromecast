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
import java.io.IOException

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null

    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    @Volatile
    private var isCasting = false

    private val mediaProjectionCallback = MediaProjectionCallback()

    // New fields for service-side renderer discovery
    private var serviceRendererDiscoverer: org.videolan.libvlc.RendererDiscoverer? = null
    private var targetRendererName: String? = null
    private var targetRendererType: String? = null
    private val serviceRendererListener = ServiceRendererEventListener()


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

                // Store target renderer details from RendererHolder
                this.targetRendererName = RendererHolder.selectedRendererName
                this.targetRendererType = RendererHolder.selectedRendererType // Now String? from RendererHolder

                // Validate essential data including the retrieved target renderer details
                if (resultCode != Activity.RESULT_OK || resultData == null || this.targetRendererName == null || this.targetRendererType == null) {
                    Log.e(TAG, "Invalid data for starting cast (resultCode=$resultCode, resultDataPresent=${resultData!=null}, targetName=${this.targetRendererName}, targetType=${this.targetRendererType}). Stopping service.")
                    RendererHolder.selectedRendererName = null // Clear holder as we are failing
                    RendererHolder.selectedRendererType = null
                    updateNotification("Error: Invalid casting parameters") // TODO: Use new string resource
                    stopSelf() // This will trigger onDestroy -> stopCastingInternals
                    return START_NOT_STICKY
                }

                // Clear currentRendererItem from any previous session before starting new discovery
                currentRendererItem = null

                val notificationDeviceName = this.targetRendererName ?: "Unknown Device"
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.searching_for_device, notificationDeviceName)))
                isCasting = true // Set casting flag

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                startServiceDiscovery() // Start discovery, listener will handle setRenderer

                // Notification was: "Searching for [device]..."
                // If startServiceDiscovery fails and calls stopCastingInternals, notification will be updated there.
                // If successful, listener will update to "Casting to [device]"
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServiceDiscovery() {
        if (libVLC == null) {
            Log.e(TAG, "LibVLC instance is null, cannot start service discovery.")
            updateNotification("Error: LibVLC not available for discovery") // Inform user
            stopCastingInternals() // Stop casting as discovery isn't possible
            return
        }
        if (serviceRendererDiscoverer == null) {
            Log.d(TAG, "Creating new serviceRendererDiscoverer")
            serviceRendererDiscoverer = org.videolan.libvlc.RendererDiscoverer(libVLC!!, "microdns_renderer")
        }
        serviceRendererDiscoverer?.setEventListener(serviceRendererListener)
        if (serviceRendererDiscoverer?.start() == true) {
            Log.d(TAG, "Service-side renderer discovery started.")
            // Notification is typically "Searching for [device]..." set by onStartCommand
        } else {
            Log.e(TAG, "Failed to start service-side renderer discovery.")
            updateNotification("Error: Failed to start discovery")
            stopCastingInternals() // Stop if discovery cannot be initiated
        }
    }

    private fun stopServiceDiscovery() {
        serviceRendererDiscoverer?.let {
            Log.d(TAG, "Stopping service-side renderer discovery.")
            it.setEventListener(null)
            it.stop()
        }
        serviceRendererDiscoverer = null
        Log.d(TAG, "Service-side renderer discovery stopped and nullified.")
    }


    // Listener for the service's own RendererDiscoverer instance
    private inner class ServiceRendererEventListener : org.videolan.libvlc.RendererDiscoverer.EventListener {
        override fun onEvent(event: org.videolan.libvlc.RendererDiscoverer.Event?) {
            if (libVLC == null || serviceRendererDiscoverer == null || event == null) {
                Log.w(TAG, "ServiceRendererEventListener: Ignoring event due to null LibVLC, discoverer, or event.")
                return
            }

            val item = event.item ?: return

            when (event.type) {
                org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded -> {
                    val itemName = item.name ?: "Unknown Name"
                    val itemDisplayName = item.displayName ?: itemName
                    Log.d(TAG, "Service Discovery: Renderer Added - $itemDisplayName (Name: $itemName, Type: ${item.type})")

                    // targetRendererType is now String?, item.type is assumed to be String? from RendererItem
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.i(TAG, "Target renderer '$targetRendererName' found by service discoverer!")
                        currentRendererItem = item
                        mediaPlayer?.setRenderer(currentRendererItem)
                        // Potentially start playback here if media is set, or ensure it's playing if already set
                        // mediaPlayer?.play()
                        updateNotification(getString(R.string.casting_to_device, targetRendererName ?: "Unknown Device"))
                        stopServiceDiscovery() // Found our target, no need to discover further

                        if (mediaProjection != null && libVLC != null) {
                            Log.d(TAG, "Preparing media from MediaProjection.")
                            val media = libVLC!!.getMediaFactory()?.fromMediaProjection(mediaProjection!!)
                            if (media != null) {
                                mediaPlayer?.setMedia(media)
                                // It's important to release the media object after it's been set to the player
                                // to avoid resource leaks, as per LibVLC best practices.
                                // The MediaPlayer takes its own reference.
                                media.release()
                                Log.d(TAG, "Media set on MediaPlayer. Attempting to play.")
                                mediaPlayer?.play()
                                // Notification is already: "Casting to [device]"
                                // updateNotification(getString(R.string.casting_to_device, targetRendererName ?: "Unknown Device"))
                            } else {
                                Log.e(TAG, "Failed to create Media from MediaProjection.")
                                updateNotification(getString(R.string.error_media_projection_setup)) // Assumes this string exists
                                stopCastingInternals() // Stop if media cannot be prepared
                            }
                        } else {
                            Log.e(TAG, "MediaProjection or LibVLC is null, cannot prepare media for casting.")
                            // Potentially update notification here too, though if mediaProjection is null,
                            // it might have been caught earlier in onStartCommand.
                            // If libVLC is null, that's a more fundamental issue.
                            updateNotification(getString(R.string.error_media_projection_unavailable)) // Assumes this string exists
                            stopCastingInternals()
                        }
                    }
                }
                org.videolan.libvlc.RendererDiscoverer.Event.ItemDeleted -> {
                    val itemName = item.name ?: "Unknown Name"
                    Log.d(TAG, "Service Discovery: Renderer Deleted - $itemName (Type: ${item.type})")
                    // Optional: If the deleted item is our currentRendererItem, handle it (e.g., stop casting)
                    if (currentRendererItem != null && currentRendererItem?.name == item.name && currentRendererItem?.type == item.type) {
                        Log.w(TAG, "Service Discovery: Current renderer $itemName was removed!")
                        updateNotification("Error: Device $itemName disconnected")
                        stopCastingInternals() // Stop casting as the renderer is gone
                    }
                }
                else -> {
                    // Other events: AllItemsDeleted, etc.
                    // Log.d(TAG, "ServiceRendererEventListener: Event type ${event.type}")
                }
            }
        }
    }

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            if (isCasting) {
                stopCastingInternals()
            }
        }
    }

    private fun stopCastingInternals() {
        // More comprehensive check for idempotency
        if (!isCasting && mediaProjection == null && mediaPlayer == null && libVLC == null) {
            Log.d(TAG, "stopCastingInternals: Already stopped or nothing to do.")
            // Ensure service stops if it's somehow still running without active resources
            if (this::class.java.simpleName == "ScreenCastingService") {
                stopForeground(true)
                stopSelf()
            }
            return
        }

        Log.d(TAG, "Stopping casting internals...")
        isCasting = false // Set casting flag to false immediately

        stopServiceDiscovery() // Stop service-side discovery if it's running

        // Stop and detach MediaProjection
        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            Log.d(TAG, "MediaProjection stopped and callback unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection or unregistering callback", e)
        }
        mediaProjection = null

        // Stop and release MediaPlayer
        mediaPlayer?.let { player ->
            try {
                player.setRenderer(null) // Attempt to detach renderer; safe if none is set.
                if (player.isPlaying) {
                    player.stop()
                }
                player.setEventListener(null)
                Log.d(TAG, "Releasing MediaPlayer instance.")
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error operating on or releasing MediaPlayer: ${e.message}", e)
            }
        }
        mediaPlayer = null // Nullify after operations

        // Release LibVLC
        libVLC?.let { vlc ->
            try {
                Log.d(TAG, "Releasing LibVLC instance.")
                vlc.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing LibVLC: ${e.message}", e)
            }
        }
        libVLC = null // Nullify after release

        // Clear renderer item from holder and local target
        currentRendererItem = null
        targetRendererName = null
        targetRendererType = null // Changed from -1
        RendererHolder.selectedRendererName = null // Also clear the global holder
        RendererHolder.selectedRendererType = null // Changed from -1

        Log.d(TAG, "Casting internals stopped and resources released.")
        stopForeground(true)
        stopSelf()
    }

    // stopCastingAndSelf() can be removed if not used externally, or kept if it provides a useful alias.
    // For now, assuming it might be called from somewhere, though typically ACTION_STOP_CASTING -> stopCastingInternals is the path.
    private fun stopCastingAndSelf() {
        Log.d(TAG, "stopCastingAndSelf called, redirecting to stopCastingInternals.")
        stopCastingInternals()
    }

    override fun onDestroy() {
        Log.d(TAG, "ScreenCastingService onDestroy. Ensuring casting internals are stopped.")
        // Most cleanup is now in stopCastingInternals.
        // Call it to ensure everything is released if onDestroy is called directly
        // e.g. by system before stopCastingInternals was triggered by an explicit ACTION_STOP_CASTING.
        stopCastingInternals()
        // RendererHolder clearing is also in stopCastingInternals, so no need to repeat here.
        super.onDestroy()
        Log.d(TAG, "ScreenCastingService fully destroyed.")
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
    }
}
