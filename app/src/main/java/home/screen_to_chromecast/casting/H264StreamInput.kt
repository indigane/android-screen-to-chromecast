package home.screen_to_chromecast.casting

import android.util.Log
import org.videolan.libvlc.interfaces.IMedia // Correct import
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// Implement the correct interface IMedia.IMediaInput
class H264StreamInput(
    private val nalQueue: ArrayBlockingQueue<ByteArray>,
    private val isStreamingActiveProvider: () -> Boolean,
    private val getSpsPpsProvider: () -> ByteArray?
) : IMedia.IMediaInput {

    private var spsPpsSent = false

    companion object {
        private const val TAG = "H264StreamInput"
        private const val READ_TIMEOUT_MS = 200L
    }

    // open returns int: 0 on success, non-zero on error.
    override fun open(uri: String?): Int {
        Log.d(TAG, "IMediaInput open called. URI: $uri")
        spsPpsSent = false
        return 0 // Success
    }

    // read returns int: number of bytes read, 0 for EOS, < 0 for error.
    override fun read(buf: ByteArray, len: Int): Int {
        if (!isStreamingActiveProvider()) {
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
                        Log.e(TAG, "Buffer (len: $len) too small for SPS/PPS (size: ${spsPps.size}).")
                        return -1 // Error
                    }
                }
            }

            val nalUnit = nalQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (nalUnit != null) {
                if (nalUnit.isNotEmpty()) {
                    if (nalUnit.size <= len) {
                        System.arraycopy(nalUnit, 0, buf, 0, nalUnit.size)
                        bytesRead = nalUnit.size
                    } else {
                        Log.e(TAG, "NAL unit (size ${nalUnit.size}) too large for LibVLC buffer (size $len).")
                        // Re-queue the NAL unit if it's too large for the current buffer.
                        // This is risky as it might lead to an infinite loop if VLC always provides small buffers.
                        // A better strategy might be to drop it or signal error.
                        if (!nalQueue.offerFirst(nalUnit, 10, TimeUnit.MILLISECONDS)) { // Try to put back
                            Log.e(TAG, "Failed to re-queue oversized NAL unit. Dropping.")
                        }
                        return 0 // Indicate no data read this time, so VLC might try again with a new buffer
                    }
                } else {
                    // Log.w(TAG, "Polled an empty NAL unit.") // Can be noisy
                    return 0
                }
            } else {
                return 0 // No data available (timeout)
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "IMediaInput read interrupted.", e)
            Thread.currentThread().interrupt()
            return -1
        } catch (e: Exception) {
            Log.e(TAG, "Exception in IMediaInput read", e)
            return -1
        }
        return bytesRead
    }

    // seek returns int: 0 on success, non-zero on error.
    override fun seek(offset: Long): Int {
        Log.d(TAG, "IMediaInput seek called. Not supported.")
        return -1 // Not supported
    }

    // close is void
    override fun close() {
        Log.d(TAG, "IMediaInput close called.")
    }

    // getSize is long
    override fun getSize(): Long {
        return -1L // Indicate unknown size for a live stream (or Long.MAX_VALUE)
    }
}
