package io.embodit.sunmaojoints.domain

import org.junit.Test
import java.io.File

/**
 * Gate B decision 4 audit table for ui-audit.md: every arrow of every step at the step's stop, its hit sphere and its
 * visual angle from the three eye spots, plus the smallest gap to its neighbours. Writes only when ARROW_AUDIT is set.
 */
class ArrowAuditTest {
    @Test fun table() {
        val out = System.getenv("ARROW_AUDIT") ?: return
        val sb = StringBuilder("| 课 | 步 | 箭头 | 方向 | 结果 | 碰撞体 Ø | 中心 (m) | 正前 | 左 30° | 右 30° | 最小间隔（正前） |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        var minAng = 99f; var minGap = 99f
        for ((li, l) in Joints.ALL.withIndex()) for (k in l.steps.indices) {
            val spots = Layout.arrowSpots(l, li, k, Layout.yawQ(0f))
            for ((i, s) in spots.withIndex()) {
                val c = l.steps[k].cands[i]
                val a = Layout.EYES.map { Layout.angleDeg(it, s.pos, Layout.ARROW_HIT_D) }
                val gap = spots.indices.filter { it != i }.minOfOrNull { j ->
                    Layout.sepDeg(Layout.EYE, s.pos, spots[j].pos) - Layout.angleDeg(Layout.EYE, s.pos, Layout.ARROW_HIT_D) / 2 - Layout.angleDeg(Layout.EYE, spots[j].pos, Layout.ARROW_HIT_D) / 2
                } ?: 99f
                minAng = minOf(minAng, a.min()); minGap = minOf(minGap, gap)
                sb.append("| ${l.n} ${l.name.zh} | ${k + 1} | ${i + 1} | ${c.from.zh} | ${c.outcome} | ${(Layout.ARROW_HIT_D * 100).toInt()} cm | ${s.pos} | ")
                sb.append(a.joinToString(" | ") { "%.2f°".format(it) }).append(" | ${"%.2f°".format(gap)} |\n")
            }
        }
        sb.append("\n最小视角 ${"%.2f".format(minAng)}°（要求 ≥ 3°），最小间隔 ${"%.2f".format(minGap)}°（要求 ≥ 2°）。转台另外 3 档由 LayoutTest.arrowsBigEnoughSeparateAndVisible 断言（≥ 3°、≥ 2°、≥ 70 % 不被木头挡）。\n")
        File(out).writeText(sb.toString())
    }
}
