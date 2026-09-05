#!/usr/bin/env python3
"""WCAG contrast, MEASURED — from real screenshot pixels or from the palette's own literals.

**Contrast is measured, never eyeballed.** That rule is in the root CLAUDE.md because dark
mode has now hidden two real failures from a careful eye on the iOS app (a white-on-white
primary button, and a routine card whose rep counts sat at 3.7:1 and whose hollow rings sat
at 2.1:1). The method there is `simctl io screenshot` plus pixel arithmetic; this is the same
arithmetic, with two front doors.

    # From a screenshot the phone took (adb exec-out screencap -p > shot.png):
    python3 android/scripts/measure_contrast.py shot.png 120,300 120,340 --label "ink on card"
    python3 android/scripts/measure_contrast.py shot.png --pairs pairs.txt

    # From the token literals in ui/theme/Tokens.kt, both schemes, no device needed:
    python3 android/scripts/measure_contrast.py --tokens
    python3 android/scripts/measure_contrast.py --colours 2E2E2E F0F2F6 --label "ink on card"

**What the arithmetic can and cannot answer.** A pair of opaque literals is exact: WCAG's
ratio is a pure function of two sRGB colours and nothing on the phone can change it. Two
things it cannot see, and both need a real screenshot:

  - **A translucent colour over an unknown backdrop.** `well` is 5 % ink and the palette's
    strokes are drawn at fractional alpha; this script composites them over a stated
    background, which is only as true as that background is.
  - **ANTIALIASING.** A 1–1.5 dp stroke is mostly edge, so its measured ratio comes out well
    below its nominal one — 0.75 alpha measured 2.98:1 on iOS where the arithmetic promised
    more. Thin strokes need their colour a full step stronger than this script says, and the
    only way to know is to sample the drawn pixels.

Thresholds are WCAG 2.1: 4.5:1 for body text, 3:1 for large text (>= 18 pt regular / 14 pt
bold) and for meaningful graphics — an icon or a stroke that carries information. Decoration
that repeats a fact stated in words beside it owes nothing.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TOKENS = Path(__file__).resolve().parent.parent / (
    'app/src/main/kotlin/run/nuri/getagrip/ui/theme/Tokens.kt'
)

TEXT_FLOOR = 4.5
GRAPHIC_FLOOR = 3.0


# ----------------------------------------------------------------- the arithmetic


def srgb_to_linear(channel: float) -> float:
    """One sRGB channel, 0..1, to linear light. The 0.03928 knee is WCAG's own."""
    return channel / 12.92 if channel <= 0.03928 else ((channel + 0.055) / 1.055) ** 2.4


def relative_luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = (srgb_to_linear(c / 255.0) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    la, lb = relative_luminance(a), relative_luminance(b)
    lighter, darker = max(la, lb), min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)


def composite(fg: tuple[int, int, int, int], bg: tuple[int, int, int]) -> tuple[int, int, int]:
    """Flatten a translucent foreground onto an opaque background — source-over, in sRGB.

    sRGB rather than linear light because that is what the GPU actually does for a normal
    blend, and the whole point of this file is to answer what lands on the glass.
    """
    a = fg[3] / 255.0
    return tuple(round(fg[i] * a + bg[i] * (1 - a)) for i in range(3))


# --------------------------------------------------------------------- parsing


def parse_hex(text: str) -> tuple[int, int, int, int]:
    """`0xFF2E2E2E`, `#2E2E2E`, `2E2E2E` or `0D2E2E2E` -> (r, g, b, a). Alpha defaults to 255."""
    raw = text.strip().lstrip('#')
    if raw.lower().startswith('0x'):
        raw = raw[2:]
    if len(raw) == 6:
        raw = 'FF' + raw
    if len(raw) != 8:
        raise ValueError(f'not a colour: {text}')
    a, r, g, b = (int(raw[i:i + 2], 16) for i in (0, 2, 4, 6))
    return (r, g, b, a)


COLOR_LINE = re.compile(r'^\s*(\w+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)', re.MULTILINE)
# A token whose value is a NAMED constant rather than a literal — `alarmFlat = ALARM_FLAT`,
# the one colour both palettes deliberately share.
ALIAS_LINE = re.compile(r'^\s*(\w+)\s*=\s*([A-Z][A-Z0-9_]+)\s*,', re.MULTILINE)
CONST_LINE = re.compile(r'^(?:private )?val ([A-Z][A-Z0-9_]+) = Color\((0x[0-9A-Fa-f]{8})\)', re.MULTILINE)
PALETTE_BLOCK = re.compile(r'val (\w+Palette) = GripPalette\((.*?)\n\)', re.DOTALL)


