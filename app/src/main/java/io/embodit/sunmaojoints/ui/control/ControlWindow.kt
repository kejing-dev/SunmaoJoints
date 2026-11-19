package io.embodit.sunmaojoints.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.sunmaojoints.BuildConfig
import io.embodit.sunmaojoints.R
import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.Lang
import io.embodit.sunmaojoints.domain.Phase
import io.embodit.sunmaojoints.domain.PlayState
import io.embodit.sunmaojoints.domain.T
import io.embodit.sunmaojoints.platform.Graph
import io.embodit.sunmaojoints.platform.TAG
import io.embodit.sunmaojoints.ui.Page
import io.embodit.sunmaojoints.ui.Ui
import io.embodit.sunmaojoints.ui.theme.SunmaoColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the volume, 560 x 360 dp, design P1): two pages switched by the
 * native SpatialUI TabBar (D8): 榫卯 / 设置. No page scrolls (D44); short pages centre their content.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e ->
            graph.window.onPanelLifecycle(e.name)
            // S13: closed while on the settings page -> back to the joints page, the volume answers again
            if (e.name == "ON_DESTROY") { Ui.page = Page.JOINTS; Ui.exitAsk = false; Ui.howTo = false; graph.flow.setSettingsOpen(false) }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page}")
        graph.flow.setSettingsOpen(Ui.page == Page.SETTINGS)
        if (Ui.page != Page.SETTINGS) { Ui.exitAsk = false; Ui.howTo = false }
    }
    // factory note (glass half black): at the emulator's idle frame rate a fresh window can stay half black until something redraws
    var redraw by remember { mutableIntStateOf(0) }
    LaunchedEffect(Ui.page, graph.window.panelState) { for (ms in longArrayOf(300, 1000, 2000, 3500, 6000)) { delay(ms); redraw++ } }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp))
            .drawBehind { if (redraw >= 0) drawRect(SunmaoColors.WindowBase) }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        when (Ui.page) {
            Page.JOINTS -> JointsPage(state, graph)
            Page.SETTINGS -> SettingsPage(state, graph)
        }
    }
    // The native TabBar is its own system window: labels resolved here in the app language (ui_gates tabbar-lang)
    NativeTabs(listOf(
        Triple(Page.JOINTS, R.drawable.ic_tab_joints, stringResource(R.string.tab_joints)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    ))
}

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

/** Ticks while a timed line ("对了。") is showing. */
@Composable
private fun rememberNow(state: PlayState, graph: Graph): Long {
    var now by remember { mutableLongStateOf(graph.flow.now()) }
    LaunchedEffect(state.okAt, state.phase, state.step) {
        now = graph.flow.now()
        val wait = state.okAt + T.OK_SHOW - now
        if (wait > 0) { delay(wait + 30); now = graph.flow.now() }
    }
    return now
}

/** Centred column: content in the middle, the 8 lesson dots at the bottom (design P1: 短页靠进度点和主按钮压空白). */
@Composable
private fun Page(state: PlayState, dots: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly, content = content)
        if (dots) LessonDots(state)
    }
}

/** 8 dots: learned = filled, current = ringed (design P1 最下一行 8 个进度点). */
@Composable
private fun LessonDots(state: PlayState) {
    Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..7) {
            val done = i in state.progress.done
            val cur = i == state.lesson
            Box(
                Modifier.padding(horizontal = 7.dp).size(if (cur) 16.dp else 12.dp).clip(CircleShape)
                    .background(if (done) SunmaoColors.Primary else Color.Transparent)
                    .border(if (cur) 2.5.dp else 1.5.dp, if (cur) SunmaoColors.Ink else SunmaoColors.InkSecondary, CircleShape)
            )
        }
    }
}

@Composable
private fun Header(left: String, right: String?, tag: String, learned: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(left, "$tag/label", style = PicoTheme.typography.labelLarge, color = SunmaoColors.InkSecondary, modifier = Modifier.weight(1f))
        if (right != null) Txt(right, "$tag/right", style = PicoTheme.typography.labelLarge, color = SunmaoColors.InkSecondary)
        if (learned) {
            Spacer(Modifier.width(8.dp))
            Txt("✓", "$tag/learned", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = SunmaoColors.Primary)
        }
    }
}

@Composable
private fun Title(text: String, tag: String) =
    Txt(text, "$tag/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = SunmaoColors.Ink)

@Composable
private fun Body(text: String, tag: String, lines: Int = 3) =
    Txt(text, "$tag/body", style = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 25.sp), color = SunmaoColors.Ink, maxLines = lines, modifier = Modifier.fillMaxWidth())

