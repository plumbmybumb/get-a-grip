# Grip-change cues — 2026-09-05

Both runners announce changes to the complete GripSpec (edge, fingers or position), including finger curl. Ordinary hand swaps and adjacent sets with the same grip stay quiet.

`SessionRunner.newGripID` is a read-only presentation property. It compares the displayed slot with the most recently recorded pull, covering skipped sets as well as ordinary progression. The identifier persists from rest or lead-in through the first pull. Releasing still describes the grip in hand and does not announce a future instruction. Pausing preserves the notice. No engine event, credit, countdown or force threshold changes.

`upcomingGrip` provides notice during the final three credited seconds when both the outgoing rest and incoming lead-in are zero. The displayed grip remains current until progression. Consecutive short sets may show both current NEW GRIP and next-grip preview in the reserved notice area.

The glyph grows to 1.22× once, then settles using the existing critically damped Motion curve. Its root stays below the physical camera/island, with 14 points/dp of extra clearance reserved throughout the session. The notice has a fixed height scaled for text size; animation and text changes never insert an overlay over the force, clock, target or trace. The non-island iOS glyph has its own fixed drawing reservation. Reduce Motion suppresses growth while retaining the words and amber emphasis.

RunnerSession deduplicates a short, distinct two-note sound by grip transition ID, honoring the existing sound preference. It does not repeat on tick, pause, reconnect or rest-to-work. Native screen-reader announcements provide the new and upcoming grip descriptions. No new settings, confirmation step, or forced delay is introduced.

Regression cases cover zero-rest preview timing without replacing the current instruction, release/rest/lead-in continuity, all grip dimensions, skipped sets, ordinary hand swaps, rest/lead-in suppression of unnecessary previews, first-pull persistence and one-time sound. Compose layout checks assert identical metric bounds across all notice states at normal and doubled text. Existing runner fixture replay remains unchanged (39 scenarios, 4,130 steps).
