package home.screen_to_chromecast.casting

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class HLSServer(port: Int, private val hlsDirectory: File) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HLSServer"
        const val MIME_TYPE_M3U8 = "application/vnd.apple.mpegurl"
        const val MIME_TYPE_TS = "video/mp2t"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Received request for URI: $uri")

        if (uri == null) {
            Log.e(TAG, "URI is null")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: URI is null")
        }

        val fileName = uri.substring(1) // Remove leading slash

        return when {
            uri.endsWith(".m3u8") -> serveFile(fileName, MIME_TYPE_M3U8)
            uri.endsWith(".ts") -> serveFile(fileName, MIME_TYPE_TS)
            else -> {
                Log.w(TAG, "Unrecognized URI: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: $uri")
            }
        }
    }

    private fun serveFile(fileName: String, mimeType: String): Response {
        val file = File(hlsDirectory, fileName)
        Log.d(TAG, "Attempting to serve file: ${file.absolutePath} with MIME type: $mimeType")

        if (!file.exists()) {
            Log.e(TAG, "File not found: ${file.absolutePath}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: $fileName")
        }

        return try {
            val fis = FileInputStream(file)
            newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found exception (should have been caught by exists check): ${file.absolutePath}", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: $fileName (Exception)")
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: ${file.absolutePath}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error")
        }
    }
}
