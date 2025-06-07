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
import android.net.Uri
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import fi.iki.elonen.NanoHTTPD // Added for HLSServer start method
import kotlin.math.max

// Top-level function for IP address, as implemented previously
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
                        // Prefer site-local addresses
                        if (inetAddress.isSiteLocalAddress) {
                           return inetAddress.hostAddress
                        }
                    }
                }
            }
        }
        // Fallback: If no site-local found, take the first non-loopback IPv4
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

    // HLS Encoding fields
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encodingThread: Thread? = null
    @Volatile private var isEncoding = false
    private var spsPpsData: ByteArray? = null
    private var hlsPlaylistFile: File? = null
    private var tsSegmentFile: File? = null // Current segment file being written
    private var currentSegmentFileOutputStream: FileOutputStream? = null
    private var tsSegmentIndex = 0L
    private var currentSegmentStartTimeUs: Long = -1L // Presentation timestamp of the first frame in the current segment
    private var lastKnownPtsUs: Long = 0L // To track presentation timestamps

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val libVlcArgs = ArrayList<String>()
        libVlcArgs.add("--no-sub-autodetect-file")
        // libVlcArgs.add("--verbose=2") // For more detailed VLC logs if needed

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
                // Handle error: update notification, stop service
                updateNotification(getString(R.string.error_hls_directory_creation_failed))
                stopSelf()
                return
            }
        }
        hlsPlaylistFile = File(hlsFilesDir, "playlist.m3u8")
        Log.d(TAG, "ScreenCastingService created. HLS dir: ${hlsFilesDir?.absolutePath}")
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

                // Call startForeground before getMediaProjection as required for Android Q+ background starts
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.preparing_stream)))

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                if (!setupMediaCodecAndVirtualDisplay()) {
                    stopCastingInternals()
                    return START_NOT_STICKY
                }

                if (hlsServer == null) {
                    try {
                        hlsFilesDir?.let { dir ->
                            hlsServer = HLSServer(hlsPort, dir)
                            hlsServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) // false for not as daemon
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

                // Initialize/Reset HLS variables before starting encoding
                tsSegmentIndex = 0L
                currentSegmentStartTimeUs = -1L
                lastKnownPtsUs = 0L
                spsPpsData = null // Clear previous SPS/PPS
                closeCurrentSegmentFile() // Ensure any previous segment file is closed
                hlsPlaylistFile?.delete() // Delete old playlist to start fresh


                if (!startEncoding()) {
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

    private fun setupMediaCodecAndVirtualDisplay(): Boolean {
        Log.d(TAG, "Setting up MediaCodec and VirtualDisplay...")

        // Get screen density directly from resources
        val screenDensity = resources.displayMetrics.densityDpi
        Log.d(TAG, "Screen density obtained: $screenDensity")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface() ?: throw IOException("MediaCodec input surface creation failed.")

            if (mediaProjection == null) {
                 Log.e(TAG, "MediaProjection is null, cannot create VirtualDisplay.")
                 throw IOException("MediaProjection is null.")
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCapture", VIDEO_WIDTH, VIDEO_HEIGHT, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null)
                ?: throw IOException("VirtualDisplay creation failed.")
            Log.d(TAG, "MediaCodec and VirtualDisplay configured successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec or VirtualDisplay", e)
            updateNotification(getString(R.string.error_mediacodec_setup))
            return false
        }
    }

    private fun startEncoding(): Boolean {
        Log.d(TAG, "Attempting to start encoding...")
        return try {
            mediaCodec?.start()
            isEncoding = true
            encodingThread = Thread { encodeLoop() }
            encodingThread?.name = "ScreenCastEncodingThread"
            encodingThread?.start()
            Log.d(TAG, "Encoding thread started.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec encoding", e)
            updateNotification(getString(R.string.error_starting_encoding))
            isEncoding = false
            false
        }
    }

    private fun encodeLoop() {
        Log.d(TAG, "Encode loop started.")
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding) {
            try {
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US) ?: -1

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mediaCodec?.outputFormat?.also { format ->
                        format.getByteBuffer("csd-0")?.let { csd0 ->
                            format.getByteBuffer("csd-1")?.let { csd1 ->
                                spsPpsData = ByteArray(csd0.remaining() + csd1.remaining()).apply {
                                    csd0.get(this, 0, csd0.remaining())
                                    csd1.get(this, csd0.remaining(), csd1.remaining())
                                }
                                Log.i(TAG, "SPS/PPS data captured: ${spsPpsData?.size} bytes.")
                            }
                        }
                    }
                } else if (outputBufferIndex >= 0) {
                    val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                    if (encodedData != null) {
                        var ptsUs = bufferInfo.presentationTimeUs
                        if (ptsUs <= lastKnownPtsUs) { // Ensure monotonically increasing PTS
                            ptsUs = lastKnownPtsUs + 1
                            // Log.w(TAG, "Adjusted PTS from ${bufferInfo.presentationTimeUs} to $ptsUs")
                        }
                        lastKnownPtsUs = ptsUs

                        val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        var createNewSegment = false

                        if (currentSegmentFileOutputStream == null) { // First segment
                            if (isKeyFrame) {
                                createNewSegment = true
                                Log.i(TAG, "First segment: Starting with key frame.")
                            } else {
                                Log.w(TAG, "First frame is not a key frame. Skipping until a key frame is found.")
                                mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                                continue // Skip this frame
                            }
                        } else { // Not the first segment
                            val segmentDurationUs = ptsUs - currentSegmentStartTimeUs
                            if (segmentDurationUs >= SEGMENT_DURATION_SECONDS * 1_000_000L && isKeyFrame) {
                                createNewSegment = true
                            }
                        }

                        if (createNewSegment) {
                            if (currentSegmentFileOutputStream != null) {
                                closeCurrentSegmentFile() // Finalize the previous segment (segment N)
                                // tsSegmentIndex still refers to segment N here
                                Log.i(TAG, "Updating playlist for closed segment. Current index: $tsSegmentIndex. Previous segment closed.")
                                updateHlsPlaylist() // Update playlist to include segment N
                            }

                            // Prepare for the new segment (segment N+1)
                            tsSegmentIndex++
                            tsSegmentFile = File(hlsFilesDir, "segment$tsSegmentIndex.ts")
                            currentSegmentFileOutputStream = FileOutputStream(tsSegmentFile!!)
                            currentSegmentStartTimeUs = ptsUs
                            Log.i(TAG, "Starting new segment: index=$tsSegmentIndex, file=${tsSegmentFile?.name}, startTimeUs=$currentSegmentStartTimeUs, isKeyFrame=$isKeyFrame")

                            spsPpsData?.let {
                                currentSegmentFileOutputStream?.write(it)
                                Log.d(TAG, "Wrote SPS/PPS to new segment ${tsSegmentFile?.name}")
                            }
                            // Playlist is NOT updated here for the newly opened segment
                        }

                        if (currentSegmentFileOutputStream != null) {
                            val chunk = ByteArray(bufferInfo.size)
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            encodedData.get(chunk)
                            currentSegmentFileOutputStream?.write(chunk)
                        } else if (!createNewSegment) {
                            // This case should ideally not be hit if logic is correct for first segment.
                            Log.w(TAG, "Skipping frame as segment not initialized (not a keyframe for first segment).")
                        }

                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    try { Thread.sleep(10) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in encodeLoop: ${e.message}", e)
                if (isEncoding) {
                     updateNotification(getString(R.string.error_encoding_loop))
                }
                isEncoding = false
            }
        }

        // Cleanup after loop
        closeCurrentSegmentFile()
        if (tsSegmentIndex > 0 || hlsPlaylistFile?.exists() == false ) { // Write playlist if any segments were made or if no playlist exists yet
            updateHlsPlaylist(finished = true)
        }
        Log.i(TAG, "Encode loop finished. Final playlist written.")
    }

    private fun closeCurrentSegmentFile() {
        try {
            currentSegmentFileOutputStream?.flush()
            currentSegmentFileOutputStream?.close()
            Log.d(TAG, "Closed segment file: ${tsSegmentFile?.name ?: "N/A"}")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing segment file output stream", e)
        }
        currentSegmentFileOutputStream = null
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
                val firstSegmentInPlaylist = if (tsSegmentIndex == 0L) 0L else max(1L, tsSegmentIndex - actualMaxSegments + 1)
                writer.write("#EXT-X-MEDIA-SEQUENCE:$firstSegmentInPlaylist\n")

                if (tsSegmentIndex > 0L) {
                    for (i in firstSegmentInPlaylist..tsSegmentIndex) {
                        writer.write("#EXTINF:${String.format("%.3f", SEGMENT_DURATION_SECONDS.toDouble())},\n")
                        writer.write("segment$i.ts\n")
                    }
                }

                if (finished) {
                    writer.write("#EXT-X-ENDLIST\n")
                }
            } // Writer is automatically closed here
            Log.i(TAG, "Playlist file ${hlsPlaylistFile?.name} written successfully. tsSegmentIndex: $tsSegmentIndex. Finished: $finished. First segment in playlist: $firstSegmentInPlaylist")
        } catch (e: IOException) {
            Log.e(TAG, "Error writing HLS playlist", e)
        }
    }

    private fun startServiceDiscovery() {
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
        serviceRendererDiscoverer?.setEventListener(null)
        serviceRendererDiscoverer?.stop()
        serviceRendererDiscoverer = null
        Log.d(TAG, "Service-side renderer discovery stopped and nullified.")
    }

    private inner class ServiceRendererEventListener : org.videolan.libvlc.RendererDiscoverer.EventListener {
        override fun onEvent(event: org.videolan.libvlc.RendererDiscoverer.Event?) {
            if (libVLC == null || serviceRendererDiscoverer == null || event == null || !isCasting) {
                return
            }
            val item = event.item ?: return
            when (event.type) {
                org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded -> {
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.i(TAG, "Target renderer '${targetRendererName}' found by service discoverer!")
                        currentRendererItem = item // Assign event.item directly
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
                        media.addOption(":network-caching=1000") // Reduced caching slightly
                        media.addOption(":hls-timeout=10")
                        media.addOption(":demux=hls") // Explicitly set HLS demuxer

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
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            if (isCasting) {
                stopCastingInternals()
            }
        }
    }

    private fun stopCastingInternals() {
        if (!isCasting && mediaProjection == null && mediaPlayer == null && libVLC == null && !isEncoding && hlsServer == null) {
            Log.d(TAG, "stopCastingInternals: Already stopped or nothing significant to do.")
            stopForeground(true); stopSelf();
            return
        }
        Log.i(TAG, "Stopping casting internals...") // Changed to info
        isCasting = false

        if (isEncoding) {
            isEncoding = false
            encodingThread?.interrupt()
            try {
                encodingThread?.join(1000)
                Log.d(TAG, "Encoding thread joined.")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while joining encodingThread.", e)
                Thread.currentThread().interrupt()
            }
        }
        encodingThread = null

        try { mediaCodec?.stop() } catch (e: IllegalStateException) { Log.e(TAG, "MediaCodec stop error", e)}
        mediaCodec?.release()
        mediaCodec = null

        inputSurface?.release()
        inputSurface = null

        virtualDisplay?.release()
        virtualDisplay = null
        spsPpsData = null

        closeCurrentSegmentFile()

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

        Log.i(TAG, "Casting internals stopped and resources released.") // Changed to info
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "ScreenCastingService onDestroy.") // Changed to info
        stopCastingInternals() // Ensures all above is called
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
        Log.i(TAG, "ScreenCastingService fully destroyed.") // Changed to info
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
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
        private const val IFRAME_INTERVAL_SECONDS = 2 // Increased for potentially better segmenting
        private const val CODEC_TIMEOUT_US = 10000L

        private const val MAX_SEGMENTS_IN_PLAYLIST = 5
        private const val SEGMENT_DURATION_SECONDS = 2L
    }
}
