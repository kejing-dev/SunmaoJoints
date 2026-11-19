package io.embodit.sunmaojoints.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.Lang
import io.embodit.sunmaojoints.domain.Phase
import io.embodit.sunmaojoints.domain.Progress
import io.embodit.sunmaojoints.domain.T
import io.embodit.sunmaojoints.ui.Page
import io.embodit.sunmaojoints.ui.Ui
import io.embodit.sunmaojoints.ui.board.BoardListener
import io.embodit.sunmaojoints.ui.board.BoardTargets
import io.embodit.sunmaojoints.ui.board.SceneHolder
import io.embodit.sunmaojoints.ui.board.input.InputSource
import io.embodit.sunmaojoints.ui.board.input.resolveUp
import io.embodit.sunmaojoints.ui.board.routeFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only (design verification.debugHooks). Every command goes through the same Flow calls the volume and
 * the window use (D29); taps go through routeFlow with the holder names a real hit resolves to.
 *
 *   adb shell am broadcast -a io.embodit.sunmaojoints.DEBUG -p io.embodit.sunmaojoints --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto "<lesson 1-8> <S01|S02|S03|S04 [step]|S09|S10|S11|S12|S13>"   shot "<S05|S06|S07|S08> <lesson> <step> <cand>"
 *   tap <i>   turn   hold off   lang zh|en|auto   sound on|off   hints on|off   closecontrol   reopen   corruptsave
 *   doneall   reset   page joints|settings|howto   replay "<holder> [hand-ray|eye-hand|controller]"   hover <holder>|off
 *   evidence   dump
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim().lowercase()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun seq(block: suspend () -> Unit) { graph.scope.launch { block() } }
        suspend fun settle(ms: Long = 1400) = delay(ms)
        fun lessonArg(i: Int) = (f.getOrNull(i)?.toIntOrNull() ?: (flow.state.value.lesson + 1)).coerceIn(1, 8) - 1
        suspend fun toStep(li: Int, k: Int) {
            flow.freeze(null); Ui.page = Page.JOINTS; Ui.exitAsk = false
            flow.gotoLesson(li); flow.open(); flow.build()
            settle(600)
            for (s in 0 until k) { routeFlow(graph, "arrow:${Joints.ALL[li].steps[s].fitIndex}"); settle() }
        }
        when (cmd) {
            "goto" -> {
                val li = (f.getOrNull(0)?.toIntOrNull() ?: 1).coerceIn(1, 8) - 1
                when (f.getOrNull(1)?.uppercase() ?: "S02") {
                    "S01" -> seq { flow.freeze(null); flow.updateProgress { it.copy(firstRunDone = false) }; Ui.page = Page.JOINTS; flow.gotoLesson(0); flow.open(); flow.build() }
                    "S02" -> { flow.freeze(null); Ui.page = Page.JOINTS; flow.gotoLesson(li) }
                    "S03" -> seq { flow.freeze(null); Ui.page = Page.JOINTS; flow.gotoLesson(li); flow.open() }
                    "S04" -> seq { toStep(li, ((f.getOrNull(2)?.toIntOrNull() ?: 1) - 1).coerceIn(0, Joints.ALL[li].steps.lastIndex)) }
                    "S09" -> seq { toStep(li, Joints.ALL[li].steps.lastIndex); routeFlow(graph, "arrow:${Joints.ALL[li].steps.last().fitIndex}"); settle(1200 + T.TUG + T.XRAY + T.SPIN) }
                    "S10" -> seq {
                        flow.updateProgress { it.copy(done = (0..7).toSet() - li) }
                        toStep(li, Joints.ALL[li].steps.lastIndex); routeFlow(graph, "arrow:${Joints.ALL[li].steps.last().fitIndex}")
                    }
                    "S11" -> { Ui.page = Page.SETTINGS; Ui.exitAsk = false; Ui.howTo = false }
                    "S12" -> { Ui.page = Page.SETTINGS; Ui.howTo = false; Ui.exitAsk = true }
                    "S13" -> graph.window.closeControlOnly()
                }
            }
            // freeze one frame of a push for the QA shot list (S05 mid-push, S06 at the contact flash, S07 bounce, S08 x-ray)
            "shot" -> seq {
                val what = f.getOrNull(0)?.uppercase() ?: "S06"
                val li = lessonArg(1)
                val k = ((f.getOrNull(2)?.toIntOrNull() ?: 1) - 1).coerceIn(0, Joints.ALL[li].steps.lastIndex)
                val c = (f.getOrNull(3)?.toIntOrNull() ?: Joints.ALL[li].steps[k].fitIndex).coerceIn(0, Joints.ALL[li].steps[k].cands.lastIndex)
                toStep(li, k)
                routeFlow(graph, "arrow:$c")
                val p = flow.state.value.push ?: return@seq
                val at = when (what) {
                    "S05" -> p.at + T.FLY + 200
                    "S06" -> p.stopAt + 60
                    "S07" -> p.stopAt + T.BOUNCE / 2 + 60
                    else -> p.stopAt + T.REBOUND + 20
                }
                delay((at - flow.now()).coerceAtLeast(0))
                if (what == "S08" && flow.state.value.phase == Phase.DONE || flow.state.value.phase == Phase.ALL_DONE && what == "S08") {
                    val st = flow.state.value
                    delay(((st.doneAt + (if (st.tug) T.TUG else 0) + T.XRAY_IN + 800) - flow.now()).coerceAtLeast(0))
                }
                flow.freeze(flow.now())
            }
            "tap" -> routeFlow(graph, "arrow:${a.toIntOrNull() ?: 0}")
            "turn" -> routeFlow(graph, "turntable")
            "hold" -> if (a == "off") flow.freeze(null) else flow.freeze(flow.now())
            "lang" -> flow.updateProgress { it.copy(lang = when (a) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.AUTO }) }
            "sound" -> flow.updateProgress { it.copy(soundOn = on) }
            "hints" -> flow.updateProgress { it.copy(hintsOn = on) }
            "closecontrol" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.request("debug")
            "corruptsave" -> { graph.store.corrupt(); Log.i(TAG, "progress.json corrupted: force-stop and start to see S14") }
            "doneall" -> flow.markAllDone()
            "reset" -> { flow.updateProgress { Progress(lang = it.lang) }; flow.gotoLesson(0) }
            "page" -> when (a) {
                "settings" -> { Ui.page = Page.SETTINGS; Ui.howTo = false; Ui.exitAsk = false }
                "howto" -> { Ui.page = Page.SETTINGS; Ui.exitAsk = false; Ui.howTo = true }
                else -> Ui.page = Page.JOINTS
            }
            "hover" -> SceneHolder.scene?.setHover(a.takeIf { it != "off" })
            "replay" -> {
                // board-input hit replay: the deepest entity of the holder, resolved up the parent chain like a real hit
                val scene = SceneHolder.scene ?: return
                val target = f.getOrNull(0) ?: return
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.UNKNOWN
                val ents = scene.replayEntities(target)
                val hitEntity = ents.lastOrNull()
                val (hit, path) = resolveUp(hitEntity, BoardTargets(), 10)
                Log.i(TAG, "REPLAY $target via ${hitEntity?.getName()} -> $hit path=$path")
                BoardListener(graph, scene).onTap(hit, src, null)
            }
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence()} panel=${graph.window.panelState} page=${Ui.page}")
            "dump" -> Log.i(TAG, "DUMP ${flow.state.value}")
        }
    }
}
