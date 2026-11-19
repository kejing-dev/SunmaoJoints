package io.embodit.sunmaojoints.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.sunmaojoints.domain.Lang
import io.embodit.sunmaojoints.platform.Graph
import java.util.Locale

/** Wood, tenon gold, mortise vermilion, stone teal (design palette); text roles measured on the window base (D13). */
object SunmaoColors {
    /** Interaction stone teal, a shade deeper than the 3D arrows #1F8A8A (white text on it 5.6:1). */
    val Primary = Color(0xFF17706F)
    val OnPrimary = Color(0xFFFFFFFF)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text (>= 4.5:1 on the window base and on the cards). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Solid light base under every page (factory note: the emulator sometimes leaves part of the glass black). */
    val WindowBase = Color(0xFFE6E1D8)
    val Card = Color(0xFFFBF6EC)
    val Gold = Color(0xFFE3B341)
    val Vermilion = Color(0xFFC8452C)
    val WoodLight = Color(0xFFD9B88A)
    val WoodDark = Color(0xFF6B4A32)
    val Amber = Color(0xFFB7791F)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun SunmaoTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val lang = state.progress.lang
    val localized = remember(base, lang) { base.localized(lang) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = SunmaoColors.OnPrimary,
        labelPrimary = SunmaoColors.Ink,
        labelSecondary = SunmaoColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = SunmaoColors.Error,
        alert = SunmaoColors.Amber,
        passable = SunmaoColors.WoodDark,
        interaction = SunmaoColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Lang): Context {
    val locale = when (language) {
        Lang.AUTO -> return this
        Lang.ZH -> Locale.SIMPLIFIED_CHINESE
        Lang.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
