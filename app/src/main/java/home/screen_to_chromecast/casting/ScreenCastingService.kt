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
import android.net.Uri // Added for HLS playback
import home.screen_to_chromecast.MainActivity
import home.screen_to_chromecast.R
import home.screen_to_chromecast.RendererHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media // Added for HLS playback
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.ILibVLC
// IMediaInput and H264StreamInput removed as per instructions
import java.io.File // Added for HLSServer
import java.io.IOException
// ArrayBlockingQueue, TimeUnit, and thread are no longer needed
// import java.util.concurrent.ArrayBlockingQueue
// import java.util.concurrent.TimeUnit
#HLSServerImport# // Placeholder for HLSServer import
// import kotlin.concurrent.thread
import java.net.Inet4Address
import java.net.NetworkInterface

class ScreenCastingService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null

    // MediaCodec and VirtualDisplay for screen capture
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encodingThread: Thread? = null
    @Volatile private var isEncoding = false // For clarity within encoding logic
    private var spsPpsData: ByteArray? = null // To store SPS/PPS NAL units

    private var libVLC: ILibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRendererItem: RendererItem? = null

    @Volatile
    private var isCasting = false // Master flag for casting state

    // HLS Server and File fields
    private var hlsServer: HLSServer? = null
    private var hlsFilesDir: File? = null
    private val hlsPort = 8088 // Port for HLS server
    private var hlsPlaylistFile: File? = null
    private var tsSegmentFile: File? = null
    private var tsSegmentIndex = 0
    private var currentSegmentFileOutputStream: java.io.FileOutputStream? = null
    private var currentSegmentDurationSeconds = 0.0f


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

        // Initialize HLS directory
        hlsFilesDir = File(cacheDir, "hls_stream")
        if (hlsFilesDir?.exists() == false) {
            val created = hlsFilesDir?.mkdirs()
            if (created == true) {
                Log.d(TAG, "HLS stream directory created at ${hlsFilesDir?.absolutePath}")
            } else {
                Log.e(TAG, "Failed to create HLS stream directory at ${hlsFilesDir?.absolutePath}")
                // Consider how to handle this error - perhaps stopSelf() or notify user
            }
        } else {
            Log.d(TAG, "HLS stream directory already exists at ${hlsFilesDir?.absolutePath}")
        }
        hlsPlaylistFile = File(hlsFilesDir, "playlist.m3u8") // Initialize playlist file

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
                isCasting = true // Set casting flag early, will be reset by stopCastingInternals on any failure

                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                // Update notification to indicate preparation phase
                val preparingNotificationText = getString(R.string.preparing_stream)
                startForeground(NOTIFICATION_ID, createNotification(preparingNotificationText))


                setupMediaCodecAndVirtualDisplay() // Setup codec and display

                // Check if setup was successful (it calls stopCastingInternals on failure, which sets isCasting to false)
                if (!isCasting) { // isCasting would be false if setupMediaCodecAndVirtualDisplay failed and called stopCastingInternals
                    Log.e(TAG, "MediaCodec/VirtualDisplay setup failed. Aborting start.")
                    return START_NOT_STICKY // stopCastingInternals already called, so just return
                }

                // Start HLS Server
                if (hlsServer == null) {
                    try {
                        hlsFilesDir?.let { dir ->
                            if (!dir.exists() && !dir.mkdirs()) {
                                Log.e(TAG, "HLS files directory does not exist and could not be created: ${dir.absolutePath}")
                                updateNotification("Error: Cannot initialize stream server (directory creation failed).")
                                stopCastingInternals()
                                return START_NOT_STICKY
                            }
                            hlsServer = HLSServer(hlsPort, dir)
                            // Start the server in a daemon thread so it doesn't block the service
                            hlsServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true) // true for daemon
                            Log.d(TAG, "HLS server started on port $hlsPort, serving from ${dir.absolutePath}")
                        } ?: run {
                            Log.e(TAG, "HLS files directory is null. Cannot start HLS Server.")
                            updateNotification("Error: Cannot initialize stream server (directory not set).")
                            stopCastingInternals()
                            return START_NOT_STICKY
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to start HLS server", e)
                        updateNotification("Error: Failed to start stream server (IO Exception).")
                        stopCastingInternals()
                        return START_NOT_STICKY
                    }
                }

                startEncoding() // Start encoding process

                // Check if encoding started successfully (it calls stopCastingInternals on failure)
                if (!isEncoding) { // isEncoding would be false if startEncoding failed
                    Log.e(TAG, "Media encoding setup failed. Aborting start.")
                    // HLS server might have started, stopCastingInternals will handle it if called from startEncoding
                    // If startEncoding didn't call stopCastingInternals but failed, ensure cleanup.
                    if (isCasting) stopCastingInternals() // Ensure full cleanup if startEncoding failed partially
                    return START_NOT_STICKY
                }

                startServiceDiscovery() // Start discovery, listener will handle setRenderer. Notification updated inside.

                // If startServiceDiscovery fails, it calls stopCastingInternals, which updates notification.
                // If successful, ServiceRendererEventListener will update to "Casting to [device]" when connected.
            }
            ACTION_STOP_CASTING -> {
                Log.d(TAG, "ACTION_STOP_CASTING received.")
                stopCastingInternals()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupMediaCodecAndVirtualDisplay() {
        Log.d(TAG, "Setting up MediaCodec and VirtualDisplay...")
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            // It's safer to use the default display for metrics, especially in a service context.
            // For Android R (API 30) and above, specific display might be needed if targeting non-default display.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.getRealMetrics(metrics) // display is not defined here, need to use default or pass one
            } else {
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }
            val screenDensity = metrics.densityDpi

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface() ?: run {
                Log.e(TAG, "MediaCodec input surface could not be created.")
                throw IllegalStateException("MediaCodec input surface could not be created.")
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                VIDEO_WIDTH, VIDEO_HEIGHT, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            ) ?: run {
                Log.e(TAG, "VirtualDisplay could not be created.")
                throw IllegalStateException("VirtualDisplay could not be created.")
            }
            Log.d(TAG, "MediaCodec and VirtualDisplay setup successful.")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaCodec or VirtualDisplay", e)
            updateNotification("Error: Failed to initialize screen capture.")
            stopCastingInternals() // This will also handle cleanup
        }
    }

    private fun encodeLoop() {
        Log.d(TAG, "Encode loop started.")
        val bufferInfo = MediaCodec.BufferInfo()
        var lastPlaylistUpdateTime = 0L

        while (isEncoding) {
            try {
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US) ?: -1

                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "MediaCodec output format changed.")
                        val outputFormat = mediaCodec?.outputFormat
                        outputFormat?.let {
                            val csd0 = it.getByteBuffer("csd-0")
                            val csd1 = it.getByteBuffer("csd-1")
                            if (csd0 != null && csd1 != null) {
                                spsPpsData = ByteArray(csd0.remaining() + csd1.remaining())
                                csd0.get(spsPpsData, 0, csd0.remaining())
                                csd1.get(spsPpsData, csd0.remaining(), csd1.remaining())
                                Log.i(TAG, "SPS/PPS data captured: ${spsPpsData?.size} bytes")
                            }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                        // Log.v(TAG, "dequeueOutputBuffer timed out, retrying..."); // Too verbose
                    }
                    else -> {
                        if (outputBufferIndex >= 0) {
                            val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                                val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                                if (tsSegmentFile == null || currentSegmentDurationSeconds >= SEGMENT_DURATION_SECONDS || (isKeyFrame && tsSegmentFile == null)) { // Start new segment on key frame if no segment yet
                                    closeCurrentSegmentFile()
                                    tsSegmentIndex++
                                    currentSegmentDurationSeconds = 0f
                                    tsSegmentFile = File(hlsFilesDir, "segment${tsSegmentIndex}.ts")
                                    Log.i(TAG, "Creating new segment: ${tsSegmentFile?.name}")
                                    try {
                                        currentSegmentFileOutputStream = FileOutputStream(tsSegmentFile)
                                        if (isKeyFrame && spsPpsData != null) { // Prepend SPS/PPS to key frame at start of new segment
                                            currentSegmentFileOutputStream?.write(spsPpsData)
                                            Log.d(TAG, "Wrote SPS/PPS data to segment ${tsSegmentFile?.name}")
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "Failed to open FileOutputStream for segment: ${tsSegmentFile?.absolutePath}", e)
                                        // Handle error, maybe stop encoding?
                                        isEncoding = false // Stop encoding on critical I/O error
                                        break // Exit loop
                                    }
                                    // Update playlist immediately for new segment
                                    updateHlsPlaylist()
                                    lastPlaylistUpdateTime = System.currentTimeMillis()
                                }

                                try {
                                    val chunk = ByteArray(bufferInfo.size)
                                    encodedData.get(chunk)
                                    currentSegmentFileOutputStream?.write(chunk)
                                    // Approximate duration based on frame rate, this is very rough
                                    currentSegmentDurationSeconds += 1.0f / VIDEO_FRAME_RATE
                                } catch (e: IOException) {
                                    Log.e(TAG, "Failed to write data to segment: ${tsSegmentFile?.absolutePath}", e)
                                    isEncoding = false // Stop encoding on critical I/O error
                                    break // Exit loop
                                }

                                // Periodically update playlist even if segment isn't full yet, e.g. every second
                                if (System.currentTimeMillis() - lastPlaylistUpdateTime > 1000 && tsSegmentIndex > 0) {
                                   // updateHlsPlaylist() // This can be too frequent, let's do it on segment change or end
                                   // For live streams, playlist needs to be updated to reflect new segments.
                                   // However, the main update is done when a new segment is created.
                                }
                            }
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in encodeLoop, stopping encoding.", e)
                isEncoding = false // Ensure loop terminates
            }
        }
        Log.d(TAG, "Encode loop finished.")
        closeCurrentSegmentFile()
        updateHlsPlaylist(finished = true) // Final playlist update with ENDLIST tag
    }

    private fun closeCurrentSegmentFile() {
        try {
            currentSegmentFileOutputStream?.flush()
            currentSegmentFileOutputStream?.close()
            Log.d(TAG, "Closed segment file: ${tsSegmentFile?.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing segment file: ${tsSegmentFile?.name}", e)
        }
        currentSegmentFileOutputStream = null
    }

    private fun updateHlsPlaylist(finished: Boolean = false) {
        if (hlsFilesDir == null || hlsPlaylistFile == null) {
            Log.e(TAG, "Cannot update HLS playlist, directory or playlist file is null.")
            return
        }
        Log.d(TAG, "Updating HLS playlist: ${hlsPlaylistFile?.name}, finished: $finished")

        val playlistContent = StringBuilder()
        playlistContent.appendLine("#EXTM3U")
        playlistContent.appendLine("#EXT-X-VERSION:3")
        // Target duration should be the maximum segment duration, rounded up.
        playlistContent.appendLine("#EXT-X-TARGETDURATION:${SEGMENT_DURATION_SECONDS + 1}")
        // Media sequence starts from the first segment in the current playlist
        val firstSegmentInPlaylist = max(0, tsSegmentIndex - MAX_SEGMENTS_IN_PLAYLIST)
        playlistContent.appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSegmentInPlaylist")

        val startSegmentIndex = max(1, tsSegmentIndex - MAX_SEGMENTS_IN_PLAYLIST + 1)
        for (i in startSegmentIndex..tsSegmentIndex) {
            // Actual segment duration might vary slightly. Using fixed value for now.
            playlistContent.appendLine("#EXTINF:${String.format("%.3f", SEGMENT_DURATION_SECONDS.toFloat())},")
            playlistContent.appendLine("segment${i}.ts")
        }

        if (finished) {
            playlistContent.appendLine("#EXT-X-ENDLIST")
        }

        try {
            hlsPlaylistFile?.writeText(playlistContent.toString())
            Log.i(TAG, "HLS playlist updated: ${hlsPlaylistFile?.absolutePath}")
            Log.v(TAG, "Playlist content:\n$playlistContent")
        } catch (e: IOException) {
            Log.e(TAG, "Error writing HLS playlist file", e)
        }
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
            val deviceName = targetRendererName ?: "device" // Use "device" as a fallback
            updateNotification(getString(R.string.searching_for_device, deviceName))
        } else {
            Log.e(TAG, "Failed to start service-side renderer discovery.")
            updateNotification(getString(R.string.error_starting_discovery)) // Use existing string
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

                        val deviceIp = getDeviceIpAddress()
                        if (deviceIp == null) {
                            Log.e(TAG, "Could not get device IP address. Cannot start HLS playback.")
                            updateNotification("Error: Network configuration issue. Please check Wi-Fi.") // More specific message
                            stopCastingInternals() // Stop if IP is not available
                            return@onEvent // from the listener's onEvent
                        }

                        // Construct HLS URL using the dynamic port and playlist file name
                        val hlsUrl = "http://$deviceIp:$hlsPort/${hlsPlaylistFile?.name ?: "playlist.m3u8"}"
                        Log.i(TAG, "HLS Stream URL for Chromecast: $hlsUrl")

                        if (libVLC == null || mediaPlayer == null) {
                            Log.e(TAG, "LibVLC or MediaPlayer instance is null when trying to play. Aborting.")
                            updateNotification("Error: VLC Player not ready.")
                            stopCastingInternals()
                            return@onEvent
                        }

                        val media = Media(libVLC, Uri.parse(hlsUrl))
                        // media.addOption(":demux=hls") // Not adding this for now, LibVLC usually detects HLS.
                        // Add network caching to potentially improve HLS stability
                        media.addOption(":network-caching=1500") // 1.5 seconds caching
                        media.addOption(":hls-live-insertxframetag=yes") // Useful for some HLS streams
                        media.addOption(":hls-timeout=10") // Timeout for HLS operations in seconds


                        mediaPlayer?.setMedia(media)
                        media.release() // Media object can be released after setMedia

                        mediaPlayer?.play()
                        Log.i(TAG, "Attempting to play HLS stream on renderer: ${item.displayName}")

                        val rendererDisplayName = item.displayName ?: item.name ?: "Unknown Device"
                        updateNotification(getString(R.string.casting_to_device, rendererDisplayName))
                        Log.i(TAG, "Playback of HLS stream initiated on renderer: $rendererDisplayName")

                        stopServiceDiscovery() // Found our target, no need to discover further
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
        isEncoding = false // Set encoding flag to false immediately

        // Stop Encoding Thread
        encodingThread?.interrupt() // Attempt to interrupt if blocked
        try {
            encodingThread?.join(1000) // Wait for thread to finish
            Log.d(TAG, "Encoding thread joined.")
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining encoding thread.", e)
            Thread.currentThread().interrupt() // Preserve interrupt status
        }
        encodingThread = null

        // Stop and release MediaCodec
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            Log.d(TAG, "MediaCodec stopped and released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping or releasing MediaCodec", e)
        }
        mediaCodec = null

        // Release InputSurface
        inputSurface?.release()
        inputSurface = null
        Log.d(TAG, "InputSurface released.")

        // Release VirtualDisplay
        virtualDisplay?.release()
        virtualDisplay = null
        Log.d(TAG, "VirtualDisplay released.")

        // Clear SPS/PPS data
        spsPpsData = null

        // Finalize HLS playlist - this is now handled at the end of encodeLoop
        // updateHlsPlaylist(finished = true) // Ensure ENDLIST is written (if not already by encodeLoop)
        // No, encodeLoop should be solely responsible for the finished = true call.

        // Close any open segment file - this is now handled by encodeLoop or closeCurrentSegmentFile
        // closeCurrentSegmentFile()

        stopServiceDiscovery() // Stop service-side discovery if it's running

        // Stop HLS Server
        hlsServer?.let {
            try {
                it.stop()
                Log.d(TAG, "HLS server stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping HLS server", e)
            }
        }
        hlsServer = null

        // Optional: Clean up HLS files directory (example, can be enabled if needed)
        // For debugging, files are kept. For production, cleanup might be desired here or in onDestroy.
        /*
        hlsFilesDir?.let { dir ->
            if (dir.exists()) {
                Log.d(TAG, "Cleaning HLS stream directory: ${dir.absolutePath}")
                dir.listFiles()?.forEach { file ->
                    Log.d(TAG, "Deleting HLS file: ${file.name}")
                    file.delete()
                }
                // Optionally delete the directory itself: dir.delete()
            }
        }
        */
        tsSegmentIndex = 0 // Reset segment index for next session
        currentSegmentDurationSeconds = 0f


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
                player.setRenderer(null) // Detach renderer first
                if (player.isPlaying) {
                    player.stop() // Stop playback
                }
                player.setMedia(null) // Crucial: Detach media to release network resources for HLS
                Log.d(TAG, "Media detached from MediaPlayer.")
                player.setEventListener(null) // Remove listeners
                Log.d(TAG, "Releasing MediaPlayer instance.")
                player.release() // Release the player itself
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
        // LibVLC release is also handled in stopCastingInternals if it hasn't been released yet.
        stopCastingInternals()

        // Clean up HLS files directory
        hlsFilesDir?.let { dir ->
            if (dir.exists()) {
                if (dir.deleteRecursively()) {
                    Log.i(TAG, "HLS files directory ${dir.absolutePath} deleted successfully.")
                } else {
                    Log.w(TAG, "Failed to delete HLS files directory ${dir.absolutePath}.")
                }
            }
        }
        hlsFilesDir = null // Nullify the reference

        // RendererHolder clearing is also in stopCastingInternals, so no need to repeat here.
        super.onDestroy()
        Log.i(TAG, "ScreenCastingService fully destroyed.") // Changed to info for consistency
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

        // Video Encoding Parameters
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2 * 1024 * 1024 // 2 Mbps
        private const val VIDEO_FRAME_RATE = 30
        private const val IFRAME_INTERVAL_SECONDS = 1 // Key frame interval
        private const val CODEC_TIMEOUT_US = 10000L // Timeout for MediaCodec operations

        // HLS Specific Constants
        private const val MPEG_TS_PACKET_SIZE = 188 // Standard TS packet size
        private const val MAX_SEGMENTS_IN_PLAYLIST = 5 // Max segments in m3u8 before sliding window
        private const val SEGMENT_DURATION_SECONDS = 2 // Target duration for each .ts segment
    }
}

