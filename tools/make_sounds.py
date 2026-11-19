"""Sunmao Joints sounds (design sound.list, profile D51: every wooden event is a real CC0 recording).

Sources in tools/sound-src/ are the cleaned CC0 wood recordings the factory's Klotski Box / Luban Lock already use
(freesound 254343 "Puzzle Pieces Foley Mvmt Wood Slide" by cmorris035, Kenney impact-sounds impactWood_*; full chain
and URLs in apps/sunmao-joints/build/sound-sources.md). This script only trims, resamples (rate change), lays the same
recording three times for the all-done chime, fades the ends and peak-normalises. No synthesis.

  python -X utf8 tools/make_sounds.py
"""
import os, wave
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "sound-src")
OUT = os.path.join(os.path.dirname(HERE), "app", "src", "main", "assets", "sounds")
SR = 22050


def load(name):
    with wave.open(os.path.join(SRC, name + ".wav")) as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, name
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768
        if w.getframerate() != SR:
            x = rate(x, w.getframerate() / SR)
        return x


def rate(x, r):
    idx = np.arange(int(len(x) / r)) * r
    return np.interp(idx, np.arange(len(x)), x)


def trim(x, ms):
    return x[: int(SR * ms / 1000)]


def fade(x, inn=3, out=25):
    x = x.copy()
    a = int(SR * inn / 1000); b = int(SR * out / 1000)
    if a: x[:a] *= np.linspace(0, 1, a)
    if b and len(x) > b: x[-b:] *= np.linspace(1, 0, b)
    return x


def norm(x, dbfs):
    return x / (np.abs(x).max() or 1) * 10 ** (dbfs / 20)


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    print(f"{name}.wav {len(x) / SR * 1000:.0f} ms peak {20 * np.log10(np.abs(x).max()):.1f} dBFS")


def main():
    for f in os.listdir(OUT) if os.path.isdir(OUT) else []:
        if f.endswith(".wav"): os.remove(os.path.join(OUT, f))
    # slide (push in / pull apart): the wood slide recording, ~400 ms
    save("slide", norm(fade(trim(load("slide"), 420)), -3))
    save("slide_2", norm(fade(trim(load("slide_2"), 420)), -3))
    # seat 「咔」: the crisp wood snap
    for i, s in enumerate(["snap", "snap_2", "snap_3"]):
        save("seat" + ("" if i == 0 else f"_{i + 1}"), norm(fade(trim(load(s), 160), 1, 30), -1.5))
    # block 「咚」: the medium wood knock, a touch lower
    for i, s in enumerate(["block", "block_2"]):
        save("block" + ("" if i == 0 else f"_{i + 1}"), norm(fade(trim(rate(load(s), 0.9), 230), 1, 40), -1.5))
    # tray: a piece set down on its mat (the heavy knock, quieter)
    for i, s in enumerate(["drop", "drop_2"]):
        save("tray" + ("" if i == 0 else f"_{i + 1}"), norm(fade(trim(load(s), 170), 1, 40), -4))
    # turn: a light wood rub
    save("turn", norm(fade(trim(load("slide_3"), 320)), -6))
    save("turn_2", norm(fade(trim(load("whoosh_2"), 320)), -6))
    # lock: one clear wood-block note (the first hit of the medium knock pair), unvaried
    lock = norm(fade(trim(load("apart"), 210), 1, 60), -1.5)
    save("lock", lock)
    # all done: the same note three times rising (rates 1, 1.12, 1.26 = do, re, mi), 300 ms apart
    out = np.zeros(int(SR * 0.95))
    for k, r in enumerate([1.0, 1.12, 1.26]):
        n = rate(lock, r); s = int(SR * 0.3 * k)
        out[s:s + len(n)] += n[: len(out) - s]
    save("all_done", norm(out, -1.5))


if __name__ == "__main__":
    main()
