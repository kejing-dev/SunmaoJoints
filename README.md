# 榫卯课 Sunmao Joints

A lesson app for PICO OS 6 (Shared Space): eight Chinese wooden joints, 2.5x real size on a turntable, with a life-size
1:1 copy beside it. Pull a joint apart to see the tenon (gold) and the mortise (red), then put it back: each step offers
2–3 arrows, and only one way goes in. A wrong push stops where the wood really meets, and the plaque says why.

直榫 · 燕尾榫 · 格角榫 · 抱肩榫 · 楔钉榫 · 夹头榫 · 粽角榫 · 龙凤榫加穿带 (13 steps, 29 arrows: 13 go in, 14 stop, 2 traps).

- Package `io.embodit.sunmaojoints`, Spatial SDK BOM 6.1.9, Kotlin + SpatialUI (no Material).
- A volume (0.68 x 0.70 x 0.70 m) with the turntable, plus a control window (560 x 360 dp) to its right with a native
  TabBar: 榫卯 / 设置. Tap only: arrows, the turntable (a quarter turn), the badge that reopens a closed window.
- Fully offline, Chinese / English, progress saved in one small JSON file.

The joints are teaching models: simplified and enlarged to show the principle, not cutting plans.

## Build

```bash
JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.12.1+1 ./gradlew --offline assembleDebug :app:testDebugUnitTest
```

Sounds: CC0 wood recordings (freesound 254343 by cmorris035, Kenney impact-sounds), see `tools/make_sounds.py`.
Font: Noto Sans (OFL, `third_party/noto-sans`).
