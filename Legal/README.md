# App use terms and acceptance

`current/agreement-2026-09-06-r2.json` is bundled byte-for-byte by both apps. It contains
English and French Terms, Privacy and agreement-screen copy. Both loaders compute
SHA-256 over those exact UTF-8 bytes. The website's matching version must be identical.

Training and max measurement open directly. There is no pre-training agreement
screen and no acknowledgement is recorded merely because somebody uses the app.
Terms and Privacy remain readable offline in Settings in English and French.
Measurement safeguards such as loaded-tare confirmation are independent of legal
acknowledgement and remain in place.

Earlier builds asked for an explicit acknowledgement and stored a local record
containing version, document fingerprint, language, UTC device time, app version,
platform and screen version. Genuine historical records remain readable and
shareable in Settings; the record link is hidden when no valid records exist.
The compatibility store and its validation tests remain so existing records are
not lost or fabricated. No current training entry point calls its acceptance API.
These records are not identity checks, injury releases, parental consent, server
evidence, or consent to optional health-data processing.

Once released, never edit a document under the same version. Archive the old file,
create a new version and update both resource loaders and the matching website copy.
Retain the original release commit, UI implementation and translations with each
version. The retained legacy matcher distinguishes versions, fingerprints and screen
versions for historical records; it no longer controls access to training.

These terms preserve injury claims and mandatory rights. No new injury waiver,
mandatory arbitration, financial-loss cap or parent signature process is included.
Those proposals need a separate scope and enforceability review. Preserve MPL and
third-party licenses; this app-use agreement does not replace them or automatically
replace the configured App Store EULA.

## Verification

Run `./build.sh test` and `./android/build.sh test` for storage and Android UI cases.
The `LegalUI` Xcode scheme checks direct entry to training and max measurement without
an agreement screen. Android UI tests check offline documents and malformed historical
records. Use disposable simulators with synthetic data; do not reset a user's records.
