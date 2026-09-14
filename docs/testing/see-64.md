# SEE-64 — Material 3 v4 UI

SEE-64 is a presentation-only pass over the Android app. The complete Linear description and the attached `Seeker Agent Connect v4 (offline).html` were read before implementation. The attachment's dark and light renderings are the visual source of truth.

## What changed

- system-following light and dark Material 3 colour, type, and shape tokens;
- opaque-only shared cards, buttons, dialogs, sheets, transient messages, and navigation, with zero tonal or shadow elevation;
- a Home dashboard with a scroll-reactive app bar and a request carousel whose viewport-derived end padding lets the first and last cards centre-snap, opens on the first request, and eases active colours over 200 ms;
- persistent Home, Requests, Wallet, and Activity navigation;
- connection, request, policy, and Activity details presented as a bottom-sheet stack;
- content-hugging acknowledgement and signature reviews, with pinned v4 actions and compact advisory verdict cards;
- animated sheet promotion and backplates using the design's 260 ms entrance, 240 ms exit, and 300 ms stack motion;
- v4 request-list cards and connection details, including the status, fact, Rules, and destructive card hierarchy;
- truthful three-state request badges, stable server/request identity in compact reviews, verified-before-advisory signing order, complete signable messages and configured policy checks, selected-state semantics for tabs and exclusive controls, wrapping critical actions, and scrollable dialog explanations;
- Global rules retained as the first-class rules layer above paired servers, with the HTML's complete expandable help card and separate caption, provenance chips, section icons, smoothly animated solid switches, configured/empty status lines, nested action/asset/address cards, reversible Clear all action, and tonal/filled pinned footer actions;
- transaction reviews that lead with the amount, recipient, wallet, network, and fee; distinguish device verification from Solana confirmation; name known programs before their addresses; and keep raw transaction data in collapsed technical details;
- bottom-navigation and gesture-inset clearance on Home and Pending requests so the final row can scroll fully into view;
- Material icons, compact identifier styling, source-ordered content, and v4 action hierarchy across the existing screens.

No request, transaction, policy, wallet, pairing, storage, or sidecar behavior changed. In particular, a policy still never approves or blocks an action, transaction validation still runs before policy assessment, and every wallet action still requires the owner's hand.

## Automated verification

Run on 2026-09-14 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, Gradle's pinned Temurin 21 daemon, and Oracle JDK 19.0.2 as the launcher. The installed Android SDK was supplied through `ANDROID_HOME`; no machine-specific repository file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 760 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — protocol code and fixtures are current; this UI ticket changes neither |
| `pnpm build` | PASS — sidecar and test-agent TypeScript builds |
| Android unit suite | PASS — 760 tests, including exact theme tokens/opacity, the opaque-only source guard, Home carousel endpoint centring for one, two, and five requests, bottom-nav list clearance, transaction hierarchy and technical-detail disclosure, Global rules configured/empty/error states and section hierarchy, complete request and policy review, selected-state semantics, large-text action wrapping, scrollable dialog copy, persistent navigation, sheet routes, and updated screen behavior |
| Opaque-surface source audit | PASS — no alpha colour, transparent colour, gradient, non-zero shadow/tonal elevation, blur, or graphics-layer use in app UI source |
| `git diff --check` | PASS |

The required commands include the unchanged stage-boundary guards. They continue to enforce the one wallet adapter call site, read-only transaction decoder and policy layer, separated storage locations, sidecar-only chain access, no background component, and role separation.

### SEE-74 Global rules follow-up

The ticket description, the expected dark Global rules capture, the reported mobile capture, and the Global rules states in the complete offline HTML were compared before the follow-up was implemented. Automated Compose coverage now exercises both an empty document and the configured reference state: the complete intro never begins mid-sentence, the caption is separate, all four sections expose their icon/provenance/switch/status hierarchy, configured actions and values are individual cards, empty lists are explicit, and the footer appears only for a dirty draft. Separate tests cover reversible Clear all, a pinned failed-save state with retry wording, and clearing that error after a new edit without losing the draft.

## Device and visual checks

**Physical Seeker: NOT RUN.** A Seeker was attached and the isolated `io.github.brrenat.seekervault.see64visual` visual fixture installed successfully beside the real app, but the device remained securely locked during the verification window, so no trustworthy dark/light screenshot comparison was recorded. The installed production package and its data were not replaced. Real Seed Vault Wallet hand-off, camera pairing, system light/dark transition, largest system text size, and TalkBack traversal have not been exercised on hardware for SEE-64. No request was approved, no wallet was opened, and no transaction was signed or sent.

The dark and light HTML reference states were inspected directly. SEE-82 later preserved those exact files under [`docs/design/`](../design/README.md) after repairing a theme regression. Automated Compose tests cover semantics and interaction paths, but they are not a substitute for a pixel comparison on the 390 by 844 Seeker viewport. The remaining owner check is therefore visual and device-specific: compare Home at the top and beyond 48 dp scroll, each root destination, nested sheets, Global and connection rules, all request verdict states, pairing, wallet, Activity, light/dark, largest text, and TalkBack against the checked-in design.
