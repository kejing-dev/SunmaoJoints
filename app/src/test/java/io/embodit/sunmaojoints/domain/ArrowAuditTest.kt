package io.embodit.sunmaojoints.domain

import org.junit.Test
import java.io.File

/**
 * Gate B decision 4 audit table for ui-audit.md: every arrow of every step at every turntable stop, its hit sphere and
 * its visual angle from the three eye spots, plus the smallest gap to its neighbours over the three eyes. Writes only when ARROW_AUDIT is set.
 */
class ArrowAuditTest {
    @Test fun table() {
        val out = System.getenv("ARROW_AUDIT") ?: return
        val sb = StringBuilder("| 课 | 步 | 档 | 箭头 | 方向 | 结果 | 碰撞体 Ø | 中心 (m) | 正前 | 左 30° | 右 30° | 最小间隔（三眼位） |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        var minAng = 99f; var minGap = 99f
        for ((li, l) in Joints.ALL.withIndex()) for (k in l.steps.indices) for (y in 0..3) {
            val spots = Layout.arrowSpotsAt(li, k, y)
            for ((i, s) in spots.withIndex()) {
                val c = l.steps[k].cands[i]
                val a = Layout.EYES.map { Layout.angleDeg(it, s.pos, Layout.ARROW_HIT_D) }
                val gap = spots.indices.filter { it != i }.minOfOrNull { j -> Layout.minGapDeg(s.pos, spots[j].pos) } ?: 99f
                minAng = minOf(minAng, a.min()); minGap = minOf(minGap, gap)
                sb.append("| ${l.n} ${l.name.zh} | ${k + 1} | $y | ${i + 1} | ${c.from.zh} | ${c.outcome} | ${(Layout.ARROW_HIT_D * 100).toInt()} cm | ${s.pos} | ")
                sb.append(a.joinToString(" | ") { "%.2f°".format(it) }).append(" | ${"%.2f°".format(gap)} |\n")
            }
        }
        sb.append("\n最小视角 ${"%.2f".format(minAng)}°（要求 ≥ 3°），最小间隔 ${"%.2f".format(minGap)}°（要求 ≥ 2°）。四档 × 三眼位由 LayoutTest.arrowsBigEnoughSeparateAndVisible 和 QaArrowAuditTest 断言（≥ 3°、≥ 2°、≥ 70 % 不被木头 / 虚影 / 转台 / 1:1 小样 / 木匾挡；控制窗（最大 640 × 460 dp + TabBar + 系统控件行，0.0008 m/dp）一个采样点都不挡，v0.1.2 q2-1）。\n")
        File(out).writeText(sb.toString())
    }
}
