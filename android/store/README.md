# Google Play artwork

The six portrait screenshots use real Android app captures with synthetic demo
workouts. No personal training history is included. The framing follows the iOS
App Store artwork: Avenir Next, graphite and blue, and layered mountain ridges.

`out/` contains 1080 × 1920 RGB screenshots, a 1024 × 500 feature graphic and a
512 × 512 RGB store icon. `raw/` preserves the unedited emulator captures.

Render with Node.js, Playwright and Chromium installed:

```sh
node android/store/render.mjs
```

Optional `PLAYWRIGHT_MODULE` and `CHROMIUM_PATH` environment variables select an
existing Playwright installation and Chromium executable. Avenir Next must be
available locally to reproduce the approved typography. No font files are bundled.

Capture notes: API 36 emulator (`Get_a_Grip_API_36`), 1080 × 2424, English, light
appearance, demo force gauge, SystemUI demo mode (9:41, full battery, no
notifications). The world is seeded by the DEBUG launch extras in
`app/src/main/kotlin/run/nuri/getagrip/debug/Seeds.kt`:

```sh
adb shell am start -n run.nuri.getagrip/.MainActivity --ez mockDevice true \
  --ez seedTwoRoutines true --ez seedHistory true --ez seedOneToday true
```

Launch twice before capturing Today: the first launch after a seed can still draw the
previous world. Tap the gauge chip to connect the demo device.

- `01_today`: Today, one of two sessions done.
- `02_working`, `03_rest`: add `--ez seedTargetKg true` (a 20–24 kg band on every set,
  which the demo gauge's 22 kg plateau sits inside), start a session, capture a hold
  with the clock running and the next rest's countdown. Today is captured without it:
  the kilogram band against the seeded maxes paints the routine's intensity mark red.
- `04_critical_force`: Benchmarks › Critical force history › the newest left-hand test
  (a seeded result, analysed by the real code). The live test (`--ez previewCriticalForce
  true`) was captured too, but the demo gauge's pulls drift against the test's beat and
  its per-pull bars read as gaps.
- `05_history`: History scrolled so the calendar and the load-per-grip chart share the
  screen.
- `06_builder`: the routine's overview › Edit routine, scrolled to the sets.

The store icon is derived from the app's actual launcher paths. Screenshot framing does
not alter the app UI or force readings.
