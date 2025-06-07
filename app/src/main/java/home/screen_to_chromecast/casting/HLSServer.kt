package home.screen_to_chromecast.casting

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class HLSServer(port: Int, private val hlsFilesDir: File) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "HLSServer received request for URI: $uri")

        // Remove leading '/' if present, handle empty or null URI
        val requestedPath = uri?.takeIf { it.isNotEmpty() }?.removePrefix("/") ?: ""
        if (requestedPath.isEmpty()) {
            Log.e(TAG, "Requested URI is empty or null after processing.")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: Invalid URI.")
        }

        val requestedFile = File(hlsFilesDir, requestedPath)

        if (!requestedFile.exists() || !requestedFile.isFile || !requestedFile.startsWith(hlsFilesDir)) {
            // Security check: Ensure the requested file is within the hlsFilesDir
            Log.e(TAG, "File not found, not a file, or outside designated directory: ${requestedFile.absolutePath}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
        }

        return try {
            val mimeType = when {
                requestedPath.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                requestedPath.endsWith(".ts") -> "video/mp2t"
                else -> {
                    Log.w(TAG, "Attempt to serve file with unrecognized extension: $requestedPath")
                    "application/octet-stream" // Fallback, though ideally only .m3u8 and .ts are served
                }
            }
            val fis = FileInputStream(requestedFile)
            // Use newChunkedResponse for potentially large .ts files if issues arise with newFixedLengthResponse
            // For now, newFixedLengthResponse should be fine as segments are small.
            newFixedLengthResponse(Response.Status.OK, mimeType, fis, requestedFile.length())
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "FileNotFoundException for: ${requestedFile.absolutePath}", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: ${requestedFile.absolutePath}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error.")
        }
    }

    companion object {
        private const val TAG = "HLSServer"
    }
}