// ------------------------------------------------------------------ 榫卯 page

@Composable
private fun JointsPage(state: PlayState, graph: Graph) {
    val flow = graph.flow
    val zh = isZh()
    val l = state.lessonData
    val name = l.name.of(zh)
    val n = state.lesson + 1
    val now = rememberNow(state, graph)
    when (state.phase) {
        Phase.LOADING -> Page(state, dots = false) {
            Title(stringResource(R.string.app_name), "s00")
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Body(stringResource(R.string.loading), "s00", 1)
            }
        }
        Phase.CLOSED -> Page(state) {
            Header(stringResource(R.string.lesson_of, n), null, "s02", learned = state.learned)
            Title(name, "s02")
            if (state.corrupt) Body(stringResource(R.string.corrupt), "s14", 2)
            Body(l.usedIn.of(zh), "s02/used", 1)
            Body(stringResource(if (n == 1 && !state.progress.firstRunDone) R.string.first_sub else R.string.mini_line), "s02/mini", 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton("", "s02/prev", onClick = { flow.prev() }, enabled = state.lesson > 0, icon = R.drawable.ic_chevron_left, modifier = Modifier.width(96.dp))
                PrimaryButton(stringResource(R.string.btn_open), "s02/open", onClick = { flow.open() }, icon = R.drawable.ic_explode, modifier = Modifier.weight(1f))
                SecondaryButton("", "s02/next", onClick = { flow.next() }, enabled = state.lesson < 7, icon = R.drawable.ic_chevron_right, modifier = Modifier.width(96.dp))
            }
        }
        Phase.OPEN -> Page(state) {
            Header(stringResource(R.string.lesson_name, n, name), null, "s03", learned = state.learned)
            Body(l.why.of(zh), "s03/why", if (zh) 3 else 4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(SunmaoColors.Gold, stringResource(R.string.legend_tenon), "s03/gold")
                Spacer(Modifier.width(24.dp))
                Legend(SunmaoColors.Vermilion, stringResource(R.string.legend_mortise), "s03/red")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(stringResource(R.string.btn_build), "s03/build", onClick = { flow.build() }, icon = R.drawable.ic_arrow_push, modifier = Modifier.weight(1.4f))
                SecondaryButton(stringResource(R.string.btn_close), "s03/close", onClick = { flow.close() }, modifier = Modifier.weight(1f))
            }
        }
        Phase.PICK, Phase.PUSH, Phase.BLOCKED, Phase.TRAP -> Page(state) {
            Header(stringResource(R.string.lesson_name, n, name), stringResource(R.string.step_of, state.step + 1, l.steps.size), "s04")
            val reason = state.reason?.let { state.stepData?.cands?.getOrNull(it)?.sign?.of(zh) }
            val line = when {
                state.phase == Phase.BLOCKED || state.phase == Phase.TRAP -> null
                state.okAt > 0 && now - state.okAt < T.OK_SHOW -> stringResource(R.string.ok_line)
                state.firstTrip -> stringResource(R.string.first_pick)
                state.hint -> stringResource(R.string.try_glow)
                else -> stringResource(R.string.pick)
            }
            if (line != null) Body(line, "s04/line", 3)
            else {
                Txt(reason.orEmpty(), "s06/reason", style = PicoTheme.typography.titleMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = SunmaoColors.Ink, maxLines = 2)
                Body(stringResource(R.string.another), "s06/another", 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton(stringResource(R.string.btn_quit), "s04/quit", onClick = { flow.quit() }, modifier = Modifier.width(200.dp))
            }
        }
        Phase.DONE -> Page(state) {
            Header(stringResource(R.string.lesson_of, n), null, "s09", learned = true)
            Title(stringResource(R.string.done_title, name), "s09")
            Body(l.why.of(zh), "s09/why", if (zh) 3 else 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(stringResource(R.string.btn_next), "s09/next", onClick = { flow.nextLesson() }, icon = R.drawable.ic_chevron_right, modifier = Modifier.weight(1.3f))
                SecondaryButton(stringResource(R.string.btn_again), "s09/again", onClick = { flow.again() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
            }
        }
        Phase.ALL_DONE -> Page(state) {
            Title(stringResource(R.string.all_title), "s10")
            Body(stringResource(R.string.all_sub), "s10/sub", 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(stringResource(R.string.btn_first), "s10/first", onClick = { flow.nextLesson() }, modifier = Modifier.weight(1.3f))
                SecondaryButton(stringResource(R.string.btn_again), "s10/again", onClick = { flow.again() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(color).border(1.5.dp, SunmaoColors.Ink, CircleShape))
        Spacer(Modifier.width(8.dp))
        Txt(text, tag, style = PicoTheme.typography.bodyLarge, color = SunmaoColors.Ink)
    }
}

// ------------------------------------------------------------------ 设置 page

@Composable
private fun SettingsPage(state: PlayState, graph: Graph) {
    val context = LocalContext.current
    val p = state.progress
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        when {
            // S12: the body turns into the question in place (design: 就地换成确认两键)
            Ui.exitAsk -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                Txt(stringResource(R.string.exit_q), "s12/q", style = PicoTheme.typography.titleLarge.copy(fontSize = 26.sp), color = SunmaoColors.Ink, maxLines = 2)
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(stringResource(R.string.exit_no), "s12/no", onClick = { Ui.exitAsk = false }, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.exit_yes), "s12/yes", onClick = { exitApp(context, graph) }, danger = true, icon = R.drawable.ic_exit, modifier = Modifier.weight(1f))
                }
            }
            Ui.howTo -> HowTo(Modifier.weight(1f))
            else -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                // gate B decision 5: sound is the first row
                SwitchRow(stringResource(R.string.settings_sound), "s11/sound", p.soundOn) { v -> graph.flow.updateProgress { it.copy(soundOn = v) } }
                SwitchRow(stringResource(R.string.settings_hints), "s11/hints", p.hintsOn) { v -> graph.flow.updateProgress { it.copy(hintsOn = v) } }
                OptionRow(stringResource(R.string.settings_lang), "s11/lang", labelWidth = 150.dp) {
                    Segments(listOf(Lang.ZH to stringResource(R.string.lang_zh), Lang.EN to stringResource(R.string.lang_en)), if (isZh()) Lang.ZH else Lang.EN,
                        "s11/lang", { l -> graph.flow.updateProgress { it.copy(lang = l) } }, Modifier.weight(1f))
                }
                Txt(stringResource(R.string.settings_fine, Joints.DISCLAIMER.of(isZh()), BuildConfig.VERSION_NAME), "s11/fine", style = PicoTheme.typography.bodySmall,
                    color = SunmaoColors.InkSecondary, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        }
        // D26: [退出应用] fixed at the bottom of every view of the settings page
        if (!Ui.exitAsk) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (Ui.howTo) SecondaryButton(stringResource(R.string.how_to_back), "s11/back", onClick = { Ui.howTo = false }, icon = R.drawable.ic_back, modifier = Modifier.weight(1f))
            else SecondaryButton(stringResource(R.string.settings_howto), "s11/howto", onClick = { Ui.howTo = true }, icon = R.drawable.ic_help, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.settings_exit), "s11/exit", onClick = { Ui.exitAsk = true; Log.i(TAG, "EXIT ask S12") }, danger = true, icon = R.drawable.ic_exit, modifier = Modifier.weight(1f))
        }
    }
}

