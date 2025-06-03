package home.screen_to_chromecast.casting

import android.util.Log
import org.videolan.libvlc.interfaces.IMedia // Correct import for IMedia
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class H264StreamInput(
    private val nalQueue: ArrayBlockingQueue<ByteArray>,
    private val isStreamingActiveProvider: () -> Boolean,
    private val getSpsPpsProvider: () -> ByteArray?
) : IMedia.Input { // Implement the correct interface

    private var spsPpsSent = false

    companion object {
        private const val TAG = "H264StreamInput"
        private const val READ_TIMEOUT_MS = 200L
    }

    // Correct override signature
    override fun open(uri: String?): Int { // open returns int (0 for success, non-zero for error)
        Log.d(TAG, "IMedia.Input open called. URI: $uri")
        spsPpsSent = false
        return 0 // Success
    }

    // Correct override signature
    override fun read(buf: ByteArray, len: Int): Int {
        if (!isStreamingActiveProvider()) {
            // Log.d(TAG, "Streaming not active, returning 0 (EOS) to read().") // Can be noisy
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
                        return bytesRead
                    } else {
                        Log.e(TAG, "Buffer (len: $len) too small for SPS/PPS (size: ${spsPps.size}). This is critical.")
                        return -1 // Indicate error
                    }
                } else {
                    // Log.d(TAG, "SPS/PPS not available yet, will try to send NAL unit.") // Can be noisy
                }
            }

            val nalUnit = nalQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (nalUnit != null) {
                if (nalUnit.isNotEmpty()) {
                    if (nalUnit.size <= len) {
                        System.arraycopy(nalUnit, 0, buf, 0, nalUnit.size)
                        bytesRead = nalUnit.size
                    } else {
                        Log.e(TAG, "NAL unit (size ${nalUnit.size}) too large for LibVLC buffer (size $len). Dropping NAL.")
                        return 0 // Problematic, but returning 0 might allow VLC to request again
                    }
                } else {
                    Log.w(TAG, "Polled an empty NAL unit. Ignoring.")
                    return 0
                }
            } else {
                return 0 // No data available at this moment (timeout)
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "IMedia.Input read interrupted.", e)
            Thread.currentThread().interrupt()
            return -1
        } catch (e: Exception) {
            Log.e(TAG, "Exception in IMedia.Input read", e)
            return -1
        }
        return bytesRead
    }

    // Correct override signature
    override fun seek(offset: Long): Int { // seek returns int
        Log.d(TAG, "IMedia.Input seek called. Not supported.")
        return -1 // Indicate error or not supported
    }

    // Correct override signature
    override fun close() {
        Log.d(TAG, "IMedia.Input close called.")
    }

    // Correct override signature
    override fun getSize(): Long {
        return Long.MAX_VALUE
    }
}
