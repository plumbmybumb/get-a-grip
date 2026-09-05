# Contributing

Open an issue for a reproducible bug or a proposed feature. Include the platform,
app version, gauge model, expected behavior, and what happened. Use synthetic
examples; remove personal training data and device identifiers from attachments.

For code changes, fork the repository, make a focused branch, and open a pull
request explaining the problem, resulting behavior, and validation. Keep the native
platform implementations consistent where they share data formats and engine rules.
A routine is a daily ritual; do not introduce a separate grip library.

Use `./build.sh test` for iOS and `./android/build.sh test` for Android. Run shared
fixture checks when changing codecs, grip keys, session timing, or sharing formats.
Device-clock timing, release gates, tare integrity, and reconnect behavior are
correctness boundaries: never trade them for a smoother-looking measurement.

Contributions are accepted under MPL 2.0, with existing third-party notices retained.
Only submit material you have the right to contribute. New dependencies need a
compatible license and an update to THIRD_PARTY_NOTICES.txt. Public discussion
should be respectful and focused on the work. Maintainers decide what ships in the
official app and are not required to accept every feature or contribution.
