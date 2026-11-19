"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio), window and plaque."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
BASE, CARD, FILL2 = "#E6E1D8", "#FBF6EC", "#D2CDC4"  # window base, how-to cards, measured secondary-button fill
pairs = [
    ("正文 / 标题 Ink / WindowBase", "#1B1F23", BASE, 4.5), ("课号、步数、小字 InkSecondary / WindowBase", "#3A3F44", BASE, 4.5),
    ("学过 ✓ Primary / WindowBase", "#17706F", BASE, 3.0), ("怎么操作 Ink / Card", "#1B1F23", CARD, 4.5), ("怎么操作手柄行 InkSecondary / Card", "#3A3F44", CARD, 4.5),
    ("主按钮字 白 / Primary", "#FFFFFF", "#17706F", 4.5), ("退出 白 / Error", "#FFFFFF", "#B3261E", 4.5),
    ("次按钮字 Ink / 次按钮底（实测）", "#1B1F23", FILL2, 4.5), ("木匾 象牙 / 深胡桃", "#F4E9D8", "#3A2A1E", 4.5),
    ("图例 金点 / WindowBase（图标）", "#E3B341", BASE, 1.0), ("图例 朱红点 / WindowBase（图标）", "#C8452C", BASE, 3.0),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
