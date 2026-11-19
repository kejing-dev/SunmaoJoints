package io.embodit.sunmaojoints.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.sunmaojoints.domain.LevelGate
import io.embodit.sunmaojoints.ui.Containers
import kotlin.math.asin

/**
 * Opens / reopens the control window (native-window-move.md §3.5 and §7 item 9). Every open first asks the
 * [LevelGate]; the board's frame system feeds it the head pitch each frame. In Shared Space the head pose is not
 * available to the app (HMDTrackingProvider reports NOT_IN_FULL_SPACE), so the gate opens after its no-head fallback.
 * Design S13: when the system [×] closes the control window, the volume shows a badge; a tap on it reopens the window
 * beside the volume (Placement.right). Exit closes both containers before the tasks are removed (D44).
 */
class WindowCtl {
    val gate = LevelGate()
    /** Set by exitApp so the volume closing during exit does not start a second exit. */
    @Volatile var exiting = false
    var levelHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"

    private var navigator: SpatialNavigator? = null
    /** The control window's own navigator. */
    private var panelNavigator: SpatialNavigator? = null
    var volumeState by mutableStateOf("ON_CREATE")
        private set
    val volumeOpen: Boolean get() = volumeState != "ON_DESTROY"
    private var reopenPending = false
    private var lastStepMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private var hmd: HMDTrackingProvider? = null
    private var hmdTried = false

    /** The board screen hands over its navigator (the window's own composition can't reopen itself). */
    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach() {
        navigator = null
        gate.cancel()
        main.removeCallbacks(watchdog)
        runCatching { hmd?.stop() }
        hmd = null
    }

    /** Ask for the window; it opens once the head is level (or the fallback fires). */
    fun request(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    /** Close and reopen beside the volume (Placement.right). */
    fun reopen() {
        if (!panelOpen) request("reopen")
        else { reopenPending = true; Log.i(TAG, "WINDOW reopen close (beside the lock)"); runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }
    }

    /** Design container.lifecycle: the volume closed by the system [×] ends the whole app (see BoardScreen). */
    fun onVolumeLifecycle(event: String) {
        volumeState = event
        Log.i(TAG, "VOLUME lifecycle $event")
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (reopenPending && event == "ON_DESTROY") { reopenPending = false; request("reopen") }
    }

    /** Debug: the system [×] on the control window (S13: the badge appears in the volume). */
    fun closeControlOnly() { runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Exit (D26 / D44): close the control window and the board volume itself. */
    fun closeAll() {
        gate.cancel()
        main.removeCallbacks(watchdog)
        val nav = navigator ?: panelNavigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }

    /** Called every frame by the board's ECS system. */
    fun stepFrame() {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) return
        val pitch = headPitch()
        levelHint = gate.hintWanted(pitch)
        gate.step(now, pitch)?.let { why -> levelHint = false; openNow(why, pitch) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        Log.i(TAG, "WINDOW open why=$why pitch=$pitch")
        runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }

    /**
     * Head pitch in degrees (+ up), or null without a valid head pose (all zero / identity counts as invalid).
     * Only feeds the window level gate (D9); never used to aim, place or select (D1).
     */
    private fun headPitch(): Float? {
        if (!hmdTried) {
            hmdTried = true
            hmd = runCatching {
                HMDTrackingProvider().also { p ->
                    val r = p.start()
                    Log.i(TAG, "HMD start=$r support=${p.supportState} state=${p.state}")
                }
            }.onFailure { Log.w(TAG, "HMD unavailable: $it") }.getOrNull()
        }
        val provider = hmd ?: return null
        if (provider.state != DataProvider.State.STARTED) return null
        // gate:allow head-pose only the head pitch for the window level gate (D9), never aims / places / selects
        val pose = runCatching { provider.latestData?.hmdPose }.getOrNull() ?: return null
        val q = pose.rotation
        val p = pose.position
        if (p.x == 0f && p.y == 0f && p.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) return null
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        return Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
