# Swipe actions and visible Undo — 2026-09-05

Android History and Maxes now use a shared `SwipeActionRow`. A horizontal drag reveals a fixed-width button, sized to its localized text. There is no dismiss anchor and no action in a drag/settle callback: a tiny swipe, a fast fling, or repeated full swipes cannot delete. The user taps Delete to commit. History reveals Share on the opposite side, and tapping it opens that workout's export. Tapping the shifted row closes the reveal. Custom accessibility actions preserve access without gestures. Layout direction mirrors both dragging and placement.

The implementation uses Compose Foundation's native [anchored drag APIs](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling) to arbitrate horizontal dragging against vertical list scrolling. Release and close use the app's existing Motion curves. The iOS History row uses native leading Share swipe actions, with the existing trailing Delete behavior preserved.

`UndoSnackbar` itself now reserves `LocalFloatingTabBarInset`, the actual measured menu height. Today no longer adds its own duplicate padding, and History gains the clearance it was missing. The inset also follows the two-row menu used at large text sizes. Builder snackbars remain inside their full-screen presentation.

Six pointer/layout regression tests cover tiny and repeated high-speed swipes, explicit Delete taps, opposite-side sharing with fresh callbacks, tap-to-close, RTL, vertical scrolling, and Undo above both normal and doubled menu heights.

Validation passed: 393 Android app tests and the iOS build. On the simulator, a 90 ms full-width swipe left preserved all 92 seeded workouts and revealed Delete. Tapping Delete showed Undo above the floating menu; tapping Undo restored the same dated workout and its original 23.2 kg peak. Swiping right and tapping Share opened the one-workout, 36-pull CSV export. The temporary delete was undone.
