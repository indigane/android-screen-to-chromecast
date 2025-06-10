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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
// import java.io.FileOutputStream // No longer needed for direct segment writin
//g
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
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Ad
dress) {
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
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Ad
dress) {
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

    private var serviceRendererDiscoverer: org.videolan.libvlc.RendererDiscovere
r? = null
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
    @Volatile private var isEncoding = false // Flag to control overall encoding
// process
    // private var hlsPlaylistFile: File? = null // REMOVED
    private var tsSegmentIndex = 0 // Effectively unused for naming, kept for po
//tential logging.
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT

    // Handler for timed segment rollover - REMOVED
    // private var segmentRolloverHandler: Handler? = null // REMOVED
    // private var segmentRolloverRunnable: Runnable? = null // REMOVED

    private val serviceHandler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null


    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVI
CE) as MediaProjectionManager
        val libVlcArgs = ArrayList<String>()
        libVlcArgs.add("--no-sub-autodetect-file")
        libVlcArgs.add("-vvv") // Add this line for maximum verbosity

        try {
            libVLC = LibVLC(this, libVlcArgs)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error initializing LibVLC in Service: ${e.localizedMessa
ge}", e)
            stopSelf()
            return
        }
        mediaPlayer = MediaPlayer(libVLC)
        createNotificationChannel()

        hlsFilesDir = File(cacheDir, "hls_stream") // Still needed for live_stre
//am.ts
        if (!hlsFilesDir!!.exists()) {
            if(!hlsFilesDir!!.mkdirs()){
                Log.e(TAG, "Failed to create HLS directory: ${hlsFilesDir?.absol
utePath}")
                updateNotification(getString(R.string.error_hls_directory_creati
on_failed))
                stopSelf()
                return
            }
        }
        // hlsPlaylistFile = File(hlsFilesDir, "playlist.m3u8") // REMOVED
        screenDensity = resources.displayMetrics.densityDpi // Get screen densit
