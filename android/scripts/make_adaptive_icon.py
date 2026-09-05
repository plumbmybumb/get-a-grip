#!/usr/bin/env python3
"""The app icon — FOUR FINGERTIPS ON AN EDGE — as an Android adaptive icon.

The twin of scripts/make_app_icon.swift: four graphite bars of hand-shaped heights
gripping a Bleu de France rung on the flat slate field. It is the app's OWN mark (the
FingerGlyph scaled up), so icon and UI cannot drift apart. Flat fills only; the
launcher supplies depth. The monochrome layer is the same drawing in one colour so a
themed icon stays greyscale, as the iOS tinted variant does.

Geometry lives in a 108-unit viewport; the launcher masks the centre 72 and keeps the
66-unit circle safe, so the hand sits inside that circle. The finger gap is a little
wider than the in-app glyph's for the same reason the iOS icon tuned its gap against
60 pt: at launcher size four bars with a tight gap read as one striped block.
"""
import pathlib

RES = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
GRAPHITE = "#FF2B3038"
BLEU = "#FF318CE7"

BAR_W = 8.0
GAP = 5.0
LENGTH_FACTORS = [0.86, 1.0, 0.94, 0.80]   # index → little, the hand's own proportions
BASE_LENGTH = 26.0
TOP_Y = 31.0
RUNG_H = 6.0
RUNG_GAP = 5.0
RUNG_OVERHANG = 3.0

def rounded_rect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{x + r:.2f},{y:.2f} h{w - 2 * r:.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f} "
            f"v{h - 2 * r:.2f} a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f} h{-(w - 2 * r):.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f} v{-(h - 2 * r):.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f} z")

def paths():
    hand_w = 4 * BAR_W + 3 * GAP
    x0 = 54 - hand_w / 2
    out = []
    for i, f in enumerate(LENGTH_FACTORS):
        out.append((rounded_rect(x0 + i * (BAR_W + GAP), TOP_Y, BAR_W, BASE_LENGTH * f, BAR_W / 2), "bar"))
    rung_y = TOP_Y + BASE_LENGTH + RUNG_GAP
    out.append((rounded_rect(x0 - RUNG_OVERHANG, rung_y, hand_w + 2 * RUNG_OVERHANG, RUNG_H, 2.5), "rung"))
    return out

def vector(fill_for):
    body = "\n".join(f'    <path android:fillColor="{fill_for(kind)}" android:pathData="{d}" />'
                     for d, kind in paths())
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            f"{body}\n</vector>\n")

def main():
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(
        vector(lambda kind: BLEU if kind == "rung" else GRAPHITE))
    (RES / "drawable" / "ic_launcher_monochrome.xml").write_text(
        vector(lambda kind: "#FF000000"))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    (RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(adaptive)
    (RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(adaptive)
    print("wrote adaptive icon into", RES)

if __name__ == "__main__":
    main()
