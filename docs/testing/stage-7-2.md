# Stage 7.2 design fidelity acceptance

Date: 2026-09-19

This is SEE-124's closing record for the Stage 7.2 parent, SEE-110, updated with SEE-122's remaining
sheet rebuild. Automated results and physical device results are kept separate; a JVM render or APK
build is never reported as a Seeker pass.

## Automated evidence

| Check | Result |
| --- | --- |
| Debug component gallery | **PASS** — the debug-only catalog calls all 128 scanner fixtures: the 118 captured variants in the 34 specified component groups, five review sheets, four diagnostic probes, and the four-item navigation assembly. A scanner-to-catalog equality test prevents omissions. |
| Release exclusion | **PASS** — the gallery activity, manifest entry, shortcut, resources, and design-system screen are in `src/debug`; `:app:assembleRelease` succeeds without them and the release APK contains no `ComponentGallery` class or shortcut resource. |
| Component and screen visual review | **PASS** — the 53 atom, 22 molecule, 43 organism, five review-sheet, five tab-screen, and six remaining-sheet captures are compared by the Roborazzi/`designCompare` run, which pairs 135 reference/actual paths. |
| Committed Roborazzi baselines | **PASS** — 128 design-system PNGs and 11 app-screen PNGs are generated into and committed from the modules' `src/test/snapshots/images/` directories. |
| Regression guard | **PASS** — `pnpm check:android` runs `verifyRoborazziDebug` in both modules and contains no record task. A deliberate deterministic rendering change makes verification fail; restoring it makes both verify tasks pass. CI therefore cannot approve an unrecorded render change by rewriting its baseline first. |
| One review template, five actions | **PASS** — Transfer, Swap, Prediction, Signature, and Acknowledge use `ReviewSheet` with exact fixtures whose captures are compared by the Roborazzi/`designCompare` run. |
| Five tab-flow screens | **PASS** — Home, Inbox, Wallet, Activity, and Add connection are composed from `:designsystem` and have app-module Roborazzi goldens compared by `designCompare`. |
| Remaining sheets (SEE-122) | **PASS** — Wallet hand-off, Connection detail, connection rules, Global rules, add/edit asset, and add address are stateless `:designsystem` compositions with app adapters. The six exact-copy fixtures are paired by `designCompare`; the ticket-specific difference list is empty. |
| SEE-74 visual match | **PASS in automated visual review** — the new Global rules sheet supplies the complete intro, Actions and Assets toggles, editable/delete asset rows, and shared sheet chrome. Its former mismatches are superseded by the `screens/rules-global.png` reference pair. |

`designCompare` also reports 52 generated token specimens that do not have one-preview-per-token
Android captures. Token values are instead held by `DesignTokensTest`; those generated specimens
are not public component variants.

## Explicit remaining Stage 7.2 gap

| Gap | Result and owner |
| --- | --- |
| Exported `icon-button` | **NOT RUN / BLOCKED** — the export has five references, but SEE-111 deliberately supplied no `design/components/icon-button/spec.md`. The guide forbids inferring an API without that contract. Existing organisms use their documented private token-backed icon action. A guide/spec refresh must precede a public component implementation. |
SEE-110's criterion that all listed sheets are composed from `:designsystem` is now met in code and
automated visual evidence. Stage 7.2 still cannot claim a physical-device acceptance pass until the
hardware checklist below is completed.

## Physical Seeker acceptance

**NOT RUN (2026-09-19).** The Android SDK ADB device list was empty:

```text
$ /Users/superset/Library/Android/sdk/platform-tools/adb devices -l
List of devices attached
```

Therefore each required hardware line remains **NOT RUN**:

- live gallery walk for all component variants;
- Home, Inbox, Wallet, Activity, and Add connection beside the exported reference;
- Transfer, Swap, Prediction, Signature, and Acknowledge review sheets;
- wallet hand-off, connection detail, connection rules, Global rules, add/edit asset, and add
  address sheets;
- system font scale 1.0;
- system font scale 1.3;
- gesture-navigation insets;
- three-button-navigation insets;
- pairing-code field keyboard overlap;
- address field keyboard overlap;
- threshold field keyboard overlap.

No issue discovered by a physical walk could be fixed or filed because no walk occurred. Run the
same checklist on an attached Seeker before declaring the stage fully accepted.
