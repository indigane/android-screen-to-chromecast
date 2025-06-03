package home.screen_to_chromecast.casting

import android.util.Log
// Assuming IMediaInput is a top-level interface in this package for LibVLC 3.6.2
import org.videolan.libvlc.interfaces.IMediaInput
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class H264StreamInput(
    private val nalQueue: ArrayBlockingQueue<ByteArray>,
    private val isStreamingActiveProvider: () -> Boolean,
    private val getSpsPpsProvider: () -> ByteArray?
) : IMediaInput { // Implementing the assumed top-level interface

    private var spsPpsSent = false

    companion object {
        private const val TAG = "H264StreamInput"
        private const val READ_TIMEOUT_MS = 200L
    }

    override fun open(uri: String?): Int {
        Log.d(TAG, "IMediaInput open called. URI: $uri")
        spsPpsSent = false
        return 0 // Success
    }

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
                        Log.e(TAG, "NAL unit (size ${nalUnit.size}) too large for LibVLC buffer (size $len). Dropping NAL.")
                        if (!nalQueue.offer(nalUnit, 10, TimeUnit.MILLISECONDS)) {
                             Log.e(TAG, "Failed to re-queue oversized NAL unit (queue full). Dropping.")
                        }
                        return 0
                    }
                } else {
                    return 0
                }
            } else {
                return 0
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

    override fun seek(offset: Long): Int {
        Log.d(TAG, "IMediaInput seek called. Not supported.")
        return -1 // Not supported
    }

    override fun close() {
        Log.d(TAG, "IMediaInput close called.")
    }

    override fun getSize(): Long {
        return -1L // Unknown size for live stream
    }
}
