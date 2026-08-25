package app.castmirror.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.castmirror.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * A GLES2 compositor that sits between the mirrored display and the video encoder.
 *
 * ### Why it exists
 *
 * Cast negotiates the video resolution once, in the OFFER, and offers no way to change it
 * mid-session. The encoder's input surface and its frame size are therefore **fixed for the whole
 * session** — chosen landscape, `encodeWidth >= encodeHeight`. But the phone rotates, and its
 * captured content changes aspect ratio when it does. Feeding a portrait screen straight into a
 * fixed landscape encoder either stretches it or, if the virtual display is left at the encoder's
 * size, ends up as a tiny rotated image in a sea of black.
 *
 * This class breaks that coupling. The [MediaProjection][android.media.projection.MediaProjection]
 * renders into [inputSurface] — a [SurfaceTexture] sized to the phone's *current* real resolution,
 * so the capture is always the natural shape of the screen. Every captured frame is then drawn as
 * an aspect-preserved, centred quad into the fixed landscape encoder surface, with black bars
 * filling the rest. A portrait phone appears centred with pillar bars; a landscape phone fills the
 * frame; rotating live re-letterboxes smoothly, with no distortion and no tiny-screen-in-void.
 *
 * ### How it works
 *
 * All EGL and GL calls happen on one dedicated thread ([glThread]); GL contexts are not
 * thread-safe and this keeps every touch of the context on the same thread. The constructor blocks
 * until that thread has built the context and the [SurfaceTexture], so [inputSurface] is valid the
 * moment it returns.
 *
 * - EGL is initialised with the encoder surface as the window surface, using a recordable config
 *   so `eglSwapBuffers` queues each composited frame straight to the encoder's input.
 * - A single external-OES texture receives the captured screen; its [SurfaceTexture] fires
 *   [onFrameAvailable][SurfaceTexture.OnFrameAvailableListener] on the GL thread, which draws one
 *   frame.
 * - The [SurfaceTexture]'s transform matrix is applied to the texture coordinates (it carries the
 *   vertical flip and any crop), and a separately computed scale is applied to the vertex
 *   positions to fit the source rectangle into the encode frame.
 * - `eglPresentationTimeANDROID` stamps each frame with the [SurfaceTexture]'s timestamp, which is
 *   `CLOCK_MONOTONIC` — the same base the encoder's `presentationTimeUs` and the RTP timestamp
 *   derivation assume, so lip-sync accounting is unaffected by the extra hop.
 *
 * ### Rotation
 *
 * [setSourceSize] updates the [SurfaceTexture]'s default buffer size and recomputes the letterbox
 * fit. The caller pairs it with `VirtualDisplay.resize` to the same dimensions and a key-frame
 * request, so the whole picture re-encodes cleanly at the new aspect.
 *
 * **Device-only verifiable.** There is no GL context in the build sandbox, so this class compiles
 * there but cannot run there; its behaviour can only be confirmed on a real device.
 *
 * @param outputSurface the encoder's input surface. Owned by the caller; not released here.
 * @param encodeWidth the fixed encode width in pixels.
 * @param encodeHeight the fixed encode height in pixels.
 * @param initialSourceWidth the phone's current display width in pixels.
 * @param initialSourceHeight the phone's current display height in pixels.
 */
