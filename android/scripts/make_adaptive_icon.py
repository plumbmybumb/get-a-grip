#!/usr/bin/env python3
"""The app icon — FOUR FINGERTIPS ON AN EDGE — as an Android adaptive icon.

Matches Sources/Assets.xcassets/AppIcon.appiconset/icon-1024.png: four graphite
fingers tucked behind a Bleu de France rung on a slate gradient. The original iOS
1024-unit artwork is mapped uniformly into Android's visible 72-unit viewport;
do not independently resize the fingers, their gaps, or the rung. The monochrome
layer uses exactly the same silhouette for themed launchers.

Geometry lives in a 108-unit viewport; the launcher masks the centre 72 and keeps the
66-unit circle safe. The mark fits inside that circle, including the rounded rung.
"""
import pathlib

RES = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
GRAPHITE = "#FF2B3038"
BLEU = "#FF318CE7"

# iOS source coordinates, with a top-left origin for Android. The fingers share
# their bottom (y=646); the rung starts at 626 and covers the lowest 20 units.
SOURCE_SIDE = 1024.0
SCALE = 72.0 / SOURCE_SIDE
INSET = 18.0
BAR_W = 148.0
GAP = 46.0
HEIGHTS = [286.0, 352.0, 322.0, 244.0]
FINGER_BOTTOM = 646.0
RUNG_W = 858.0
RUNG_H = 96.0
RUNG_Y = 626.0

def rounded_rect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (f"M{x + r:.2f},{y:.2f} h{w - 2 * r:.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f} "
            f"v{h - 2 * r:.2f} a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f} h{-(w - 2 * r):.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f} v{-(h - 2 * r):.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f} z")

def paths():
    hand_w = 4 * BAR_W + 3 * GAP
    x0 = (SOURCE_SIDE - hand_w) / 2
    def source_rect(x, y, w, h, r):
        return rounded_rect(INSET + x * SCALE, INSET + y * SCALE,
                            w * SCALE, h * SCALE, r * SCALE)
    out = []
    for i, height in enumerate(HEIGHTS):
        out.append((source_rect(x0 + i * (BAR_W + GAP), FINGER_BOTTOM - height,
                                BAR_W, height, BAR_W / 2), "bar"))
    # Draw last: the overlap makes this fingers gripping an edge, not floating bars.
    out.append((source_rect((SOURCE_SIDE - RUNG_W) / 2, RUNG_Y,
                            RUNG_W, RUNG_H, RUNG_H / 2), "rung"))
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
    (RES / "drawable" / "ic_launcher_background.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="54" android:startY="18"
                android:endX="54" android:endY="90"
                android:startColor="#FFE3E6EB" android:endColor="#FFC3C9D2" />
        </aapt:attr>
    </path>
</vector>
''')
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    (RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(adaptive)
    (RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(adaptive)
    print("wrote adaptive icon into", RES)

if __name__ == "__main__":
    main()
