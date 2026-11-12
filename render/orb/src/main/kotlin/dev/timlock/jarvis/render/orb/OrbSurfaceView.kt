package dev.timlock.jarvis.render.orb

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.Choreographer
import android.view.Surface
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbStateHolder

/**
 * GLES 3.0 orb surface. RENDERMODE_WHEN_DIRTY, driven by a Choreographer callback that skips vsyncs to hit the
 * tier's fps (60/30/20). Doubles as the window background (vignette is drawn in GL).
 */
class OrbSurfaceView(
    context: Context,
    stateHolder: OrbStateHolder,
    audioBus: AudioVisualBus,
) : GLSurfaceView(context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val renderer = OrbRenderer(stateHolder, audioBus, ::thermalHeadroom)

    var ambientStyleOverride: Boolean?
        get() = renderer.ambientStyleOverride
        set(v) { renderer.ambientStyleOverride = v }

    var qualityOverride: Tier?
        get() = renderer.qualityOverride
        set(v) { renderer.qualityOverride = v }

    @Volatile private var running = false
    // Cadence runs on its own looper (review H3): Choreographer is per-Looper, requestRender() is thread-safe.
    private var cadenceThread: HandlerThread? = null
    private var cadenceHandler: Handler? = null
    private var choreo: Choreographer? = null
    private var lastRenderNs = 0L   // cadence thread only
    private var appliedFps = 0      // cadence thread only
    private var thermalListener: Any? = null
    private val vsyncNs: Long

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val fps = renderer.targetFps
            val interval = 1_000_000_000L / fps
            if (lastRenderNs == 0L || frameTimeNanos - lastRenderNs >= interval - vsyncNs / 2) {
                lastRenderNs = frameTimeNanos
                requestRender()
            }
            if (fps != appliedFps) applyFrameRate(fps)
            choreo?.postFrameCallback(this)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        val hz = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.refreshRate
        vsyncNs = (1e9f / (if (hz > 20f) hz else 60f)).toLong()
    }

    private fun thermalHeadroom(): Float =
        if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(10) else 0f

    private fun applyFrameRate(fps: Int) {
        appliedFps = fps
        if (Build.VERSION.SDK_INT >= 30 && holder.surface.isValid) {
            holder.surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    /** Lifecycle hook: call from Activity.onPause. */
    fun pauseOrb() {
        stopCadence()
        onPause()
    }

    /** Lifecycle hook: call from Activity.onResume. */
    fun resumeOrb() {
        onResume()
        startCadence()
    }

    private fun startCadence() {
        if (running) return
        running = true
        var h = cadenceHandler
        if (h == null) {
            val t = HandlerThread("orb-cadence").also { it.start() }
            cadenceThread = t
            h = Handler(t.looper)
            cadenceHandler = h
        }
        h.post {
            val c = Choreographer.getInstance() // this looper's Choreographer
            choreo = c
            lastRenderNs = 0L
            appliedFps = 0
            c.removeFrameCallback(frameCb)
            c.postFrameCallback(frameCb)
        }
    }

    private fun stopCadence() {
        running = false
        cadenceHandler?.post { choreo?.removeFrameCallback(frameCb) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCadence()
        if (Build.VERSION.SDK_INT >= 29) {
            val l = PowerManager.OnThermalStatusChangedListener { renderer.governor.thermalStatus = it }
            power.addThermalStatusListener(context.mainExecutor, l)
            thermalListener = l
            renderer.governor.thermalStatus = power.currentThermalStatus
        }
    }

    override fun onDetachedFromWindow() {
        stopCadence()
        cadenceThread?.quitSafely()
        cadenceThread = null
        cadenceHandler = null
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { power.removeThermalStatusListener(it) }
        }
        thermalListener = null
        super.onDetachedFromWindow()
    }
}
