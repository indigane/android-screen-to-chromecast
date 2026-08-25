package app.castmirror.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface
import app.castmirror.util.AppLog
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the [MediaProjection] and the [VirtualDisplay] that mirrors the screen onto the encoder's
 * input surface.
 *
 * The virtual display is created at exactly the negotiated encode resolution and stays that size
 * for the whole session. That is a deliberate simplification forced by the protocol: Cast's OFFER
 * fixes the video resolution at negotiation time and there is no way to renegotiate it without
 * tearing the session down, so the encoder's size cannot follow the device's orientation. Keeping
 * the virtual display the same size as the encoder makes the display server scale and letterbox
 * the screen into it, which is exactly what a rotation needs and costs nothing.
 *
 * The dimensions and flags reproduce what GMS's `ScreenCapturer` passes:
 * `createVirtualDisplay("Cast Receiver", w, h, min(w,h) * 320 / 1080, PUBLIC | PRESENTATION, ...)`.
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val handler: Handler,
) {

    /** Why capture ended without the app asking it to. */
    enum class StopReason {
        /** The user revoked the projection, or the system withdrew it. */
        PROJECTION_STOPPED,

        /** The virtual display itself was torn down. */
        DISPLAY_STOPPED,
    }

    /** Notified when the projection or the virtual display goes away for a reason we did not cause. */
    fun interface StoppedCallback {
        /** Capture ended for [reason]. */
        fun onCaptureStopped(reason: StopReason)
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var stoppedCallback: StoppedCallback? = null

    @Volatile
    private var released = false

    /**
     * Registers the projection callback and starts mirroring onto [surface].
     *
     * The callback must be registered *before* `createVirtualDisplay`: since Android 14 the
     * framework throws `IllegalStateException` from `createVirtualDisplay` when no callback is
     * registered, because it has no way to tell the app that the user revoked the projection.
     *
     * @param surface the encoder's input surface.
     * @param width encode width in pixels.
     * @param height encode height in pixels.
     * @param onStopped invoked when the user or the system ends the projection.
     * @throws IllegalStateException if the projection token has already been consumed or revoked.
     */
    fun start(surface: Surface, width: Int, height: Int, onStopped: StoppedCallback) {
        check(!released) { "ScreenCapture already released" }
        stoppedCallback = onStopped

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.i("capture: MediaProjection stopped by the system or the user")
                notifyStopped(StopReason.PROJECTION_STOPPED)
            }
        }
        projectionCallback = cb
        projection.registerCallback(cb, handler)

        val density = densityFor(width, height)
        val display = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            density,
            VIRTUAL_DISPLAY_FLAGS,
            surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    AppLog.i("capture: virtual display stopped")
                    notifyStopped(StopReason.DISPLAY_STOPPED)
                }
            },
            handler,
        ) ?: throw IllegalStateException(
            "createVirtualDisplay returned null; the MediaProjection token was rejected"
        )
        virtualDisplay = display
        AppLog.i("capture: mirroring ${width}x$height @ ${density}dpi into the encoder surface")
    }

    /**
     * Resizes the virtual display in place.
     *
     * Only useful to force the display server to re-render the whole surface — the size itself
     * must keep matching the encoder, which cannot be reconfigured mid-session.
     */
    fun resize(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        runCatching { display.resize(width, height, densityFor(width, height)) }
            .onFailure { AppLog.w("capture: resize failed", it) }
    }

    /** Releases the virtual display and stops the projection. Idempotent. */
    fun release() {
        if (released) return
        released = true
        stoppedCallback = null
        virtualDisplay?.let { display ->
            // Detaching the surface first stops the display server writing into a surface the
            // encoder is about to release.
            runCatching { display.surface = null }
            runCatching { display.release() }
        }
        virtualDisplay = null
        projectionCallback?.let { runCatching { projection.unregisterCallback(it) } }
        projectionCallback = null
        runCatching { projection.stop() }
    }

    private fun notifyStopped(reason: StopReason) {
        val cb = stoppedCallback ?: return
        stoppedCallback = null
        runCatching { cb.onCaptureStopped(reason) }
    }

    companion object {
        /**
         * The display name GMS uses. Visible in `dumpsys display`, and worth keeping identical so
         * a receiver or a ROM that special-cases it behaves the same for us.
         */
        const val VIRTUAL_DISPLAY_NAME = "Cast Receiver"

        /**
         * `VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_PRESENTATION`, the literal `3` GMS
         * passes. PUBLIC makes the display mirror the default display's content; PRESENTATION
         * lets an app target it with a `Presentation` if it wants to.
         */
        const val VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        /**
         * GMS's density rule: `min(width, height) * 320 / 1080`.
         *
         * The density decides how large UI elements look on the mirrored display. Deriving it from
         * the short edge keeps a 720p mirror legible instead of rendering phone-sized UI at
         * desktop density.
         */
        fun densityFor(width: Int, height: Int): Int =
            max(1, (min(width, height) * 320) / 1080)
    }
}
