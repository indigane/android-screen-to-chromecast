package home.screen_to_chromecast.casting

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

class HLSServer(port: Int, private val hlsFilesDir: File) : NanoHTTPD(port) {

    init {
        Log.i(TAG, "HLSServer instance created. Requested Port: $port, Root Dir: ${hlsFilesDir.absolutePath}")
    }

    @Throws(IOException::class)
    override fun start() {
        try {
            // Explicitly call the version that takes timeout and daemon flag
            super.start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HLSServer super.start(SOCKET_READ_TIMEOUT, false) completed. Now listening on port $listeningPort.")
        } catch (e: IOException) {
            Log.e(TAG, "HLSServer super.start() FAILED!", e)
            throw e
        }
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "HLSServer super.stop() completed.")
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "serve() called. URI: ${session.uri}, Method: ${session.method}, Remote IP: ${session.remoteIpAddress}, Headers: ${session.headers}")
        val uri = session.uri

        val requestedPath = uri?.takeIf { it.isNotEmpty() }?.removePrefix("/") ?: ""
        if (requestedPath.isEmpty()) {
            Log.e(TAG, "Requested URI is empty or null after processing. URI: ${session.uri}")
            val response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: Invalid URI.")
            Log.d(TAG, "Returning 404 response for URI ${session.uri}. Status: ${response.status}, MIME: ${response.mimeType}")
            return response
        }

        val requestedFile = File(hlsFilesDir, requestedPath)
        Log.d(TAG, "Requested file path: ${requestedFile.absolutePath}")

        if (!requestedFile.exists() || !requestedFile.isFile || !requestedFile.startsWith(hlsFilesDir)) {
            Log.e(TAG, "File not found, not a file, or outside designated directory: ${requestedFile.absolutePath} for URI: ${session.uri}")
            val response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: ${session.uri}")
            Log.d(TAG, "Returning 404 response for URI ${session.uri}. Status: ${response.status}, MIME: ${response.mimeType}")
            return response
        }

        return try {
            val mimeType = when {
                requestedPath.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                requestedPath.endsWith(".ts") -> "video/mp2t"
                else -> {
                    Log.w(TAG, "Attempt to serve file with unrecognized extension: $requestedPath")
                    "application/octet-stream"
                }
            }
            Log.d(TAG, "Attempting to open FileInputStream for: ${requestedFile.name}")
            val fis = FileInputStream(requestedFile)
            // Using newFixedLengthResponse as segments are small and this is simpler.
            // If issues with large files/streams, newChunkedResponse might be better.
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, requestedFile.length())
            Log.d(TAG, "Returning OK for ${requestedFile.name} with MIME: $mimeType. Status: ${response.status}")
            response
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "FileNotFoundException for ${requestedFile.absolutePath} (URI: ${session.uri})", e)
            val response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "FileNotFoundException: ${session.uri}")
            Log.d(TAG, "Returning 404 (FileNotFoundException) for URI ${session.uri}. Status: ${response.status}, MIME: ${response.mimeType}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: ${requestedFile.absolutePath} (URI: ${session.uri})", e)
            val response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error: ${e.message}")
            Log.d(TAG, "Returning 500 Internal Error for URI ${session.uri}. Status: ${response.status}, MIME: ${response.mimeType}")
            response
        }
    }

    companion object {
        private const val TAG = "HLSServer"
    }
}
