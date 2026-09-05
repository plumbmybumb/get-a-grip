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

Capture notes: API 36 emulator, 1080 × 2424, English, light appearance, demo force
gauge. The sample live routine used a 20–24 kg target to match the demo signal.
The store icon is derived from the app's actual launcher paths. Screenshot
framing does not alter the app UI or force readings.
