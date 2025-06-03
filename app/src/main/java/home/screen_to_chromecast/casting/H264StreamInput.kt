package home.screen_to_chromecast.casting

import android.util.Log
import org.videolan.libvlc.interfaces.IMedia
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class H264StreamInput(
    private val nalQueue: ArrayBlockingQueue<ByteArray>,
    private val isStreamingActiveProvider: () -> Boolean, // Changed to a provider
    private val getSpsPpsProvider: () -> ByteArray? // Changed to a provider
) : IMedia.Input {

    private var spsPpsSent = false
    // private var streamOffset = 0L // Not strictly needed for LibVLC for live stream

    companion object {
        private const val TAG = "H264StreamInput"
        private const val READ_TIMEOUT_MS = 200L // Timeout for polling NAL units
    }

    override fun open(uri: String?): Boolean {
        Log.d(TAG, "IMedia.Input open called. URI: $uri")
        spsPpsSent = false
        // streamOffset = 0L
        return true
    }

    override fun read(buf: ByteArray, len: Int): Int {
        if (!isStreamingActiveProvider()) {
            Log.d(TAG, "Streaming not active, returning 0 (EOS) to read().")
            return 0 // EOS
        }

        var bytesRead = 0
        try {
            if (!spsPpsSent) {
                val spsPps = getSpsPpsProvider()
                if (spsPps != null && spsPps.isNotEmpty()) {
                    if (spsPps.size <= len) {
                        System.arraycopy(spsPps, 0, buf, 0, spsPps.size)
                        bytesRead = spsPps.size
                        spsPpsSent = true
                        Log.i(TAG, "Sent SPS/PPS data (${bytesRead} bytes) to LibVLC.")
                        // streamOffset += bytesRead
                        return bytesRead
                    } else {
                        Log.e(TAG, "Buffer (len: $len) too small for SPS/PPS (size: ${spsPps.size}). This is critical.")
                        // This is a fatal error for the stream if SPS/PPS cannot be sent.
                        // LibVLC might not be able to decode without it.
                        return -1 // Indicate error
                    }
                } else {
                     Log.d(TAG, "SPS/PPS not available yet, will try to send NAL unit.")
                }
            }

            // If SPS/PPS was sent, or not available yet, try to send a NAL unit
            val nalUnit = nalQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (nalUnit != null) {
                if (nalUnit.isNotEmpty()) { // Ensure NAL unit is not empty
                    if (nalUnit.size <= len) {
                        System.arraycopy(nalUnit, 0, buf, 0, nalUnit.size)
                        bytesRead = nalUnit.size
                        // streamOffset += bytesRead
                    } else {
                        Log.e(TAG, "NAL unit (size ${nalUnit.size}) too large for LibVLC buffer (size $len). Dropping NAL. This is problematic.")
                        // This means LibVLC is asking for chunks smaller than our NAL units.
                        // This could lead to corrupted stream.
                        // Returning 0 might make VLC retry, returning -1 signals error.
                        // For now, we "drop" it by returning 0, hoping next read has larger buffer or NAL is smaller.
                        return 0; // Or -1 to signal a more fatal error.
                    }
                } else {
                    Log.w(TAG, "Polled an empty NAL unit. Ignoring.")
                    return 0; // Treat as no data available for this read
                }
            } else {
                // Timeout polling, means queue is empty for now
                // Log.v(TAG, "NAL queue poll timed out or empty.") // Verbose
                return 0 // No data available at this moment
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "IMedia.Input read interrupted.", e)
            Thread.currentThread().interrupt()
            return -1
        } catch (e: Exception) {
            Log.e(TAG, "Exception in IMedia.Input read", e)
            return -1
        }
        // Log.v(TAG, "IMedia.Input read returning ${bytesRead} bytes.") // Verbose
        return bytesRead
    }

    override fun seek(offset: Long): Boolean {
        Log.d(TAG, "IMedia.Input seek called. Not supported.")
        return false
    }

    override fun close() {
        Log.d(TAG, "IMedia.Input close called.")
    }

    override fun getSize(): Long {
        return Long.MAX_VALUE // Indicate live/infinite stream
    }
}
