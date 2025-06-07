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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
// import kotlin.concurrent.thread // Not needed as per decision

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    // Removed virtualDisplay, mediaCodec, and inputSurface field declarations

    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encodingThread: Thread? = null
    private var spsPpsData: ByteArray? = null
    private lateinit var nalUnitQueue: java.util.concurrent.ArrayBlockingQueue<ByteArray>
    // Removed: private var customMediaNativePointer: Long = 0L
    private var customMediaSuccessfullySet: Boolean = false
    private var isTargetRendererSet: Boolean = false
    private var customMediaSetupAttempted: Boolean = false

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
        nalUnitQueue = java.util.concurrent.ArrayBlockingQueue(NAL_QUEUE_CAPACITY)
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
                currentRendererItem = null // Reset this too
                isTargetRendererSet = false // Reset flag
                customMediaSuccessfullySet = false // Reset this flag
                customMediaSetupAttempted = false // Reset this flag

                val notificationDeviceName = this.targetRendererName ?: "Unknown Device"
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.searching_for_device, notificationDeviceName)))
                isCasting = true // Set casting flag

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                startVideoEncoding()

                // Updated JNI Integration
                // No longer directly calling JNI here, tryNativeSetupAndPlay will handle it.
                Log.d(TAG, "Video encoding setup complete. NAL queue should be ready.")
                tryNativeSetupAndPlay()

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

    private fun startVideoEncoding() {
        Log.d(TAG, "Starting video encoding...")
        try {
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics) // Use defaultDisplay.getMetrics

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCasting",
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
            mediaCodec?.start()
            Log.i(TAG, "MediaCodec started and VirtualDisplay created.")

            encodingThread = Thread(Runnable {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isCasting) {
                    try {
                        val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US) ?: -1

                        if (outputBufferIndex >= 0) {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    Log.d(TAG, "Received BUFFER_FLAG_CODEC_CONFIG")
                                    val csd = ByteArray(bufferInfo.size)
                                    outputBuffer.get(csd)
                                    spsPpsData = csd // Store combined SPS/PPS
                                    // Offering spsPpsData to queue or using in open_cb will be handled later
                                    // For now, just storing it. If needed, it could be offered here:
                                    // if (!nalUnitQueue.offer(spsPpsData, NAL_QUEUE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                    //     Log.w(TAG, "SPS/PPS data offer to queue timed out.")
                                    // }
                                } else {
                                    val nalUnit = ByteArray(bufferInfo.size)
                                    outputBuffer.get(nalUnit)
                                    if (!nalUnitQueue.offer(nalUnit, NAL_QUEUE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                        Log.w(TAG, "NAL unit offer to queue timed out. Queue size: ${nalUnitQueue.size}")
                                    }
                                }
                            }
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.d(TAG, "Encoder output format changed: ${mediaCodec?.outputFormat}")
                            // Preferred way to get SPS/PPS
                            val newFormat = mediaCodec?.outputFormat
                            val spsBuffer = newFormat?.getByteBuffer("csd-0")
                            val ppsBuffer = newFormat?.getByteBuffer("csd-1")
                            if (spsBuffer != null && ppsBuffer != null) {
                                val sps = ByteArray(spsBuffer.remaining())
                                spsBuffer.get(sps)
                                val pps = ByteArray(ppsBuffer.remaining())
                                ppsBuffer.get(pps)
                                spsPpsData = ByteArray(sps.size + pps.size)
                                System.arraycopy(sps, 0, spsPpsData!!, 0, sps.size)
                                System.arraycopy(pps, 0, spsPpsData!!, sps.size, pps.size)
                                Log.i(TAG, "SPS/PPS data extracted from format change.")
                                // Again, offering or using in open_cb is for later.
                                // if (spsPpsData != null && !nalUnitQueue.offer(spsPpsData, NAL_QUEUE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                //    Log.w(TAG, "SPS/PPS data (from format change) offer to queue timed out.")
                                // }
                            }
                        } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // Log.v(TAG, "dequeueOutputBuffer timed out, try again later"); // Can be noisy
                        } else {
                            Log.w(TAG, "Unhandled outputBufferIndex: $outputBufferIndex")
                        }
                    } catch (e: InterruptedException) {
                        Log.i(TAG, "Encoding thread interrupted.")
                        break // Exit loop if interrupted
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during encoding loop: ${e.message}", e)
                        // Potentially stop casting or signal error
                    }
                }
                Log.d(TAG, "Encoding thread finished.")
            })
            encodingThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video encoding: ${e.message}", e)
            // Update notification or stop casting if critical error
            updateNotification("Error: Video encoding setup failed")
            stopCastingInternals() // Ensure cleanup if setup fails
        }
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
                        Log.i(TAG, "Target renderer '$targetRendererName' (type: ${item.type}) found by service discoverer!")
                        currentRendererItem = item // Store the found item
                        isTargetRendererSet = true   // Indicate that our target renderer is available

                        // Notification update: Device found, inform user we're trying to connect/stream
                        // Placeholder for getString(R.string.device_found_preparing_stream, targetRendererName ?: "Unknown Device")
                        updateNotification("Device ${targetRendererName ?: "Unknown Device"} found, preparing stream…")

                        stopServiceDiscovery() // Stop discovery as we found our target

                        tryNativeSetupAndPlay() // Attempt to setup and play
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
        isCasting = false // Set casting flag to false immediately to signal encoding thread

        // Stop Encoding Thread
        encodingThread?.interrupt()
        try {
            encodingThread?.join(1000) // Wait for a short period
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining encoding thread: ${e.message}", e)
            Thread.currentThread().interrupt() // Preserve interrupt status
        }
        encodingThread = null

        // Release MediaCodec and VirtualDisplay
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            Log.d(TAG, "MediaCodec stopped and released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping or releasing MediaCodec: ${e.message}", e)
        }
        mediaCodec = null

        inputSurface?.release() // Though owned by MediaCodec, explicit release can be good practice
        inputSurface = null
        Log.d(TAG, "InputSurface released.")

        try {
            virtualDisplay?.release()
            Log.d(TAG, "VirtualDisplay released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}", e)
        }
        virtualDisplay = null

        // Clear the NAL unit queue and reset SPS/PPS data
        if (this::nalUnitQueue.isInitialized) { // Check if nalUnitQueue has been initialized
            nalUnitQueue.clear()
            Log.d(TAG, "NAL unit queue cleared.")
        }
        spsPpsData = null

        // JNI related cleanup for custom media
        customMediaSuccessfullySet = false // Reset this flag
        isTargetRendererSet = false    // Reset this flag
        customMediaSetupAttempted = false // Reset this flag
        currentRendererItem = null     // Reset the stored renderer item


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

    private fun tryNativeSetupAndPlay() {
        if (customMediaSetupAttempted) {
            Log.d(TAG, "tryNativeSetupAndPlay: Setup already attempted.")
            return
        }

        // Check if nalUnitQueue is initialized, otherwise it could be a race condition if startVideoEncoding hasn't finished
        if (!this::nalUnitQueue.isInitialized) {
            Log.w(TAG, "tryNativeSetupAndPlay: NAL unit queue not initialized yet.")
            return
        }

        if (isTargetRendererSet && currentRendererItem != null && libVLC != null && mediaPlayer != null /* && nalUnitQueue is ready implicitly by now */) {
            Log.i(TAG, "tryNativeSetupAndPlay: Conditions met. Attempting native setup and play for renderer: ${currentRendererItem?.name}")
            customMediaSetupAttempted = true // Mark that we are attempting the setup

            if (mediaProjection == null) {
                Log.e(TAG, "tryNativeSetupAndPlay: MediaProjection is null, cannot proceed with native setup.")
                // Placeholder for getString(R.string.error_media_projection_lost)
                updateNotification("Error: Screen capture permission lost")
                stopCastingInternals()
                return
            }

            // spsPpsData is a member variable, should be populated by startVideoEncoding by now
            customMediaSuccessfullySet = nativeSetupCustomMediaAndPlay(
                mediaPlayer!!,
                libVLC!!,
                nalUnitQueue,
                spsPpsData,
                currentRendererItem!!
            )

            if (customMediaSuccessfullySet) {
                Log.i(TAG, "nativeSetupCustomMediaAndPlay succeeded. Stream should be starting.")
                // Placeholder for getString(R.string.casting_to_device, currentRendererItem?.name ?: "Unknown Device")
                updateNotification("Casting to ${currentRendererItem?.name ?: "Unknown Device"}...")
            } else {
                Log.e(TAG, "nativeSetupCustomMediaAndPlay failed.")
                // Placeholder for getString(R.string.error_stream_start_failed)
                updateNotification("Error: Failed to start video stream")
                stopCastingInternals()
            }
        } else {
            var reason = ""
            if (customMediaSetupAttempted) reason += "setup already attempted; " // Should not happen due to early exit
            if (!isTargetRendererSet) reason += "target renderer not set; "
            if (currentRendererItem == null) reason += "currentRendererItem is null; "
            if (libVLC == null) reason += "libVLC is null; "
            if (mediaPlayer == null) reason += "mediaPlayer is null; "
            Log.d(TAG, "tryNativeSetupAndPlay: Conditions not yet fully met to attempt native setup. Reason: $reason")
        }
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

    // Declare Native Methods
    private external fun nativeSetupCustomMediaAndPlay(
        mediaPlayer: MediaPlayer,
        libVLC: ILibVLC,
        nalQueue: ArrayBlockingQueue<ByteArray>,
        spsPpsData: ByteArray?,
        rendererItem: RendererItem
    ): Boolean

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

        init {
            try {
                System.loadLibrary("custom_media_input")
                Log.i(TAG, "Successfully loaded native library 'custom_media_input'")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'custom_media_input'", e)
                // Consider throwing an error or having a flag that prevents service usage
            }
        }

        // Removed unused video and encoding constants
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2 * 1024 * 1024 // 2 Mbps
        private const val VIDEO_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val CODEC_TIMEOUT_US = 10000L
        private const val NAL_QUEUE_CAPACITY = 120
        private const val NAL_QUEUE_TIMEOUT_MS = 100L
    }
}
