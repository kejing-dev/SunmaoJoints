package io.embodit.sunmaojoints.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.embodit.sunmaojoints.platform.BackKeys
import io.embodit.sunmaojoints.platform.TAG
import io.embodit.sunmaojoints.ui.theme.SunmaoColors

/**
 * D60: controller keys never press a window button. Buttons cannot take focus (so ENTER / DPAD_CENTER have nothing to
 * click) and the window eats A / X / Y / ENTER / DPAD_CENTER before they reach anything (logged, unassigned); BACK and
 * controller B go to the one back handler (reopen / nothing), never to a focused control.
 */
internal val PAD_KEYS = setOf(Key.ButtonA, Key.ButtonB, Key.ButtonX, Key.ButtonY, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

internal fun Modifier.eatPadKeys(where: String): Modifier = onPreviewKeyEvent { e ->
    if (BackKeys.route(e.nativeKeyEvent, "compose:$where")) true
    else if (e.key in PAD_KEYS) { Log.i(TAG, "KEY eaten $where ${e.key}"); true } else false
}

internal val NoFocus: Modifier = Modifier.focusProperties { canFocus = false }

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null) {
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) SunmaoColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) SunmaoColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (danger) SunmaoColors.OnError else PicoTheme.colorScheme.labelPrimary)
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) SunmaoColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/**
 * A compact key for the ①–⑥ handles and the six direction keys (v1.3, profile D50): text only, 64 dp high (B31), 72 dp min
 * width so six fit the 720 dp window; "on" (selected stick / the hint's way) is the interaction fill.
 */
@Composable
internal fun KeyButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false, sub: String? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 72.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/key", style = if (big) PicoTheme.typography.titleLarge else PicoTheme.typography.labelLarge, color = fg)
        if (sub != null) Txt(sub, "$tag/sub", style = PicoTheme.typography.labelSmall, color = fg)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left (or an icon). */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier.then(NoFocus), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) SunmaoColors.Card else Color.Transparent).border(1.5.dp, fg, CircleShape))
        }
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    // B19: the control itself is >= 48 dp tall (the default track is ~37 dp).
    SegmentControl(modifier = modifier.height(52.dp).then(NoFocus)) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = if (small) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/** A whole >= 48 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).then(NoFocus)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