/** 怎么操作 (profile B19): one row per main action, the hand way and the controller way. */
@Composable
private fun HowTo(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            Triple(R.drawable.ic_explode, R.string.how_to_open, R.string.how_to_open_hand to R.string.how_to_open_pad),
            Triple(R.drawable.ic_arrow_push, R.string.how_to_push, R.string.how_to_push_hand to R.string.how_to_push_pad),
            Triple(R.drawable.ic_rotate_right, R.string.how_to_turn, R.string.how_to_turn_hand to R.string.how_to_turn_pad),
            Triple(R.drawable.ic_window, R.string.how_to_window, R.string.how_to_window_hand to R.string.how_to_window_pad),
        ).forEachIndexed { i, (icon, title, ways) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SunmaoColors.Card).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = SunmaoColors.Ink)
                Spacer(Modifier.width(10.dp))
                Txt(stringResource(title), "howto/title$i", style = PicoTheme.typography.titleSmall, color = SunmaoColors.Ink, maxLines = 2, modifier = Modifier.width(132.dp))
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(ways.first), "howto/hand$i", style = PicoTheme.typography.bodySmall, color = SunmaoColors.Ink, maxLines = 1)
                    Txt(stringResource(ways.second), "howto/pad$i", style = PicoTheme.typography.bodySmall, color = SunmaoColors.InkSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    graph.window.exiting = true
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                // the system capsule never draws supportContent (pico-cli-feedback: TabBar 只画图标), so the word sits under
                // the icon in mainContent and takes the item's content colour (white when selected, puzzle-box QA q2-1)
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = LocalContentColor.current)
                    }
                },
            )
        }
    }
}
