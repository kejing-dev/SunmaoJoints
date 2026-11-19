package io.embodit.sunmaojoints

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.sunmaojoints.ui.Containers
import io.embodit.sunmaojoints.ui.board.BoardScreen
import io.embodit.sunmaojoints.ui.control.ControlWindow
import io.embodit.sunmaojoints.ui.theme.SunmaoTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the display-case volume (default
 * container, 0.68 x 0.70 x 0.70 m from the manifest) and the control window (Planar, 560 x 360 dp) to its right.
 * Every open uses Placement.right(volume) (profile D44 correction: Shared Space never promises "in front of you").
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            SunmaoTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 360.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            SunmaoTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 360.dp, maxWidth = 640.dp, maxHeight = 460.dp)) { ControlWindow() }
            }
        }
    }
