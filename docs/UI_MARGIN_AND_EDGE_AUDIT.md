# Control spacing and release-edge audit

September 8, 2026. This is a UI-only follow-up to the release/rest cues. It does
not change force processing, release detection, hold-to-end timing, or storage.

## Scope and spacing rules

Reviewed shared buttons, chips and typed-value controls, plus their use in the
runner, summary, routine builder, Today, History, max measurement, gauge connection,
Settings and sharing/export sheets. The main defects were fixed heights, missing
inner label padding, and crowded equal-width rows with translated or enlarged text.

- Action labels have 16 pt/dp horizontal and 10 pt/dp vertical inner padding.
- Heights are minimums. Labels can wrap and their painted button surface grows.
- Actions sharing a row share the same painted height. Crowded runner actions
  reflow into fewer columns, including when the alternate hold label needs space.
- Holding an action does not change its target's size. Destructive hold durations
  remain unchanged; moving into a scroll cancels an in-progress hold.
- Compact selection chips retain their intentional horizontal density while gaining
  enough vertical space for wrapped text.
- Screen/card gutters retain the existing design tokens. Android readable-width
  constraints apply before fill constraints, with headings and cards aligned together.
- At accessibility text sizes, readability and reaching every control take priority
  over forcing the entire runner into one screen.

## Release border geometry

iOS uses the iOS 26 `ConcentricRectangle` shape at the root runner overlay, extending
through safe areas. Its six-point stroke uses the system container's corner shape,
replacing the previous estimate from safe-area height. See Apple's
[ConcentricRectangle documentation](https://developer.apple.com/documentation/swiftui/concentricrectangle).

Android 14 and later use the system `DisplayShape` path in window coordinates.
Android 12–13 use each reported rounded corner's radius and center. The outline is
translated into the overlay and clipped to the app window. A double-width stroke
clipped inside that outline leaves six dp visible at the outside edge. Source paths
are copied; geometry is cached and refreshed when insets, placement or size change.

The fallback handles empty or rectangular OEM display-shape data using available
corner information. Android's older rounded-corner API explicitly describes a
quarter-circle approximation; devices that supply no outline/corner data fall back
to the window rectangle. Pixel-perfect coverage of every physical device cannot be
certified from simulator tests or guaranteed where the OEM omits geometry.

Official references:

- [DisplayShape](https://developer.android.com/reference/android/view/DisplayShape)
- [WindowInsets.getDisplayShape](https://developer.android.com/reference/android/view/WindowInsets#getDisplayShape())
- [RoundedCorner](https://developer.android.com/reference/android/view/RoundedCorner)

Both overlays ignore touch and accessibility focus. The runner's releasing phase
owns the orange cue: it clears on actual release and does not continue over pause or
the summary. No additional repeating animation or sensor-driven geometry work is added.

## Pull-border follow-up

The same native outline now carries a steady four-point/dp blue border during active
work. During measured work, an engine dropout/over-target warning, rejected timeline,
or unavailable live stream takes priority with a six-point/dp red border. Readiness
uses connection, streaming and freshness booleans rather than sample-rate values.
Timer-only work uses its own clock and ignores unrelated gauge readiness. Releasing
retains the six-point/dp orange cue; armed, rest, countdown, pause and summary have
no screen border. Existing text/metric colors and engine behavior are unchanged.

Follow-up verification: all 660 iOS and 942 Android tests pass. The checks include
warning/recovery transitions, stale signal readiness, phase exclusions and timer-only
work. Native screenshots for blue, red and orange are in
`build/review/training-borders/`.

## Verification approach

Regression checks inspect rendered glyph insets and painted capsule bounds, not
only view frames. Runner checks exercise French labels at standard, enlarged and
accessibility sizes. Android geometry checks cover asymmetric corners, offset and
partial windows, noncircular outlines, resizing, empty platform data and preserving
the platform's original path.

Final results: 655 iOS unit tests passed, plus both French runner UI tests on the
compact iPhone SE and the accessibility/hold test repeated on iPhone 17 Pro. The
gesture test drags slowly from End, holds for longer than its activation time,
confirms the session remains open, then verifies a deliberate stationary hold ends
it. All 935 Android tests passed (533 app, 402 engine), including the 35 focused
layout, geometry, summary and grip-cue checks.

Review captures are stored under `build/review/margin-audit/` (iOS) and
`build/review/margins/` (Android), both ignored build output. The iOS device matrix
includes rounded iPhone 17 Pro and square-corner iPhone SE screens at normal,
French XXXL and accessibility text sizes. Android native Robolectric captures cover
compact phones, up to 2x text and a wider tablet layout. These are local UI fixtures,
not live Bluetooth sessions or released builds.
