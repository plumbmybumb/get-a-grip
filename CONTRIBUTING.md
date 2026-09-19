# Contributing

By taking part here you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Proposing a change

Open an issue first for anything that changes behaviour, and say what you expected and
what happened. Include the platform, app version, gauge model, and a diagnostics export
if the gauge is involved. Use synthetic examples: remove personal training data and
device identifiers from attachments. Typos, comments and obvious one-line fixes can go
straight to a pull request.

Adding support for a force gauge is the one change with a checklist of its own:
[docs/ADDING_A_GAUGE.md](docs/ADDING_A_GAUGE.md) lists every place a device has to be
registered on both platforms. A port with no hardware behind it is welcome; describing
one as verified is not.

Then fork, make a focused branch, and open a pull request that says **what** changed and
**why**, and which commands you ran. Keep the two native implementations consistent
wherever they share a data format or an engine rule. Maintainers decide what ships in
the official app and are not required to accept every feature.

## Commands

```sh
./build.sh test          # iOS XCTest suite
./build.sh uitest        # iOS XCUITest suite — run by hand, not in CI
./android/build.sh test  # Kotlin engine and Android app tests
```

Run the suite for the code you touched, and both when the change is shared. See
[BUILDING.md](BUILDING.md) for the toolchain, the cross-platform oracle and the DEBUG
launch arguments.

## Rules that are not style

**Fixtures.** `Fixtures/` is the contract between two engines that share no code — see
[Fixtures/README.md](Fixtures/README.md). A rule change in `Shared/Engine` is not done
until the fixture and its Kotlin twin change with it. **Never edit an expectation to
make a failing test pass.** Regenerate only when you mean to change the contract, and
say so in the pull request.

**Translations.** Every user-facing string goes through
`Sources/Localizable.xcstrings` with English **and** French. No literal strings in a
view. Android's catalog is generated, never hand-written: run
`python3 android/scripts/xcstrings_to_android.py` after touching the catalog, and commit
what it writes.

**Grips.** A grip is a VALUE on a set row — edge, fingers, position — not a library
entry. Do not add a grip library, a grip list, or anything that has to exist before
somebody can pull. Identical specs are the same trend series because they are equal, not
because they point at the same record.

**Identities.** The official bundle ids, App Group and CloudKit container never change:
renaming one separates existing users from their data. Forks set their own in
`project.local.yml`, from `project.local.yml.example`.

**Measurement.** Device-clock timing, release gates, tare integrity and reconnect
behaviour are correctness boundaries. Never trade one for a smoother-looking
measurement, and never let a gauge's unverified protocol be described as verified.

## Code style

Swift 6 with `SWIFT_STRICT_CONCURRENCY: complete`; the Kotlin engine imports nothing from
Android. Every source file opens with the two-line SPDX header the others carry.

Comments explain **why**, not what — the reason a rule exists is what stops the next
change from undoing it. A decision taken from the maintainer's own training is cited in
place as `(Nuri, 2026-08-03)`: a name and the date it was decided. Keep that form when
you record one, and when you overrule one, say so and date it.

Do not add `Co-Authored-By` trailers to commits.

## Licensing

Contributions are accepted under MPL 2.0, with existing third-party notices retained.
Only submit material you have the right to contribute. New dependencies need a
compatible license and an update to `THIRD_PARTY_NOTICES.txt` — see BUILDING.md for the
tools that write it.
