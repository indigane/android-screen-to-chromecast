package home.screen_to_chromecast.casting

import android.util.Log
import org.videolan.libvlc.interfaces.IMedia
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Custom IMedia.Input for LibVLC to stream H.264 NAL units from a queue.
 */
class H264StreamInput(
    private val nalQueue: ArrayBlockingQueue<ByteArray>,
    private val isStreamingActive: () -> Boolean,
    private val getSpsPps: () -> ByteArray? // Function to get SPS/PPS data
) : IMedia.Input {

    private var spsPpsSent = false
    private var streamOffset = 0L

    companion object {
        private const val TAG = "H264StreamInput"
    }

    override fun open(uri: String?): Boolean {
        Log.d(TAG, "IMedia.Input open called. URI: $uri")
        spsPpsSent = false // Reset for new stream
        streamOffset = 0L
        // Initialization, if any, can go here.
        // The queue is managed externally.
        return true // Indicate success
    }

    override fun read(buf: ByteArray, len: Int): Int {
        if (!isStreamingActive()) {
            Log.d(TAG, "Streaming not active, returning 0 (EOS) to read().")
            return 0 // End of stream if casting stopped
        }

        var bytesRead = 0
        try {
            // Send SPS/PPS first if not already sent
            if (!spsPpsSent) {
                val spsPps = getSpsPps()
                if (spsPps != null && spsPps.isNotEmpty()) {
                    if (spsPps.size <= len) {
                        System.arraycopy(spsPps, 0, buf, 0, spsPps.size)
                        bytesRead = spsPps.size
                        spsPpsSent = true
                        Log.d(TAG, "Sent SPS/PPS data (${bytesRead} bytes) to LibVLC.")
                        streamOffset += bytesRead
                        return bytesRead
                    } else {
                        Log.e(TAG, "Buffer too small for SPS/PPS. Required: ${spsPps.size}, Available: $len")
                        // This is a problem, LibVLC buffer might be too small for initial config.
                        // Consider how to handle this. For now, proceed without sending if too large.
                        // Or, fragment it if LibVLC supports that for config (unlikely for elementary stream config).
                        spsPpsSent = true; // Mark as sent to avoid loop, but it failed.
                    }
                } else {
                    // SPS/PPS not available yet, try to get a NAL unit
                    Log.d(TAG, "SPS/PPS not available yet, waiting for NAL unit.")
                }
            }


            // Try to get a NAL unit from the queue
            val nalUnit = nalQueue.poll(100, TimeUnit.MILLISECONDS) // Poll with timeout
            if (nalUnit != null) {
                if (nalUnit.size <= len - bytesRead) { // Check if remaining buffer can hold NAL unit
                    System.arraycopy(nalUnit, 0, buf, bytesRead, nalUnit.size)
                    bytesRead += nalUnit.size
                } else {
                    Log.w(TAG, "NAL unit (size ${nalUnit.size}) too large for remaining buffer (size ${len - bytesRead}). Re-queuing.")
                    nalQueue.offerFirst(nalUnit) // Put it back at the head of the queue
                    // If bytesRead is 0 here, it means the buffer was too small even for this single NAL.
                    // This could happen if LibVLC requests very small chunks.
                    if (bytesRead == 0 && nalUnit.size > len) {
                        Log.e(TAG, "LibVLC read buffer (len: $len) is smaller than NAL unit (size: ${nalUnit.size}). This is problematic.")
                        // This indicates a fundamental issue with buffer sizes.
                        // We might need to tell LibVLC to use larger buffers if possible,
                        // or the stream won't play correctly.
                        return -1; // Indicate an error
                    }
                }
            } else if (bytesRead == 0 && !isStreamingActive()) {
                // Queue is empty and streaming is no longer active
                Log.d(TAG, "Queue empty and streaming stopped during read, returning 0 (EOS).")
                return 0;
            }
            // If nalUnit is null but streaming is active, poll will timeout and loop, or return current bytesRead (if any from SPS/PPS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "IMedia.Input read interrupted.", e)
            Thread.currentThread().interrupt() // Preserve interrupt status
            return -1 // Indicate error
        } catch (e: Exception) {
            Log.e(TAG, "Exception in IMedia.Input read", e)
            return -1 // Indicate error
        }

        if (bytesRead > 0) {
            streamOffset += bytesRead
        }
        // Log.d(TAG, "IMedia.Input read returning ${bytesRead} bytes.")
        return bytesRead
    }


    override fun seek(offset: Long): Boolean {
        Log.d(TAG, "IMedia.Input seek called with offset: $offset. Not supported for live stream.")
        return false // Seeking not supported for a live H.264 stream
    }

    override fun close() {
        Log.d(TAG, "IMedia.Input close called.")
        // Cleanup, if any, related to this input stream.
        // The NAL queue is managed externally.
    }

    override fun getSize(): Long {
        // For a live stream, the size is unknown or infinite.
        // Returning Long.MAX_VALUE can indicate this.
        return Long.MAX_VALUE
    }
}