//y once

        // segmentRolloverHandler = Handler(Looper.getMainLooper()) // REMOVED

        Log.d(TAG, "ScreenCastingService created. HLS dir: ${hlsFilesDir?.absolu
tePath}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
{
        val action = intent?.action
        Log.d(TAG, "onStartCommand received with action: $action")

        when (action) {
            ACTION_START_CASTING -> {
                if (isCasting) {
                    Log.w(TAG, "Already casting, ignoring START_CASTING command.
")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT
_DATA)

                this.targetRendererName = RendererHolder.selectedRendererName
                this.targetRendererType = RendererHolder.selectedRendererType

                if (resultCode != Activity.RESULT_OK || resultData == null || th
is.targetRendererName == null || this.targetRendererType == null) {
                    Log.e(TAG, "Invalid data for starting cast. Stopping service
.")
                    updateNotification(getString(R.string.error_invalid_casting_
parameters))
                    RendererHolder.selectedRendererName = null
                    RendererHolder.selectedRendererType = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                isCasting = true
                currentRendererItem = null

                startForeground(NOTIFICATION_ID, createNotification(getString(R.
string.preparing_stream)))

                mediaProjection = mediaProjectionManager?.getMediaProjection(res
ultCode, resultData)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)

                if (mediaProjection == null) {
                    Log.e(TAG, "MediaProjection could not be obtained. Stopping.
")
                    updateNotification("Error: Failed to start screen capture se
ssion.")
                    stopCastingInternals()
                    return START_NOT_STICKY
                }

                if (hlsServer == null) {
                    try {
                        hlsFilesDir?.let { dir ->
                            hlsServer = HLSServer(hlsPort, dir)
                            hlsServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, fals
e)
                            Log.d(TAG, "HLS server started on port $hlsPort, ser
ving from ${dir.absolutePath}")
                        } ?: run {
                            Log.e(TAG, "HLS files directory is null. Cannot star
t HLS Server.")
                            updateNotification(getString(R.string.error_hls_dire
ctory))
                            stopCastingInternals()
                            return START_NOT_STICKY
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to start HLS server", e)
                        updateNotification(getString(R.string.error_starting_hls
_server))
                        stopCastingInternals()
                        return START_NOT_STICKY
                    }
                }

                isEncoding = true
                tsSegmentIndex = 0 // This is not strictly used for naming anymo
//re.
                // hlsPlaylistFile?.delete() // REMOVED

                if (!startNewMediaRecorderSegment()) { // This will now start re
//cording to LIVE_TS_FILENAME
                    Log.e(TAG, "Failed to start MediaRecorder for single file. S
topping service.")
                    updateNotification("Error: Could not start screen recording.
") // Generic error
                    stopCastingInternals()
                    return START_NOT_STICKY
                }

                // updateHlsPlaylist(finished = false) // REMOVED - No playlist
//to update
                startServiceDiscovery() // Service discovery still needed to fin
//d the Chromecast
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
            Log.w(TAG, "startNewMediaRecorderSegment called but isEncoding is fa
lse or mediaProjection is null.")
            return false
        }

        // Clean up previous MediaRecorder and VirtualDisplay
        mediaRecorder?.apply {
            setOnInfoListener(null)
            setOnErrorListener(null)
            try { stop() } catch (e: RuntimeException) { Log.e(TAG, "MediaRecord
er stop failed", e) } // Catch RuntimeException for stop
            reset()
            release()
        }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null

        // tsSegmentIndex++ // REMOVED - No longer incrementing for multiple seg
//ments
        val currentSegmentFile = File(hlsFilesDir, LIVE_TS_FILENAME) // CHANGED
//to single filename
        Log.i(TAG, "Starting MediaRecorder to output to single file: ${currentSe
gmentFile.absolutePath}")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Medi
aRecorder(this) else MediaRecorder()

        try {
            mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
            mediaRecorder?.setOutputFile(currentSegmentFile.absolutePath)
            mediaRecorder?.setVideoEncodingBitRate(VIDEO_BITRATE)
            mediaRecorder?.setVideoFrameRate(VIDEO_FRAME_RATE)
            mediaRecorder?.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            // mediaRecorder?.setMaxDuration(...) // REMOVED - No max duration f
//or single file mode

            mediaRecorder?.setOnInfoListener { _, what, extra ->
                Log.i(TAG, "MediaRecorder OnInfo (single file mode): what=$what,
 extra=$extra")
                // No MAX_DURATION_REACHED handling needed.
                // Other info codes (like errors) could still be logged or handl
//ed if necessary.
            }
            mediaRecorder?.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error (single file mode): what=$what,
extra=$extra")
                updateNotification(getString(R.string.error_mediarecorder, what,
 extra))
                stopCastingInternals()
            }

            mediaRecorder?.prepare()
            val recorderSurface = mediaRecorder?.surface ?: throw IOException("M
ediaRecorder surface is null after prepare")

            virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenCaptur
e", VIDEO_WIDTH, VIDEO_HEIGHT, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorderSurface
, null, null)
                ?: throw IOException("VirtualDisplay creation failed for MediaRe
corder")

            mediaRecorder?.start()
            Log.i(TAG, "MediaRecorder started for file: ${currentSegmentFile.nam
e}")

            // Timer scheduling REMOVED
            // segmentRolloverRunnable?.let { segmentRolloverHandler?.removeCall
//backs(it) }
            // if (isEncoding) { ... }

            return true
        } catch (e: Exception) {
            Log.i(TAG, "Failed to prepare or start MediaRecorder for file: ${cur
rentSegmentFile.name}", e) // Log updated
            updateNotification(getString(R.string.error_mediarecorder_prepare_st
art, 0)) // tsSegmentIndex is 0 or irrelevant
            mediaRecorder?.release()
            mediaRecorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            return false
        }
    }

    // private fun handleSegmentCompletion() { // REMOVED
    //     Log.i(TAG, "Segment $tsSegmentIndex completion triggered. Current isE
//ncoding: $isEncoding")
    //
    //     // The current MediaRecorder and its VirtualDisplay will be reset/rel
//eased by startNewMediaRecorderSegment
    //     // or by stopCastingInternals if isEncoding becomes false.
    //     // We must call updateHlsPlaylist *before* startNewMediaRecorderSegme
//nt increments tsSegmentIndex
    //     if (tsSegmentIndex > 0) {
    //         updateHlsPlaylist()
    //     }
    //
    //     if (isEncoding) {
    //         if (!startNewMediaRecorderSegment()) {
    //             Log.e(TAG, "Failed to start next MediaRecorder segment after
//completion of segment $tsSegmentIndex.")
    //             stopCastingInternals()
    //         }
    //     } else {
    //          Log.i(TAG, "isEncoding is false in handleSegmentCompletion, perf
//orming final playlist update.")
    //          if (tsSegmentIndex > 0) updateHlsPlaylist(finished = true)
    //     }
    // }

    // private fun updateHlsPlaylist(finished: Boolean = false) { // REMOVED
    //     if (hlsPlaylistFile == null || hlsFilesDir == null) {
    //         Log.e(TAG, "Playlist file or HLS directory is null. Cannot update
// playlist.")
    //         return
    //     }
    //     Log.i(TAG, "updateHlsPlaylist called. tsSegmentIndex: $tsSegmentIndex
//, finished: $finished")
    //     try {
    //         hlsPlaylistFile!!.bufferedWriter().use { writer ->
    //             writer.write("#EXTM3U
//")
    //             writer.write("#EXT-X-VERSION:3
//")
    //             writer.write("#EXT-X-TARGETDURATION:${SEGMENT_DURATION_SECOND
//S + 1}
//")
    //
    //             val actualMaxSegments = if (MAX_SEGMENTS_IN_PLAYLIST <= 0) 1
//else MAX_SEGMENTS_IN_PLAYLIST
    //             val firstSegmentInPlaylist = if (tsSegmentIndex == 0 && !fini
//shed) 0 else max(1, tsSegmentIndex - actualMaxSegments + 1)
    //
    //             writer.write("#EXT-X-MEDIA-SEQUENCE:$firstSegmentInPlaylist
//")
    //
    //             // tsSegmentIndex is 1 when the first segment *just started*
//due to the previous step's changes.
    //             // The playlist is updated *after* startNewMediaRecorderSegme
//nt (which increments tsSegmentIndex).
    //             // So, when updateHlsPlaylist is called for the very first ti
//me (not finished, just started):
    //             // - tsSegmentIndex will be 1.
    //             // - firstSegmentInPlaylist will be max(1, 1 - MAX_SEGMENTS_I
//N_PLAYLIST + 1), which is 1 if MAX_SEGMENTS_IN_PLAYLIST >=1
    //             if (tsSegmentIndex == 1 && !finished) { // Special case for i
//nitial playlist when segment1.ts has just started
    //                 writer.write("#EXTINF:${String.format("%.3f", SEGMENT_DUR
//ATION_SECONDS.toDouble())},
//")
    //                 writer.write("segment1.ts
//")
    //             } else if (tsSegmentIndex > 0) { // For subsequent updates or
// when finishing
    //                 // The loop should correctly handle listing up to MAX_SEG
//MENTS_IN_PLAYLIST segments
    //                 // firstSegmentInPlaylist is already calculated to handle
// the sliding window.
    //                 for (i in firstSegmentInPlaylist..tsSegmentIndex) {
    //                     writer.write("#EXTINF:${String.format("%.3f", SEGMENT
//_DURATION_SECONDS.toDouble())},
//")
    //                     writer.write("segment$i.ts
//")
    //                 }
    //             }
    //             // No segment entry if tsSegmentIndex is 0, which shouldn't h
//appen if called after first segment start.
    //
    //             if (finished) {
    //                 writer.write("#EXT-X-ENDLIST
//")
    //             }
    //         }
    //         Log.i(TAG, "Playlist file ${hlsPlaylistFile?.name} written succes
//sfully. tsSegmentIndex: $tsSegmentIndex. Finished: $finished.")
    //     } catch (e: IOException) {
    //         Log.e(TAG, "Error writing HLS playlist", e)
    //     }
    // }

    private fun startServiceDiscovery() {
        // ... (content of startServiceDiscovery - unchanged from previous state
//)
        if (libVLC == null) {
            Log.e(TAG, "LibVLC instance is null, cannot start service discovery.
")
            updateNotification(getString(R.string.error_libvlc_not_ready))
            stopCastingInternals()
            return
        }
        if (serviceRendererDiscoverer == null) {
            serviceRendererDiscoverer = org.videolan.libvlc.RendererDiscoverer(l
ibVLC!!, "microdns_renderer")
        }
        serviceRendererDiscoverer?.setEventListener(serviceRendererListener)
        if (serviceRendererDiscoverer?.start() == true) {
            Log.d(TAG, "Service-side renderer discovery started.")
            val deviceName = targetRendererName ?: getString(R.string.unknown_de
vice_placeholder)
            updateNotification(getString(R.string.searching_for_device, deviceNa
me))
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
        override fun onEvent(event: org.videolan.libvlc.RendererDiscoverer.Event?) {
            if (libVLC == null || serviceRendererDiscoverer == null || event == null || !isCasting) {
                Log.w(TAG, "ServiceRendererEventListener: Ignoring event due to invalid state (libVLC=$libVLC, discoverer=$serviceRendererDiscoverer, event=$event, isCasting=$isCasting)")
                return
            }
            // event.item can be null for some event types not ItemAdded or ItemDeleted
            val item = event.item
            if (item == null && (event.type == org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded || event.type == org.videolan.libvlc.RendererDiscoverer.Event.ItemDeleted)) {
                Log.w(TAG, "ServiceRendererEventListener: Event type ${event.type} received with a null item. Ignoring.")
                return
            }

            when (event.type) {
                org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded -> {
                    item ?: run { // Ensure item is not null for ItemAdded before proceeding
                        Log.w(TAG, "ServiceRendererEventListener: ItemAdded event with null item. Ignoring.")
                        return
                    }
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.i(TAG, "Target renderer '${targetRendererName}' found by service discoverer!")
                        currentRendererItem = item
                        mediaPlayer?.setRenderer(currentRendererItem)

                        playbackRunnable?.let { serviceHandler.removeCallbacks(it) } // Cancel previous

                        playbackRunnable = Runnable {
                            if (!isCasting || currentRendererItem == null || currentRendererItem?.name != targetRendererName) {
                                Log.w(TAG, "Playback runnable: Casting stopped, renderer lost, or target changed. Aborting.")
                                return@Runnable
                            }

                            val deviceIp = getDeviceIpAddress()
                            if (deviceIp == null) {
                                Log.e(TAG, "Playback runnable: Could not get device IP. Cannot start playback.")
                                updateNotification(getString(R.string.error_network_config))
                                stopCastingInternals()
                                return@Runnable
                            }
                            val streamUrl = "http://$deviceIp:$hlsPort/$LIVE_TS_FILENAME"
                            Log.i(TAG, "Playback runnable: Stream URL for Chromecast: $streamUrl")

                            if (libVLC == null || mediaPlayer == null || mediaPlayer?.isReleased == true) {
                                Log.e(TAG, "Playback runnable: LibVLC or MediaPlayer null/released.")
                                updateNotification(getString(R.string.error_libvlc_not_ready))
                                return@Runnable
                            }

                            if (mediaPlayer?.renderer != currentRendererItem) {
                                 Log.w(TAG, "Playback runnable: MediaPlayer renderer changed. Re-setting.")
                                 mediaPlayer?.setRenderer(currentRendererItem)
                            }

                            val media = Media(libVLC, Uri.parse(streamUrl))
                            media.addOption(":network-caching=1000")
                            media.addOption(":demux=ts")

                            mediaPlayer?.setMedia(media)
                            media.release()
                            mediaPlayer?.play()

                            val rendererDisplayName = currentRendererItem?.displayName ?: currentRendererItem?.name ?: getString(R.string.unknown_device_placeholder)
                            updateNotification(getString(R.string.casting_to_device, rendererDisplayName))
                            Log.i(TAG, "Playback runnable: Playback of TS stream initiated on renderer: $rendererDisplayName")
                        }

                        Log.d(TAG, "Scheduling playback for '${targetRendererName}' in ${INITIAL_PLAYBACK_DELAY_MS}ms.")
                        serviceHandler.postDelayed(playbackRunnable!!, INITIAL_PLAYBACK_DELAY_MS)
                        stopServiceDiscovery()
                    }
                }
                org.videolan.libvlc.RendererDiscoverer.Event.ItemDeleted -> {
                    item ?: run { // Ensure item is not null for ItemDeleted
                        Log.w(TAG, "ServiceRendererEventListener: ItemDeleted event with null item. Ignoring.")
                        return
                    }
                    if (item.name == targetRendererName && item.type == targetRendererType) {
                        Log.w(TAG, "Current target renderer '${targetRendererName}' was removed!")
                        updateNotification(getString(R.string.error_device_disconnected, targetRendererName ?: getString(R.string.unknown_device_placeholder)))
                        stopCastingInternals()
                    }
                }
                else -> {
                    // Log.d(TAG, "ServiceRendererEventListener: Received event type: ${event.type} with item: ${item?.name}")
                }
            } // End of when
        } // End of onEvent method
    } // End of ServiceRendererEventListener class

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

        // Cancel pending playback runnable
        playbackRunnable?.let {
            serviceHandler.removeCallbacks(it)
            Log.d(TAG, "Cancelled pending playback runnable.")
        }
        playbackRunnable = null // Clear it

        // Cancel any pending segment rollover timer first - REMOVED
        // segmentRolloverRunnable?.let {
        //     segmentRolloverHandler?.removeCallbacks(it)
        //     Log.d(TAG, "Cancelled pending segment rollover timer in stopCasti
//ngInternals.")
        // }
        // segmentRolloverRunnable = null // Clear it

        val wasEncoding = isEncoding
        isEncoding = false

        mediaRecorder?.setOnInfoListener(null)
        mediaRecorder?.setOnErrorListener(null)
        try {
            mediaRecorder?.stop()
            Log.d(TAG, "MediaRecorder stopped.")
        } catch (e: RuntimeException) { // MediaRecorder.stop() can throw Runtim
//eException
            Log.w(TAG, "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        // if (wasEncoding && tsSegmentIndex > 0) { // REMOVED - No playlist to
//update
        //     updateHlsPlaylist(finished = true)
        //     Log.i(TAG, "Final HLS playlist with ENDLIST written due to stopCa
//stingInternals.")
        // } else if (hlsPlaylistFile?.exists() == true && tsSegmentIndex == 0)
//{ // REMOVED
        //     hlsPlaylistFile?.delete()
        //     Log.i(TAG, "Deleted empty initial HLS playlist during stop.")
        // }

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
                    Log.i(TAG, "HLS files directory ${dir.absolutePath} deleted
successfully.")
                } else {
                    Log.w(TAG, "Failed to delete HLS files directory ${dir.absol
utePath}.")
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
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString
(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.notification_channel_descri
ption)
            getSystemService(NotificationManager::class.java).createNotification
Channel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        // ... (content of createNotification - unchanged)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODE
S.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationInten
t, pendingIntentFlags)
        val stopCastIntent = Intent(this, ScreenCastingService::class.java).appl
y { action = ACTION_STOP_CASTING }
        val stopCastPendingIntent = PendingIntent.getService(this, 1, stopCastIn
tent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.casting_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_cast, getString(R.string.stop_casting_
action), stopCastPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        // ... (content of updateNotification - unchanged)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
 as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentTe
xt))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCastingSvc"
        private const val LIVE_TS_FILENAME = "live_stream.ts" // Ensure this is
//defined
        private const val INITIAL_PLAYBACK_DELAY_MS = 3000L // 3 seconds
        const val ACTION_START_CASTING = "home.screen_to_chromecast.action.START
_CASTING"
        const val ACTION_STOP_CASTING = "home.screen_to_chromecast.action.STOP_C
ASTING"
        const val EXTRA_RESULT_CODE = "home.screen_to_chromecast.extra.RESULT_CO
DE"
        const val EXTRA_RESULT_DATA = "home.screen_to_chromecast.extra.RESULT_DA
TA"

        private const val NOTIFICATION_ID = 1237
        private const val NOTIFICATION_CHANNEL_ID = "ScreenCastingChannel"

        // Using lowered settings from previous diagnostic step
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 360
        private const val VIDEO_BITRATE = 500 * 1024
        private const val VIDEO_FRAME_RATE = 15

        // private const val IFRAME_INTERVAL_SECONDS = 2 // Unused
        // private const val CODEC_TIMEOUT_US = 10000L // Unused

        // HLS specific constants - no longer strictly needed for multi-segment
//HLS
        // but SEGMENT_DURATION_SECONDS is used by the timer logic (now removed
//for single file)
        // and MAX_SEGMENTS_IN_PLAYLIST is unused.
        // For single file, these are not relevant in the same way.
        // private const val MAX_SEGMENTS_IN_PLAYLIST = 5
        private const val SEGMENT_DURATION_SECONDS = 5 // This was for the timer
//, now removed.
    }
}
