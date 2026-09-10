# Get a Grip 1.1.0 development plan

Started 10 September 2026 on `codex/release-1-1-0`, based on public commit
`333d630fa63b728335fcc7309d8f0e0f32cf005e`. This is the next shared iOS/Android
development branch. The existing release tags remain immutable. This document
tracks proposed work; it does not claim features are implemented or released.

## Open source and community — scoped

- [ ] Extend the existing Settings source/credits area on both platforms with a
  compact “Open source & community” disclosure or section.
- [ ] Link **Grip Connect** to
  <https://github.com/Stevie-Ray/hangtime-grip-connect>, crediting gauge protocol
  ports from Stevie-Ray Hartog's project. Retain the existing BSD-2-Clause
  copyright and full notices in every distributed copy.
- [ ] Link **Crimpdeq** to <https://crimpdeq.com/>. Describe it as an open-source
  force sensor and thank its creator for testing Get a Grip. This is a community
  report, not new first-party hardware verification; do not change gauge flags.
- [ ] Keep links optional, open them in the browser and preserve short localized
  descriptions and accessible native tap targets. Avoid partnership or broad
  compatibility claims.
- [ ] Verify English/French layout, large text and link destinations on both apps.

## Optional developer support — decision pending

An optional “Buy me a coffee” / “Support development” action is being considered.
Existing support page: <https://buymeacoffee.com/irunnuri> (provided by Nuri).
The public page was checked on 10 September 2026: it identifies Nuri, links to
nuri.run, offers a custom contribution with a $1 USD minimum, and leaves the
“Make this monthly” option unchecked. No digital perks are advertised on that
page. Public-page inspection does not verify checkout completion or account
payout readiness.

No in-app payment feature, product, price or entitlement is configured yet.
Keep any support action separate from project attribution, with no training
features or assistance dependent on paying.

The proposed default for iOS is a native consumable tip purchase. An external
payment link needs a separate storefront/provider assessment. For Android, assess
whether a pure tip meets Google's direct-contribution exception before choosing
external payment or Play Billing. Website support is a separate option.

Policy references checked on 10 September 2026; recheck before implementation
and release:

- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/):
  sections 2.3.10, 3.1.1, 3.1.1(a), 3.2.1(vii) and 4.2.2.
- [Google Play payment-policy FAQ](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en):
  “Do direct tips or contributions from user to creator require Play’s billing system?”

## Release preparation — later

- [ ] Add upcoming features and regression reports to this plan as scope evolves.
- [ ] Set both marketing versions to 1.1.0 and assign fresh build numbers when
  preparing test/store artifacts; current manifests still identify the preceding
  releases.
- [ ] Run relevant platform, UI, shared-fixture and physical-device checks.
- [ ] Confirm the Android border fix on the reporter's actual device if available.
- [ ] Write final release notes, verify signed artifacts, tag exact source and
  submit only after the planned feature/testing work is complete.
