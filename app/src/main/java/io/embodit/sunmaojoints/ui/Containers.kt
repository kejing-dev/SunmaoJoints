package io.embodit.sunmaojoints.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The volume is the default container (manifest). */
object Containers {
    const val BOARD = "SunmaoJoints"
    const val CONTROL = "SunmaoControl"
    const val CONTROL_TAG = "control"
}

/** The two pages of the control window (design panels P1: 榫卯 / 设置, native TabBar). */
enum class Page { JOINTS, SETTINGS }

/** Window-page state shared process-wide so debug commands and the flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.JOINTS)
    /** S12: the settings page body turned into the quit question in place. */
    var exitAsk by mutableStateOf(false)
    /** The 怎么操作 view inside the settings page ([退出应用] stays at the bottom of both views). */
    var howTo by mutableStateOf(false)
}
