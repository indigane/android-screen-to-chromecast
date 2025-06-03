package home.screen_to_chromecast.casting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import home.screen_to_chromecast.MainActivity // For launching UI from notification
import home.screen_to_chromecast.R
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var targetRendererName: String? = null // Name of the Chromecast to cast to

    // H.264 stream handling
    private val nalUnitQueue = ArrayBlockingQueue<ByteArray>(NAL_QUEUE_CAPACITY) // Queue for NAL units
    private var encodingThread: Thread? = null
    private var streamingThread: Thread? = null
    @Volatile private var isCasting = false
    private var spsPpsData: ByteArray? = null


    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        libVLC = LibVLC(this, ArrayList<String>().apply { add("--no-sub-autodetect-file") })
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
                targetRendererName = intent.getStringExtra(EXTRA_RENDERER_NAME)

                if (resultCode != RESULT_OK || resultData == null || targetRendererName == null) {
                    Log.e(TAG, "Invalid data for starting cast. Stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createNotification("Starting cast..."))
                isCasting = true

                // Get MediaProjection
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(MediaProjectionCallback(), null)

                // Start screen capture and encoding
                startScreenCaptureAndEncode()

                // Start LibVLC streaming
                startVLCStreaming()

                updateNotification("Casting to $targetRendererName")
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY // Or START_STICKY if you want it to restart
    }

    private fun startScreenCaptureAndEncode() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null. Cannot start capture.")
            stopCastingAndSelf()
            return
        }

        try {
            // Configure MediaCodec for H.264 encoding
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            // format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) // For low latency
            // format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31) // Adjust as needed

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface() // Get surface to draw on

            // Create VirtualDisplay
            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCastVirtualDisplay",
                VIDEO_WIDTH, VIDEO_HEIGHT, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            mediaCodec?.start() // Start encoding
            Log.d(TAG, "MediaCodec started for H.264 encoding.")

            // Start thread to handle encoded output
            encodingThread = thread(start = true, name = "H264EncoderThread") {
                processEncodedData()
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error configuring or starting MediaCodec/VirtualDisplay", e)
            stopCastingAndSelf()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException during MediaCodec/VirtualDisplay setup", e)
            stopCastingAndSelf()
        }
    }

    private fun processEncodedData() {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (isCasting && mediaCodec != null) {
                val outputBufferId = mediaCodec!!.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)

                if (outputBufferId >= 0) {
                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            spsPpsData = data // Store SPS/PPS
                            Log.d(TAG, "SPS/PPS NAL unit captured, size: ${data.size}")
                        } else if (bufferInfo.size > 0) { // Actual frame data
                            // Prepend SPS/PPS if this is the first frame or an IDR frame and SPS/PPS is available
                            // For simplicity, we'll rely on the custom media input to handle this logic if needed,
                            // or ensure the streaming client (VLC) can handle elementary streams correctly.
                            // For now, just queue the NAL units.
                            if (!nalUnitQueue.offer(data, NAL_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                Log.w(TAG, "NAL unit queue is full, dropping frame.")
                            }
                        }
                    }
                    mediaCodec!!.releaseOutputBuffer(outputBufferId, false)
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = mediaCodec!!.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                    // SPS/PPS might be here if not in BUFFER_FLAG_CODEC_CONFIG
                    val spsBuffer = newFormat.getByteBuffer("csd-0")
                    val ppsBuffer = newFormat.getByteBuffer("csd-1")
                    if (spsBuffer != null && ppsBuffer != null) {
                        val sps = ByteArray(spsBuffer.remaining())
                        spsBuffer.get(sps)
                        val pps = ByteArray(ppsBuffer.remaining())
                        ppsBuffer.get(pps)
                        spsPpsData = sps + pps // Concatenate
                        Log.d(TAG, "SPS/PPS captured from INFO_OUTPUT_FORMAT_CHANGED, size: ${spsPpsData?.size}")
                    }
                } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output buffer available yet
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "Encoder EOS reached.")
                    break
                }
            }
        } catch (e: Exception) { // Catch broader exceptions like IllegalStateException if codec is released
            Log.e(TAG, "Exception in H264EncoderThread", e)
        } finally {
            Log.d(TAG, "H264EncoderThread finishing.")
            // Ensure queue is cleared or a special EOS marker is sent if needed by reader
        }
    }


    private fun startVLCStreaming() {
        if (targetRendererName == null || mediaPlayer == null) {
            Log.e(TAG, "Target renderer or media player is null. Cannot start VLC streaming.")
            stopCastingAndSelf()
            return
        }

        // Find the RendererItem. This is a simplification.
        // In a real app, MainActivity might provide the held RendererItem instance,
        // or the service might need its own discovery mechanism if it's long-running.
        // For now, we assume the name is sufficient to find it if LibVLC instance is shared or re-queried.
        // This part needs a robust way to get the RendererItem.
        // Let's try to find it using the discoverer from the LibVLC instance in the service.
        val tempDiscoverer = LibVLC.RendererDiscoverer(libVLC!!, "microdns_renderer")
        var rendererItem: RendererItem? = null
        // This sync discovery is not ideal. Should be async or RendererItem passed.
        // For now, this is a placeholder for getting the RendererItem.
        // A better way: MainActivity holds the item, service gets a reference.
        // Or, pass enough details to reconstruct/find the item.
        // For this example, we'll proceed assuming we can set it by name (which is not how setRenderer works).
        // We NEED a RendererItem object.
        // TODO: Fix RendererItem acquisition. For now, this will likely fail.

        // The correct way is to get the RendererItem from MainActivity or a shared list.
        // Let's assume for now MainActivity has a static way to provide the selected one,
        // or the service needs its own discovery and selection logic if it's truly independent.
        // This is a placeholder for getting the actual RendererItem.
        // For this example, we'll skip setting the renderer if we can't find it immediately,
        // which means it won't cast to a specific device yet.
        // The 'libvlc_media_new_callbacks' approach is more direct for custom input.

        Log.d(TAG, "Attempting to set up VLC streaming for renderer: $targetRendererName")

        val media = Media(libVLC, H264StreamInput(nalUnitQueue, ::isCasting, ::getSpsPpsData))
        // Add options for H.264 elementary stream if needed
        media.addOption(":demux=h264") // Hint to VLC it's an H.264 elementary stream
        media.addOption(":h264-fps=$VIDEO_FRAME_RATE")

        mediaPlayer?.media = media
        media.release() // Media is retained by MediaPlayer

        // TODO: Set the actual RendererItem on the mediaPlayer
        // mediaPlayer.setRenderer(rendererItemToUse);
        // Without a valid RendererItem, this will play locally or not at all.
        // This is the most critical part to fix for actual casting.
        // For now, we'll just try to play. It won't cast to Chromecast yet.
        Log.w(TAG, "Placeholder: RendererItem not properly set. Casting will not work yet.")


        mediaPlayer?.play()
        Log.d(TAG, "MediaPlayer play() called.")

        // Monitor media player events (optional but good for debugging)
        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> Log.d(TAG, "VLC MediaPlayer: Playing")
                MediaPlayer.Event.Paused -> Log.d(TAG, "VLC MediaPlayer: Paused")
                MediaPlayer.Event.Stopped -> Log.d(TAG, "VLC MediaPlayer: Stopped (by itself or error)")
                MediaPlayer.Event.EndReached -> Log.d(TAG, "VLC MediaPlayer: EndReached")
                MediaPlayer.Event.EncounteredError -> Log.e(TAG, "VLC MediaPlayer: Error")
                // Add more events as needed
            }
        }
    }

    // Method to get SPS/PPS data for the H264StreamInput
    private fun getSpsPpsData(): ByteArray? = spsPpsData


    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            stopCastingInternals() // Stop our casting process
            stopSelf() // Stop the service
        }
    }

    private fun stopCastingInternals() {
        Log.d(TAG, "Stopping casting internals...")
        isCasting = false // Signal threads to stop

        encodingThread?.interrupt() // Interrupt if it's blocking on queue
        try {
            encodingThread?.join(500) // Wait for encoder thread to finish
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining H264EncoderThread")
        }
        encodingThread = null

        // Stop MediaCodec and VirtualDisplay
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { Log.e(TAG, "Error releasing VirtualDisplay", e) }
        virtualDisplay = null

        try {
            mediaCodec?.stop()
        } catch (e: Exception) { Log.e(TAG, "Error stopping MediaCodec", e) }
        try {
            mediaCodec?.release()
        } catch (e: Exception) { Log.e(TAG, "Error releasing MediaCodec", e) }
        mediaCodec = null
        inputSurface = null // Surface is released with MediaCodec

        try {
            mediaProjection?.stop()
        } catch (e: Exception) { Log.e(TAG, "Error stopping MediaProjection", e) }
        mediaProjection = null

        // Stop LibVLC playback
        mediaPlayer?.stop()
        // mediaPlayer?.release() // MediaPlayer is released with LibVLC instance
        // libVLC?.release() // LibVLC released in onDestroy

        nalUnitQueue.clear() // Clear any pending NAL units
        spsPpsData = null

        Log.d(TAG, "Casting internals stopped.")
        updateNotification("Casting stopped.") // Update notification before service stops
    }

    private fun stopCastingAndSelf() {
        stopCastingInternals()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "ScreenCastingService onDestroy.")
        stopCastingInternals() // Ensure everything is cleaned up
        mediaPlayer?.release() // Release media player
        mediaPlayer = null
        libVLC?.release() // Release LibVLC instance
        libVLC = null
        super.onDestroy()
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // Low importance for ongoing tasks
            )
            channel.description = getString(R.string.notification_channel_description)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
        val stopCastPendingIntent = PendingIntent.getService(this, 0, stopCastIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.casting_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with a proper casting icon
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_cast, getString(R.string.stop_casting_action), stopCastPendingIntent) // Placeholder for stop icon
            .setOngoing(true) // Makes the notification non-dismissable by swiping
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    companion object {
        private const val TAG = "ScreenCastingService"
        const val ACTION_START_CASTING = "home.screen_to_chromecast.action.START_CASTING"
        const val ACTION_STOP_CASTING = "home.screen_to_chromecast.action.STOP_CASTING"
        const val EXTRA_RESULT_CODE = "home.screen_to_chromecast.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "home.screen_to_chromecast.extra.RESULT_DATA"
        const val EXTRA_RENDERER_NAME = "home.screen_to_chromecast.extra.RENDERER_NAME" // Or more robust identifier

        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCastingChannel"

        // Video Encoding Parameters (example values, adjust as needed)
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2 * 1024 * 1024 // 2 Mbps
        private const val VIDEO_FRAME_RATE = 30 // fps
        private const val I_FRAME_INTERVAL_SECONDS = 1 // Keyframe interval

        private const val CODEC_TIMEOUT_US = 10000L // 10ms
        private const val NAL_QUEUE_CAPACITY = 60 // Buffer ~2 seconds of frames at 30fps
        private const val NAL_QUEUE_TIMEOUT_MS = 100L // Timeout for offering to queue
    }
}
