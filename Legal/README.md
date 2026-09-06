# App use terms and acceptance

`current/agreement-2026-09-06-r2.json` is bundled byte-for-byte by both apps. It contains
English and French Terms, Privacy and agreement-screen copy. Both loaders compute
SHA-256 over those exact UTF-8 bytes. The website's matching version must be identical.

Before a routine (including timer-only mode) or max measurement starts, the app asks
for affirmative Terms acceptance. Declining returns to the previous screen; history,
manual logging, exports, deletion and Settings remain available. Reading the documents
does not accept them. The checkbox begins unchecked and resets on language changes.

The local record contains version, document fingerprint, language, UTC device time,
app version, platform and screen version. A successful atomic write precedes entry to
training. It is not an identity check, an electronic injury release, parental consent,
server evidence, or consent to optional health-data processing. Device time can be
changed and local records can be lost or restored with backups. Do not describe these
records as verified signatures. Users can read and share their record in Settings.

Once released, never edit a document under the same version. Archive the old file,
create a new version and update both resource loaders and the matching website copy.
Retain the original release commit, UI implementation and translations with each
version. A version, content fingerprint or agreement-screen-version change requires
renewed acceptance. A routine app-build change alone does not.

These terms preserve injury claims and mandatory rights. No new injury waiver,
mandatory arbitration, financial-loss cap or parent signature process is included.
Those proposals need a separate scope and enforceability review. Preserve MPL and
third-party licenses; this app-use agreement does not replace them or automatically
replace the configured App Store EULA.

## Verification

Run `./build.sh test` and `./android/build.sh test` for storage and Android UI cases.
The `LegalUI` Xcode scheme exercises iOS refusal, history access, explicit agreement
and acceptance after relaunch. Run it on a fresh disposable simulator with synthetic
launch data; it deliberately does not reset an existing user's agreement or history.
