package io.embodit.sunmaojoints.platform

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window

/**
 * D60 补 (gate back-key; copied from GlowBench v0.1.3, QA #3 q3-1): BACK and controller B are caught at the earliest
 * layer of every window of the app and all go to the one handler (Graph.onBack). An OnBackPressedCallback alone misses
 * them: after a tap on the native TabBar the key goes into the tab's window and never reaches the dispatcher. So:
 *  1. every Activity window: its Window.Callback is wrapped, dispatchKeyEvent sees BACK / BUTTON_B before any view;
 *  2. every other window of the process (TabBar, system-window pieces the SDK adds through WindowManager): its root
 *     view gets an unhandled-key listener, swept on each Activity event and on every tab tap;
 *  3. the control window's Compose root routes BACK / BUTTON_B in onPreviewKeyEvent (ControlWindow).
 * DOWN is consumed silently, UP fires the handler once; [BackKeys.fire] drops a second fire for the same
 * press when two layers see it (same event time).
 */
object BackKeys {
    var handler: (String) -> Unit = {}

    private var lastFiredDownTime = -1L

    fun isBack(code: Int) = code == KEYCODE_BACK || code == KEYCODE_BUTTON_B || code == KEYCODE_ESCAPE

    /** Returns true if the event was BACK / B (consumed). The handler runs on the UP of a press, once per press. */
    fun route(e: KeyEvent, where: String): Boolean =
        route(e.keyCode, e.action, e.downTime, e.isCanceled, where)

    /** Same as [route] with the raw fields (unit-tested without android.view.KeyEvent). */
    fun route(code: Int, action: Int, downTime: Long, canceled: Boolean, where: String): Boolean {
        if (!isBack(code)) return false
        if (action == ACTION_UP && !canceled) fire(downTime, "$where ${codeName(code)}")
        return true
    }

    private fun codeName(code: Int) = when (code) { KEYCODE_BACK -> "BACK"; KEYCODE_BUTTON_B -> "BUTTON_B"; else -> "ESCAPE" }

    // KeyEvent constants (stable since API 1 / 9), kept here so the JVM unit test needs no android stubs
    const val KEYCODE_BACK = 4
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_ESCAPE = 111
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    @Synchronized
    internal fun reset() { lastFiredDownTime = -1L }

    @Synchronized
    private fun fire(downTime: Long, where: String) {
        if (downTime == lastFiredDownTime) return
        lastFiredDownTime = downTime
        handler(where)
    }

    /** Layer 1: wrap the Activity window's callback (idempotent). */
    fun wrap(a: Activity) {
        val w = a.window ?: return
        val cb = w.callback ?: return
        if (cb is Wrapped) return
        w.callback = Wrapped(cb, a.javaClass.simpleName)
        Log.i(TAG, "BACK keys wrapped on ${a.javaClass.simpleName}")
    }

    private class Wrapped(private val d: Window.Callback, private val name: String) : Window.Callback by d {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = route(event, "key:$name") || d.dispatchKeyEvent(event)
    }

    private val hooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** Layer 2: every root view of this process (WindowManagerGlobal), so a focused TabBar window cannot swallow BACK. */
    fun sweep(reason: String) {
        val roots = runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val g = c.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val names = c.getMethod("getViewRootNames").invoke(g) as Array<String>
            names.mapNotNull { n -> runCatching { c.getMethod("getRootView", String::class.java).invoke(g, n) as? View }.getOrNull()?.let { n to it } }
        }.getOrElse { Log.w(TAG, "BACK sweep failed: $it"); emptyList() }
        for ((n, v) in roots) hook(v, n, reason)
    }

    private fun hook(v: View, name: String, reason: String) {
        if (!hooked.add(v)) return
        val short = name.substringAfterLast('/').take(48)
        // runs when no view in that window handled the key (a window without an Activity never reaches the dispatcher)
        v.addOnUnhandledKeyEventListener { _, e -> route(e, "unhandled:$short") }
        Log.i(TAG, "BACK keys hooked root $short ($reason)")
    }
}
