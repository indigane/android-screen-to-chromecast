package home.screen_to_chromecast.casting

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession // Explicit import
import fi.iki.elonen.NanoHTTPD.Response     // Explicit import
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
                // Check for "live_stream.ts" first for clarity with single TS streaming
                requestedPath == "live_stream.ts" -> "video/mp2t"
                requestedPath.endsWith(".m3u8") -> "application/vnd.apple.mpegurl" // Keep for potential future use
                requestedPath.endsWith(".ts") -> "video/mp2t" // Fallback for other .ts files if any
                else -> {
                    Log.w(TAG, "Attempt to serve file with unrecognized extension: $requestedPath")
                    "application/octet-stream"
                }
            }

            val fis = FileInputStream(requestedFile)

            if (requestedPath == "live_stream.ts") {
                Log.d(TAG, "Serving live_stream.ts with newChunkedResponse.")
                newChunkedResponse(Response.Status.OK, mimeType, fis) // USE CHUNKED RESPONSE
            } else {
                Log.d(TAG, "Serving $requestedPath with newFixedLengthResponse.")
                newFixedLengthResponse(Response.Status.OK, mimeType, fis, requestedFile.length()) // Existing logic for other files
            }
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