class LetterboxCompositor(
    private val outputSurface: Surface,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    initialSourceWidth: Int,
    initialSourceHeight: Int,
) {

    private val glThread = HandlerThread("castmirror-gl").apply { start() }
    private val handler = Handler(glThread.looper)

    @Volatile
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uStMatrixLoc = 0
    private var uScaleLoc = 0
    private var uTextureLoc = 0

    private val stMatrix = FloatArray(16)
    private var scaleX = 1f
    private var scaleY = 1f

    @Volatile
    private var sourceWidth = initialSourceWidth.coerceAtLeast(2)

    @Volatile
    private var sourceHeight = initialSourceHeight.coerceAtLeast(2)

    @Volatile
    private var released = false

    private lateinit var surfaceTexture: SurfaceTexture

    /**
     * The surface the virtual display must render the screen into.
     *
     * Sized to the phone's real resolution, not the encoder's, so the captured content keeps its
     * natural aspect ratio before letterboxing.
     */
    lateinit var inputSurface: Surface
        private set

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTICES); position(0) }

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(textureId).also {
                    it.setDefaultBufferSize(sourceWidth, sourceHeight)
                    it.setOnFrameAvailableListener({ drawAvailableFrame() }, handler)
                }
                inputSurface = Surface(surfaceTexture)
                recomputeScale()
            } catch (e: Throwable) {
                failure = e
            } finally {
                ready.countDown()
            }
        }
        ready.await()
        failure?.let {
            release()
            throw IllegalStateException("could not initialise the GL compositor", it)
        }
        AppLog.i(
            "compositor: ${sourceWidth}x$sourceHeight -> letterbox into ${encodeWidth}x$encodeHeight"
        )
    }

    /**
     * Updates the captured source resolution after a rotation.
     *
     * Runs on the GL thread. The [SurfaceTexture]'s buffer is resized so the next captured frame
     * arrives at the new aspect ratio, and the letterbox fit is recomputed for it.
     */
    fun setSourceSize(width: Int, height: Int) {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        if (w == sourceWidth && h == sourceHeight) return
        sourceWidth = w
        sourceHeight = h
        if (released) return
        handler.post {
            if (released) return@post
            runCatching {
                surfaceTexture.setDefaultBufferSize(w, h)
                recomputeScale()
            }.onFailure { AppLog.w("compositor: source resize failed", it) }
        }
        AppLog.i("compositor: source resized to ${w}x$h")
    }

    /** Releases the GL context, the texture, the surface and the thread. Idempotent. */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
            runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            if (textureId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            releaseEgl()
            done.countDown()
        }
        runCatching { done.await() }
        glThread.quitSafely()
    }

    // ---- GL thread work ---------------------------------------------------------------------

    private fun drawAvailableFrame() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, encodeWidth, encodeHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)
            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)

            GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Throwable) {
            if (!released) AppLog.w("compositor: draw failed", e)
        }
    }

    /**
     * Recomputes the vertex scale that fits the source rectangle into the encode frame.
     *
     * The quad spans the whole clip volume; multiplying its x/y by these factors shrinks it to the
     * largest centred rectangle with the source's aspect ratio. Whichever axis is not scaled to 1
     * leaves black bars on the two opposite edges.
     */
    private fun recomputeScale() {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val encodeAspect = encodeWidth.toFloat() / encodeHeight.toFloat()
        if (sourceAspect > encodeAspect) {
            // Source is wider: fill width, bars top and bottom.
            scaleX = 1f
            scaleY = encodeAspect / sourceAspect
        } else {
            // Source is taller (a portrait phone in a landscape frame): fill height, bars sides.
            scaleX = sourceAspect / encodeAspect
            scaleY = 1f
        }
    }

    // ---- EGL / GL setup ---------------------------------------------------------------------

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig found no recordable RGBA8888 config" }
        val config = configs[0] ?: error("null EGL config")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed on the encoder surface" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        Matrix.setIdentityM(stMatrix, 0)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            "program link failed: $log"
        }
        // The shaders are no longer needed once linked into the program.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "shader compile failed: $log"
        }
        return shader
    }

    private companion object {
        /** EGL config attribute that marks a surface as safe for MediaCodec input. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Bytes between consecutive vertices: 4 floats (x, y, u, v). */
        const val STRIDE = 4 * 4

        /**
         * Full clip-volume quad as a triangle strip: `(x, y, u, v)` per vertex.
         *
         * Positions span `[-1, 1]`; the vertex shader multiplies them by the fit scale. Texture
         * coordinates span `[0, 1]` and are transformed by the SurfaceTexture matrix.
         */
        val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
                vTexCoord = (uStMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }
}