def read_palettes(path: Path = TOKENS) -> dict[str, dict[str, tuple[int, int, int, int]]]:
    """The token literals, straight out of `Tokens.kt`.

    Read from the SOURCE rather than copied here, so this script cannot drift from the palette
    it is measuring — the same reason `StringCatalogTests` reads the generated XML as text.
    """
    source = path.read_text(encoding='utf-8')
    out: dict[str, dict[str, tuple[int, int, int, int]]] = {}
    constants = {k: parse_hex(v) for k, v in CONST_LINE.findall(source)}
    for name, body in PALETTE_BLOCK.findall(source):
        colours = {k: parse_hex(v) for k, v in COLOR_LINE.findall(body)}
        for k, const in ALIAS_LINE.findall(body):
            if const in constants:
                colours[k] = constants[const]
        if 'graphiteInverse' not in colours and 'Color.White' in body:
            colours['graphiteInverse'] = (255, 255, 255, 255)
        out[name] = colours
    return out


# ------------------------------------------------------------------- reporting


def verdict(ratio: float, floor: float) -> str:
    return 'PASS' if ratio + 1e-9 >= floor else 'FAIL'


def report(label: str, fg: tuple, bg: tuple, floor: float, notes: str = '') -> bool:
    flat_bg = composite(bg, (255, 255, 255)) if len(bg) == 4 and bg[3] != 255 else bg[:3]
    flat_fg = composite(fg, flat_bg) if len(fg) == 4 and fg[3] != 255 else fg[:3]
    ratio = contrast_ratio(flat_fg, flat_bg)
    ok = ratio + 1e-9 >= floor
    print(
        f'{label:<46} {ratio:6.2f}:1  floor {floor:.1f}  '
        f'{verdict(ratio, floor)}{"  " + notes if notes else ""}'
    )
    return ok


# ------------------------------------------------------------- the palette pairs

# Every pair the app actually draws, named as the screen draws it. A pair NOT in this list is
# either decoration (it owes nothing) or does not occur.
#
# `graphic` marks a pair whose foreground is a mark rather than words: 3:1, not 4.5:1.
PAIRS = [
    ('inkPrimary', 'field', 'body text on the ground', False),
    ('inkPrimary', 'card', 'body text on a card', False),
    ('inkSecondary', 'field', 'secondary text on the ground', False),
    ('inkSecondary', 'card', 'secondary text on a card', False),
    ('inkTertiary', 'card', 'tertiary text on a card', False),
    ('inkTertiary', 'field', 'tertiary text on the ground', False),
    ('bleu', 'field', 'the live-force trace on the ground', True),
    ('bleu', 'card', 'the live-force trace on a card', True),
    ('alarm', 'card', 'alarm text on a card', False),
    ('alarm', 'field', 'alarm text on the ground', False),
    ('graphite', 'card', 'graphite label on a card', False),
    ('graphite', 'field', 'graphite label on the ground', False),
    ('graphiteInverse', 'graphite', 'the graphite pill: label on its fill', False),
    ('moss', 'card', 'the light-intensity rung on a card', True),
    ('calm', 'card', 'the steel ring on a card', True),
]

# Pairs where the TOKEN IS THE BACKGROUND — a fill with fixed ink on it. The ratio cannot
# move with the colour scheme, because at least one of the two colours is a literal.
FILL_PAIRS = [
    ((255, 255, 255, 255), 'alarmFlat', 'white on the delete backdrop', False),
    ((0x1B, 0x1F, 0x25, 255), 'armed', 'the rest badge: dark ink on its amber capsule', False),
]


