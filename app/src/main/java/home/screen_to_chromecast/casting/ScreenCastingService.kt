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
// Correct import for IMediaInput if it's a top-level interface
import org.videolan.libvlc.interfaces.IMediaInput
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    private val nalUnitQueue = ArrayBlockingQueue<ByteArray>(NAL_QUEUE_CAPACITY)
    private var encodingThread: Thread? = null
    @Volatile
    private var isCasting = false
    private var spsPpsData: ByteArray? = null

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
                currentRendererItem = RendererHolder.selectedRendererItem

                if (resultCode != Activity.RESULT_OK || resultData == null || currentRendererItem == null) {
                    Log.e(TAG, "Invalid data for starting cast (resultCode=$resultCode, resultDataPresent=${resultData!=null}, rendererItemPresent=${currentRendererItem!=null}). Stopping service.")
                    // No explicit release for currentRendererItem here, MainActivity manages RendererHolder item
                    RendererHolder.selectedRendererItem = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                val rendererName = currentRendererItem?.displayName ?: currentRendererItem?.name ?: "Unknown Device"
                startForeground(NOTIFICATION_ID, createNotification("Starting cast to $rendererName..."))
                isCasting = true

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(MediaProjectionCallback(), null)

                startScreenCaptureAndEncode()
                startVLCStreaming()

                updateNotification("Casting to $rendererName")
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
            }
        }
        return START_NOT_STICKY
    }

    private fun startScreenCaptureAndEncode() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null. Cannot start capture.")
            stopCastingAndSelf()
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCastVirtualDisplay",
                VIDEO_WIDTH, VIDEO_HEIGHT, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
            mediaCodec?.start()
            Log.d(TAG, "MediaCodec started for H.264 encoding.")
            encodingThread = thread(start = true, name = "H264EncoderThread") { processEncodedData() }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring or starting MediaCodec/VirtualDisplay", e)
            stopCastingAndSelf()
        }
    }

    private fun processEncodedData() {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (isCasting && mediaCodec != null) {
                val currentCodec = mediaCodec ?: break
                val outputBufferId = currentCodec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outputBufferId >= 0) {
                    val outputBuffer = currentCodec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            spsPpsData = data
                            Log.d(TAG, "SPS/PPS NAL unit captured, size: ${data.size}")
                        } else if (bufferInfo.size > 0) {
                            if (!nalUnitQueue.offer(data, NAL_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                // Log.w(TAG, "NAL unit queue is full, dropping frame.")
                            }
                        }
                    }
                    currentCodec.releaseOutputBuffer(outputBufferId, false)
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = currentCodec.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                    val spsByteBuffer = newFormat.getByteBuffer("csd-0")
                    val ppsByteBuffer = newFormat.getByteBuffer("csd-1")
                    if (spsByteBuffer != null && ppsByteBuffer != null) {
                        val sps = ByteArray(spsByteBuffer.remaining())
                        spsByteBuffer.get(sps)
                        val pps = ByteArray(ppsByteBuffer.remaining())
                        ppsByteBuffer.get(pps)
                        spsPpsData = sps + pps
                        Log.d(TAG, "SPS/PPS captured from INFO_OUTPUT_FORMAT_CHANGED, size: ${spsPpsData?.size}")
                    }
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "Encoder EOS reached.")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in H264EncoderThread", e)
        } finally {
            Log.d(TAG, "H264EncoderThread finishing.")
        }
    }

    private fun startVLCStreaming() {
        val localLibVLC = libVLC
        val localMediaPlayer = mediaPlayer
        val localRendererItem = currentRendererItem

        if (localLibVLC == null || localMediaPlayer == null || localRendererItem == null) {
            Log.e(TAG, "LibVLC, MediaPlayer or RendererItem is null. Cannot start VLC streaming.")
            stopCastingAndSelf()
            return
        }
        Log.d(TAG, "Setting up VLC streaming for renderer: ${localRendererItem.displayName ?: localRendererItem.name}")

        val mediaInput: IMediaInput = H264StreamInput(nalUnitQueue, ::isCasting, ::getSpsPpsData)
        // This Media constructor is the one that takes an IMediaInput.
        // If this causes an ambiguity or "not found" with 3.6.2,
        // the way to provide custom input streams has fundamentally changed.
        val media = Media(localLibVLC, mediaInput)

        media.addOption(":demux=h264")
        media.addOption(":h264-fps=$VIDEO_FRAME_RATE")

        localMediaPlayer.media = media
        media.release()

        // MediaPlayer.setRenderer(RendererItem) typically returns boolean for success.
        // If the error log implies it returns int, the check needs to change.
        // The error "Type mismatch: inferred type is Int but Boolean was expected" for this line:
        // if (mediaPlayer?.setRenderer(currentRendererItem) == true)
        // suggests that setRenderer in 3.6.2 might return int (e.g. 0 for success).
        // Let's assume it returns boolean for now, and if error persists, this is a key point.
        val rendererSetSuccessfully: Boolean = localMediaPlayer.setRenderer(localRendererItem)
        if (rendererSetSuccessfully) {
            Log.d(TAG, "Renderer successfully set on MediaPlayer.")
        } else {
            Log.e(TAG, "Failed to set renderer on MediaPlayer.")
            // Not stopping casting immediately, to see if it plays locally for debug.
        }

        localMediaPlayer.play()
        Log.d(TAG, "MediaPlayer play() called.")
        localMediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> Log.d(TAG_VLC_EVENT, "Playing")
                MediaPlayer.Event.Paused -> Log.d(TAG_VLC_EVENT, "Paused")
                MediaPlayer.Event.Stopped -> {
                    Log.d(TAG_VLC_EVENT, "Stopped. isCasting: $isCasting")
                    if(isCasting) stopCastingInternals()
                }
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG_VLC_EVENT, "EndReached")
                    if(isCasting) stopCastingInternals()
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG_VLC_EVENT, "EncounteredError")
                    if(isCasting) stopCastingInternals()
                }
                else -> Log.d(TAG_VLC_EVENT, "Event: type=${event.type}")
            }
        }
    }

    private fun getSpsPpsData(): ByteArray? = spsPpsData

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            if (isCasting) {
                stopCastingInternals()
            }
        }
    }

    private fun stopCastingInternals() {
        // Guard clause:
        if (!isCasting && mediaProjection == null && (mediaPlayer == null || mediaPlayer?.isPlaying == false)) {
            // If not casting, no projection, and player is null or not playing, likely already stopped.
            // However, ensure service stops if it's in a limbo state.
            if (isCasting) { // If isCasting was true but other conditions met, force it false.
                isCasting = false
                Log.d(TAG, "Corrected isCasting to false in stopCastingInternals guard.")
            } else {
                 // Log.d(TAG, "stopCastingInternals: Already stopped or nothing to do.")
                 // Still proceed to stopForeground and stopSelf if service needs to terminate.
            }
        }
        if (!isCasting) { // If truly not casting, ensure service stops if it's a stop request.
             Log.d(TAG, "stopCastingInternals called but isCasting is false. Ensuring service stops if needed.")
             stopForeground(true)
             stopSelf()
             return
        }


        Log.d(TAG, "Stopping casting internals...")
        isCasting = false // Set this first to signal other threads

        encodingThread?.interrupt()
        try {
            encodingThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining H264EncoderThread", e)
        }
        encodingThread = null

        nalUnitQueue.clear()
        spsPpsData = null

        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Err releasing VirtualDisplay",e) }
        virtualDisplay = null

        mediaCodec?.let { codec ->
            try { codec.stop() } catch (e: IllegalStateException) { /* Expected if already stopped */ }
            try { codec.release() } catch (e: Exception) { Log.e(TAG, "Err releasing MediaCodec", e) }
        }
        mediaCodec = null
        inputSurface = null

        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Err stopping MediaProjection",e) }
        mediaProjection = null

        mediaPlayer?.apply {
            if (this.isPlaying) {
                this.stop()
            }
            this.setEventListener(null)
        }

        currentRendererItem = null
        // RendererHolder.selectedRendererItem is managed by MainActivity

        Log.d(TAG, "Casting internals stopped.")
        stopForeground(true) // true = remove notification
        stopSelf() // Stop the service itself
    }

    private fun stopCastingAndSelf() {
        stopCastingInternals()
    }

    override fun onDestroy() {
        Log.d(TAG, "ScreenCastingService onDestroy.")
        stopCastingInternals() // Ensure cleanup, calls stopSelf
        mediaPlayer?.release()
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        // RendererHolder.selectedRendererItem?.release() // MainActivity manages this
        RendererHolder.selectedRendererItem = null // Clear reference in holder on service destroy
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
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure this icon exists
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