// Utility function to get device IP address
// Placing it outside the class, in the same file, makes it a top-level function.
// Alternatively, it could be in the companion object.
private fun getDeviceIpAddress(): String? {
    try {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        while (networkInterfaces.hasMoreElements()) {
            val intf = networkInterfaces.nextElement()
            // Consider Wi-Fi interfaces only: intf.displayName.contains("wlan", ignoreCase = true)
            // For now, keeping it more general but ensuring it's up and not loopback.
            if (intf.isUp && !intf.isLoopback) {
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        // Additionally, ensure it's a site-local address if multiple IPs are present
                        // This helps to avoid public IPs if the device has one.
                        if (inetAddress.isSiteLocalAddress) {
                             Log.d("GetIPAddress", "Found site-local IP: ${inetAddress.hostAddress}")
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
        }
        // Fallback if no site-local found, but still valid and not loopback
        // This part might be removed if strict site-local is desired.
        // For now, let's re-iterate and take the first valid non-loopback IPv4 if no site-local was preferred.
        val networkInterfacesFallback = NetworkInterface.getNetworkInterfaces()
        while (networkInterfacesFallback.hasMoreElements()) {
            val intf = networkInterfacesFallback.nextElement()
            if (intf.isUp && !intf.isLoopback) {
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        Log.d("GetIPAddress", "Found non-loopback IP (fallback): ${inetAddress.hostAddress}")
                        return inetAddress.hostAddress // Return first one found as fallback
                    }
                }
            }
        }

    } catch (ex: Exception) {
        Log.e("GetIPAddress", "Error getting IP address", ex)
    }
    Log.e("GetIPAddress", "No suitable IP address found")
    return null
}
