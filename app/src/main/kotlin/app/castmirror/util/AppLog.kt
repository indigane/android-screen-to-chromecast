package app.castmirror.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A process-wide ring buffer of human-readable log lines, mirrored to logcat.
 *
 * The service, the discovery layer and the encoder all run on background threads and all need to
 * surface progress to a UI that may not exist yet, so the buffer is the single rendezvous point:
 * writers append from any thread, and [MainActivity][app.castmirror.ui.MainActivity] replays
 * [snapshot] on resume before subscribing to [Listener].
 *
 * Keeping this an object rather than a service-owned member is deliberate — a line logged during
 * teardown must survive the service that logged it, otherwise the reason a session died is gone
 * by the time the user looks.
 */
object AppLog {

    /** Receives lines as they are appended. Callbacks arrive on the appending thread. */
    fun interface Listener {
        /** A new [line] was appended. */
        fun onLogLine(line: String)
    }

    private const val TAG = "CastMirror"
    private const val CAPACITY = 600

    private val lines = ArrayDeque<String>(CAPACITY)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Appends [message], tagged with a wall-clock timestamp. */
    fun i(message: String) = append(message, null)

    /** Appends [message] and, when present, a one-line rendering of [error]. */
    fun w(message: String, error: Throwable? = null) = append(message, error)

    private fun append(message: String, error: Throwable?) {
        val text = if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}"
        val line = "${clock.format(Date())}  $text"
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        if (error == null) Log.i(TAG, text) else Log.w(TAG, text, error)
        listeners.forEach { runCatching { it.onLogLine(line) } }
    }

    /** Every buffered line, oldest first. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** Empties the buffer. Listeners are not notified; re-read [snapshot] to redraw. */
    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    /** Subscribes [l] to subsequent lines. */
    fun addListener(l: Listener) { listeners.add(l) }

    /** Unsubscribes [l]. */
    fun removeListener(l: Listener) { listeners.remove(l) }
}
