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
import android.media.MediaRecorder // Added for MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics // Keep this for screenDensity
import android.util.Log
// import android.view.Surface // No longer needed for MediaCodec
import android.view.WindowManager // May not be needed if only for density
import androidx.core.app.NotificationCompat
import home.screen_to_chromecast.MainActivity
import home.screen_to_chromecast.R
import home.screen_to_chromecast.RendererHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.ILibVLC
import java.io.File
// import java.io.FileOutputStream // No longer needed for direct segment writing
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import fi.iki.elonen.NanoHTTPD
import kotlin.math.max

// Top-level function for IP address
fun getDeviceIpAddress(): String? {
    try {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        while (networkInterfaces.hasMoreElements()) {
            val intf = networkInterfaces.nextElement()
            if (intf.isUp && !intf.isLoopback) {
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        if (inetAddress.isSiteLocalAddress) {
                           return inetAddress.hostAddress
                        }
                    }
                }
            }
        }
        val networkInterfacesFallback = NetworkInterface.getNetworkInterfaces()
        while (networkInterfacesFallback.hasMoreElements()) {
            val intf = networkInterfacesFallback.nextElement()
            if (intf.isUp && !intf.isLoopback) {
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        Log.d("GetIPAddress", "Found non-loopback IP (fallback): ${inetAddress.hostAddress}")
                        return inetAddress.hostAddress
                    }
                }
            }
        }
    } catch (ex: Exception) {
        Log.e("ScreenCastingService", "Error getting IP address", ex)
    }
    Log.e("ScreenCastingService", "No suitable IP address found")
    return null
}

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    @Volatile
    private var isCasting = false
    private val mediaProjectionCallback = MediaProjectionCallback()

    private var serviceRendererDiscoverer: org.videolan.libvlc.RendererDiscoverer? = null
    private var targetRendererName: String? = null
    private var targetRendererType: String? = null
    private val serviceRendererListener = ServiceRendererEventListener()

    // HLS Server fields
    private var hlsServer: HLSServer? = null
    private var hlsFilesDir: File? = null
    private val hlsPort = 8088

    // MediaRecorder HLS fields
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var isEncoding = false // Flag to control overall encoding process
    private var hlsPlaylistFile: File? = null
    private var tsSegmentIndex = 0 // Changed to Int
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT


    override fun onCreate() {
        Log.i(TAG, "ScreenCastingService onCreate called.") // Added Log
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

        hlsFilesDir = File(cacheDir, "hls_stream")
        if (!hlsFilesDir!!.exists()) {
            if(!hlsFilesDir!!.mkdirs()){
                Log.e(TAG, "Failed to create HLS directory: ${hlsFilesDir?.absolutePath}")
                updateNotification(getString(R.string.error_hls_directory_creation_failed))
                stopSelf()
                return
            }
        }
        hlsPlaylistFile = File(hlsFilesDir, "playlist.m3u8")
        screenDensity = resources.displayMetrics.densityDpi // Get screen density once
        Log.d(TAG, "ScreenCastingService created. HLS dir: ${hlsFilesDir?.absolutePath}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ScreenCastingService onStartCommand called with action: ${intent?.action}, intent extras: ${intent?.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } } ?: "null"}")
        val action = intent?.action
        // Log.d(TAG, "onStartCommand received with action: $action") // Replaced by the more detailed log above

        if (intent?.action == ACTION_START_CASTING) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val resultDataPresent = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) != null
            // Logging RendererHolder details here before they are locally assigned in the 'when' block
            Log.d(TAG, "ACTION_START_CASTING intent received: resultCode=$resultCode, resultDataPresent=$resultDataPresent, current TargetRendererName=${RendererHolder.selectedRendererName}, current TargetRendererType=${RendererHolder.selectedRendererType}")
        }

        when (action) {
            ACTION_START_CASTING -> {
                if (isCasting) {
                    Log.w(TAG, "Already casting, ignoring START_CASTING command.")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                this.targetRendererName = RendererHolder.selectedRendererName
                this.targetRendererType = RendererHolder.selectedRendererType

                if (resultCode != Activity.RESULT_OK || resultData == null || this.targetRendererName == null || this.targetRendererType == null) {
                    Log.e(TAG, "Invalid data for starting cast. Stopping service.")
                    updateNotification(getString(R.string.error_invalid_casting_parameters))
                    RendererHolder.selectedRendererName = null
                    RendererHolder.selectedRendererType = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                isCasting = true
                currentRendererItem = null

                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.preparing_stream)))

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                if (mediaProjection == null) {
                    Log.e(TAG, "MediaProjection could not be obtained. Stopping.")
                    updateNotification("Error: Failed to start screen capture session.")
                    stopCastingInternals()
                    return START_NOT_STICKY
                }

                if (hlsServer == null) {
                    try {
                        hlsFilesDir?.let { dir ->
                            hlsServer = HLSServer(hlsPort, dir)
                            hlsServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                            Log.d(TAG, "HLS server started on port $hlsPort, serving from ${dir.absolutePath}")
                        } ?: run {
                            Log.e(TAG, "HLS files directory is null. Cannot start HLS Server.")
                            updateNotification(getString(R.string.error_hls_directory))
                            stopCastingInternals()
                            return START_NOT_STICKY
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to start HLS server", e)
                        updateNotification(getString(R.string.error_starting_hls_server))
                        stopCastingInternals()
                        return START_NOT_STICKY
                    }
                }

                isEncoding = true
                tsSegmentIndex = 0 // Changed to Int
                hlsPlaylistFile?.delete()
                updateHlsPlaylist(finished = false) // Create initial empty playlist

                if (!startNewMediaRecorderSegment()) {
                    Log.e(TAG, "Failed to start initial MediaRecorder segment.")
                    stopCastingInternals()
                    return START_NOT_STICKY
                }
                startServiceDiscovery()
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
            }
        }
        return START_NOT_STICKY
    }

    private fun startNewMediaRecorderSegment(): Boolean {
        if (!isEncoding || mediaProjection == null) {
            Log.w(TAG, "startNewMediaRecorderSegment called but isEncoding is false or mediaProjection is null.")
            return false
        }

        // Clean up previous MediaRecorder and VirtualDisplay
        mediaRecorder?.apply {
            setOnInfoListener(null)
            setOnErrorListener(null)
            try { stop() } catch (e: RuntimeException) { Log.e(TAG, "MediaRecorder stop failed", e) } // Catch RuntimeException for stop
            reset()
            release()
        }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null

        tsSegmentIndex++
        val currentSegmentFile = File(hlsFilesDir, "segment$tsSegmentIndex.ts")
        Log.i(TAG, "Starting new MediaRecorder segment: index=$tsSegmentIndex, file=${currentSegmentFile.absolutePath}")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

        try {
            // Attempt to configure audio
            try {
                mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
                mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder?.setAudioSamplingRate(44100) // Standard sampling rate
                mediaRecorder?.setAudioEncodingBitRate(96000) // Reasonable AAC bitrate
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up audio source/encoder, proceeding without audio. Error: ${e.message}", e)
                // Depending on strictness, could throw or simply allow video-only if recorder supports it.
                // For now, we log and continue, setOnErrorListener will catch prepare() if this is fatal.
            }

            mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
            mediaRecorder?.setOutputFile(currentSegmentFile.absolutePath)
            mediaRecorder?.setVideoEncodingBitRate(VIDEO_BITRATE)
            mediaRecorder?.setVideoFrameRate(VIDEO_FRAME_RATE)
            mediaRecorder?.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            mediaRecorder?.setMaxDuration(SEGMENT_DURATION_SECONDS * 1000 + 500) // Add buffer
            mediaRecorder?.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    handleSegmentCompletion()
                }
            }
            mediaRecorder?.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                updateNotification(getString(R.string.error_mediarecorder, what, extra))
                stopCastingInternals()
            }

            mediaRecorder?.prepare()
            val recorderSurface = mediaRecorder?.surface ?: throw IOException("MediaRecorder surface is null after prepare")

            virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCapture", VIDEO_WIDTH, VIDEO_HEIGHT, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorderSurface, null, null)
                ?: throw IOException("VirtualDisplay creation failed for MediaRecorder")

            mediaRecorder?.start()
            Log.i(TAG, "MediaRecorder started for segment $tsSegmentIndex")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare or start MediaRecorder for segment $tsSegmentIndex", e)
            updateNotification(getString(R.string.error_mediarecorder_prepare_start, tsSegmentIndex))
            mediaRecorder?.release()
            mediaRecorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            return false
        }
    }

    private fun handleSegmentCompletion() {
        Log.i(TAG, "Segment $tsSegmentIndex completed (max duration reached).")

        // The current MediaRecorder and its VirtualDisplay will be reset/released by startNewMediaRecorderSegment
        // or by stopCastingInternals if isEncoding becomes false.
        // We must call updateHlsPlaylist *before* startNewMediaRecorderSegment increments tsSegmentIndex
        if (tsSegmentIndex > 0) {
            updateHlsPlaylist()
        }

        if (isEncoding) {
            if (!startNewMediaRecorderSegment()) {
                Log.e(TAG, "Failed to start next MediaRecorder segment after completion of segment $tsSegmentIndex.")
                stopCastingInternals()
            }
        } else {
             Log.i(TAG, "isEncoding is false in handleSegmentCompletion, performing final playlist update.")
             if (tsSegmentIndex > 0) updateHlsPlaylist(finished = true)
        }
    }

    private fun updateHlsPlaylist(finished: Boolean = false) {
        if (hlsPlaylistFile == null || hlsFilesDir == null) {
            Log.e(TAG, "Playlist file or HLS directory is null. Cannot update playlist.")
            return
        }
        Log.i(TAG, "updateHlsPlaylist called. tsSegmentIndex: $tsSegmentIndex, finished: $finished")
        try {
            hlsPlaylistFile!!.bufferedWriter().use { writer ->
                writer.write("#EXTM3U\n")
                writer.write("#EXT-X-VERSION:3\n")
                writer.write("#EXT-X-TARGETDURATION:${SEGMENT_DURATION_SECONDS + 1}\n")

                val actualMaxSegments = if (MAX_SEGMENTS_IN_PLAYLIST <= 0) 1 else MAX_SEGMENTS_IN_PLAYLIST
                val firstSegmentInPlaylist = if (tsSegmentIndex == 0 && !finished) 0 else max(1, tsSegmentIndex - actualMaxSegments + 1)

                writer.write("#EXT-X-MEDIA-SEQUENCE:$firstSegmentInPlaylist\n")

                if (tsSegmentIndex > 0) {
                    // Corrected loop to use Int consistently with tsSegmentIndex
                    for (i in firstSegmentInPlaylist..tsSegmentIndex) {
                        writer.write("#EXTINF:${String.format("%.3f", SEGMENT_DURATION_SECONDS.toDouble())},\n")
                        writer.write("segment$i.ts\n")
                    }
                }

                if (finished) {
                    writer.write("#EXT-X-ENDLIST\n")
                }
            }
            Log.i(TAG, "Playlist file ${hlsPlaylistFile?.name} written successfully. tsSegmentIndex: $tsSegmentIndex. Finished: $finished.")
            // Add Playlist Content Read-Back Log
            try {
                val playlistContent = hlsPlaylistFile?.readText() ?: "Playlist file not found or empty after write attempt."
                Log.i(TAG, "Playlist content read back immediately after write (tsSegmentIndex: $tsSegmentIndex, finished: $finished):\n$playlistContent")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading back playlist content: ${e.message}", e)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing HLS playlist", e)
        }
    }

    private fun startServiceDiscovery() {
        // ... (content of startServiceDiscovery - unchanged from previous state)
        if (libVLC == null) {
            Log.e(TAG, "LibVLC instance is null, cannot start service discovery.")
            updateNotification(getString(R.string.error_libvlc_not_ready))
            stopCastingInternals()
            return
        }
        if (serviceRendererDiscoverer == null) {
            serviceRendererDiscoverer = org.videolan.libvlc.RendererDiscoverer(libVLC!!, "microdns_renderer")
        }
        serviceRendererDiscoverer?.setEventListener(serviceRendererListener)
        if (serviceRendererDiscoverer?.start() == true) {
            Log.d(TAG, "Service-side renderer discovery started.")
            val deviceName = targetRendererName ?: getString(R.string.unknown_device_placeholder)
            updateNotification(getString(R.string.searching_for_device, deviceName))
        } else {
            Log.e(TAG, "Failed to start service-side renderer discovery.")
            updateNotification(getString(R.string.error_starting_discovery))
            stopCastingInternals()
        }
    }

    private fun stopServiceDiscovery() {
        // ... (content of stopServiceDiscovery - unchanged)
        serviceRendererDiscoverer?.setEventListener(null)
        serviceRendererDiscoverer?.stop()
        serviceRendererDiscoverer = null
        Log.d(TAG, "Service-side renderer discovery stopped and nullified.")
    }

    private inner class ServiceRendererEventListener : org.videolan.libvlc.RendererDiscoverer.EventListener {
        // ... (content of ServiceRendererEventListener - unchanged)
        override fun onEvent(event: org.videolan.libvlc.RendererDiscoverer.Event?) {
            if (libVLC == null || serviceRendererDiscoverer == null || event == null || !isCasting) {
                return
            }
            val item = event.item ?: return
            when (event.type) {
                org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded -> {
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.i(TAG, "Target renderer '${targetRendererName}' found by service discoverer!")
                        currentRendererItem = item
                        mediaPlayer?.setRenderer(currentRendererItem)

                        val deviceIp = getDeviceIpAddress()
                        if (deviceIp == null) {
                            Log.e(TAG, "Could not get device IP address. Cannot start HLS playback.")
                            updateNotification(getString(R.string.error_network_config))
                            stopCastingInternals()
                            return
                        }
                        val hlsUrl = "http://$deviceIp:$hlsPort/${hlsPlaylistFile?.name}"
                        Log.i(TAG, "HLS Stream URL for Chromecast: $hlsUrl")

                        if (libVLC == null || mediaPlayer == null) {
                            Log.e(TAG, "LibVLC or MediaPlayer became null before playing.")
                            updateNotification(getString(R.string.error_libvlc_not_ready))
                            stopCastingInternals()
                            return
                        }

                        val media = Media(libVLC, Uri.parse(hlsUrl))
                        media.addOption(":network-caching=1000")
                        media.addOption(":hls-timeout=10")
                        media.addOption(":demux=hls")

                        mediaPlayer?.setMedia(media)
                        media.release()
                        mediaPlayer?.play()

                        val rendererDisplayName = currentRendererItem?.displayName ?: currentRendererItem?.name ?: getString(R.string.unknown_device_placeholder)
                        updateNotification(getString(R.string.casting_to_device, rendererDisplayName))
                        Log.i(TAG, "Playback of HLS stream initiated on renderer: $rendererDisplayName")
                        stopServiceDiscovery()
                    }
                }
                org.videolan.libvlc.RendererDiscoverer.Event.ItemDeleted -> {
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.w(TAG, "Current target renderer '${targetRendererName}' was removed!")
                        updateNotification(getString(R.string.error_device_disconnected, targetRendererName ?: getString(R.string.unknown_device_placeholder)))
                        stopCastingInternals()
                    }
                }
                else -> {}
            }
        }
    }

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        // ... (content of MediaProjectionCallback - unchanged)
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            if (isCasting) {
                stopCastingInternals()
            }
        }
    }

    private fun stopCastingInternals() {
        Log.i(TAG, "Stopping casting internals...")
        isCasting = false

        val wasEncoding = isEncoding
        isEncoding = false

        mediaRecorder?.setOnInfoListener(null)
        mediaRecorder?.setOnErrorListener(null)
        try {
            mediaRecorder?.stop()
            Log.d(TAG, "MediaRecorder stopped.")
        } catch (e: RuntimeException) { // MediaRecorder.stop() can throw RuntimeException
            Log.w(TAG, "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        if (wasEncoding && tsSegmentIndex > 0) {
            updateHlsPlaylist(finished = true)
            Log.i(TAG, "Final HLS playlist with ENDLIST written due to stopCastingInternals.")
        } else if (hlsPlaylistFile?.exists() == true && tsSegmentIndex == 0) {
            hlsPlaylistFile?.delete()
            Log.i(TAG, "Deleted empty initial HLS playlist during stop.")
        }

        hlsServer?.stop()
        hlsServer = null
        Log.d(TAG, "HLS server stopped.")

        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection stopped.")

        mediaPlayer?.let { player ->
            try {
                player.setRenderer(null)
                if (player.isPlaying) player.stop()
                player.setMedia(null)
                player.setEventListener(null)
                player.release()
                Log.d(TAG, "MediaPlayer released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            }
        }
        mediaPlayer = null

        currentRendererItem?.release()
        currentRendererItem = null
        targetRendererName = null
        targetRendererType = null
        RendererHolder.selectedRendererName = null
        RendererHolder.selectedRendererType = null

        stopServiceDiscovery()

        Log.i(TAG, "Casting internals stopped and resources released.")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "ScreenCastingService onDestroy.")
        stopCastingInternals()
        libVLC?.release()
        libVLC = null
        hlsFilesDir?.let { dir ->
            if (dir.exists()) {
                if (dir.deleteRecursively()) {
                    Log.i(TAG, "HLS files directory ${dir.absolutePath} deleted successfully.")
                } else {
                    Log.w(TAG, "Failed to delete HLS files directory ${dir.absolutePath}.")
                }
            }
        }
        hlsFilesDir = null
        super.onDestroy()
        Log.i(TAG, "ScreenCastingService fully destroyed.")
    }

    private fun createNotificationChannel() {
        // ... (content of createNotificationChannel - unchanged)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.notification_channel_description)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        // ... (content of createNotification - unchanged)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        val stopCastIntent = Intent(this, ScreenCastingService::class.java).apply { action = ACTION_STOP_CASTING }
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
        // ... (content of updateNotification - unchanged)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCastingSvc"
        const val ACTION_START_CASTING = "home.screen_to_chromecast.action.START_CASTING"
        const val ACTION_STOP_CASTING = "home.screen_to_chromecast.action.STOP_CASTING"
        const val EXTRA_RESULT_CODE = "home.screen_to_chromecast.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "home.screen_to_chromecast.extra.RESULT_DATA"

        private const val NOTIFICATION_ID = 1237
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCastingChannel"

        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2 * 1024 * 1024
        private const val VIDEO_FRAME_RATE = 30
        // IFRAME_INTERVAL_SECONDS is less critical for MediaRecorder as it handles keyframes internally for TS.
        // But can be kept if any other logic might use it, or removed if purely for MediaCodec.
        // For MediaRecorder, setMaxDuration and setMaxFileSize are the primary controls for segmentation.
        // private const val IFRAME_INTERVAL_SECONDS = 2
        private const val CODEC_TIMEOUT_US = 10000L // Kept if any MediaCodec remnants, but likely unused now.

        private const val MAX_SEGMENTS_IN_PLAYLIST = 5
        private const val SEGMENT_DURATION_SECONDS = 2 // Changed to Int
    }
}