def measure_tokens() -> int:
    palettes = read_palettes()
    failures = 0
    for scheme in ('LightPalette', 'DarkPalette'):
        colours = palettes[scheme]
        print(f'\n{scheme}')
        print('-' * 78)
        for fg_name, bg_name, label, graphic in PAIRS:
            fg, bg = colours.get(fg_name), colours.get(bg_name)
            if fg is None or bg is None:
                print(f'{label:<46} (missing token)')
                continue
            floor = GRAPHIC_FLOOR if graphic else TEXT_FLOOR
            if not report(f'{fg_name} on {bg_name} — {label}', fg, bg, floor):
                failures += 1
        for fg, bg_name, label, graphic in FILL_PAIRS:
            bg = colours.get(bg_name)
            if bg is None:
                continue
            if not report(f'{label} ({bg_name} fill)', fg, bg, GRAPHIC_FLOOR if graphic else TEXT_FLOOR):
                failures += 1
        # INFORMATIONAL, not a floor. `armed` is FF9800 in both schemes, carried unchanged
        # from iOS (`StatusTint.armed`), and it is a FILL here — the badge above measures
        # 7.68:1 with its fixed dark ink. Drawn as a RING or as ink on the light field it is
        # far below the 3:1 graphics floor, which is why the WORD always changes with the
        # colour (`New grip` / `Next`, `PAUSED`, `LET GO`): the cue survives greyscale on its
        # own. Recorded so nobody has to rediscover the number.
        armed, field = colours.get('armed'), colours.get('field')
        if armed and field:
            print(
                f'{"armed as a stroke on the field (informational)":<46} '
                f'{contrast_ratio(armed[:3], field[:3]):6.2f}:1  the word carries the cue'
            )
        # `well` is the one translucent token, and it is a SURFACE rather than a mark: it has
        # to be a quiet step off the card in BOTH schemes rather than a contrast ratio.
        well, card = colours.get('well'), colours.get('card')
        if well and card:
            flat = composite(well, card[:3])
            delta = contrast_ratio(flat, card[:3])
            print(f'{"well over card — the inset step":<46} {delta:6.2f}:1  (a step, not a floor)')
    return failures


# ------------------------------------------------------------------ screenshot


def sample(png: Path, x: int, y: int, radius: int = 1) -> tuple[int, int, int]:
    """The mean pixel in a small box — a single pixel on an antialiased edge is noise."""
    try:
        from PIL import Image  # noqa: PLC0415
    except ImportError:
        sys.exit('Sampling a screenshot needs Pillow: python3 -m pip install pillow')
    with Image.open(png) as im:
        im = im.convert('RGB')
        w, h = im.size
        acc = [0, 0, 0]
        n = 0
        for dy in range(-radius, radius + 1):
            for dx in range(-radius, radius + 1):
                px, py = x + dx, y + dy
                if 0 <= px < w and 0 <= py < h:
                    p = im.getpixel((px, py))
                    acc = [acc[i] + p[i] for i in range(3)]
                    n += 1
        return tuple(round(c / n) for c in acc)


def parse_point(text: str) -> tuple[int, int]:
    x, _, y = text.partition(',')
    return int(x), int(y)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('png', nargs='?', help='a screenshot to sample (adb exec-out screencap -p)')
    ap.add_argument('points', nargs='*', help='X,Y X,Y — foreground then background')
    ap.add_argument('--tokens', action='store_true', help='measure the palette literals, both schemes')
    ap.add_argument('--colours', nargs=2, metavar=('FG', 'BG'), help='two hex colours, ARGB or RGB')
    ap.add_argument('--pairs', metavar='FILE', help='a file of "X,Y X,Y label" lines to sample')
    ap.add_argument('--label', default='pair')
    ap.add_argument('--graphic', action='store_true', help='use the 3:1 graphics floor')
    ap.add_argument('--radius', type=int, default=1, help='sample box radius in pixels (default 1)')
    args = ap.parse_args()

    floor = GRAPHIC_FLOOR if args.graphic else TEXT_FLOOR

    if args.tokens:
        failures = measure_tokens()
        print()
        print(f'{failures} pair(s) under the floor.')
        return 1 if failures else 0

    if args.colours:
        fg, bg = (parse_hex(c) for c in args.colours)
        return 0 if report(args.label, fg, bg, floor) else 1

    if not args.png:
        ap.error('give a screenshot, --colours, or --tokens')

    png = Path(args.png)
    if args.pairs:
        failures = 0
        for line in Path(args.pairs).read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            a, b, *rest = line.split()
            label = ' '.join(rest) or 'pair'
            graphic = label.endswith('!graphic')
            label = label.removesuffix('!graphic').strip()
            fg = sample(png, *parse_point(a), args.radius)
            bg = sample(png, *parse_point(b), args.radius)
            if not report(label, fg, bg, GRAPHIC_FLOOR if graphic else TEXT_FLOOR):
                failures += 1
        return 1 if failures else 0

    if len(args.points) != 2:
        ap.error('give two points: X,Y X,Y')
    fg = sample(png, *parse_point(args.points[0]), args.radius)
    bg = sample(png, *parse_point(args.points[1]), args.radius)
    print(f'  foreground #{fg[0]:02X}{fg[1]:02X}{fg[2]:02X}   background #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}')
    return 0 if report(args.label, fg, bg, floor) else 1


if __name__ == '__main__':
    sys.exit(main())
