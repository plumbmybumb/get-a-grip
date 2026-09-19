## What

<!-- The change, in a sentence or two. -->

## Why

<!-- The problem it solves. Link the issue if there is one. A behaviour change
     should have been discussed in an issue first. -->

## Tests run

- [ ] `./build.sh test`
- [ ] `./build.sh uitest` (if the change is visible or interactive)
- [ ] `./android/build.sh test`
- [ ] Not applicable — say why:

## Checks

- [ ] User-facing strings go through `Sources/Localizable.xcstrings` in English and
      French, and Android's catalog was regenerated with
      `python3 android/scripts/xcstrings_to_android.py`.
- [ ] Fixture expectations in `Fixtures/` are untouched — or the contract changed
      deliberately, both engines changed with it, and this pull request says so.
- [ ] Bundle ids, App Group and CloudKit container are unchanged.
- [ ] New source files carry the two-line SPDX header.
