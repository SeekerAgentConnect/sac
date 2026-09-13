# SEE-64 — Material 3 v4 UI

SEE-64 is a presentation-only pass over the Android app. The complete Linear description and the attached `Seeker Agent Connect v4 (offline).html` were read before implementation. The attachment's dark and light renderings are the visual source of truth.

## What changed

- system-following light and dark Material 3 colour, type, and shape tokens;
- opaque-only shared cards, buttons, dialogs, sheets, transient messages, and navigation, with zero tonal or shadow elevation;
- a Home dashboard with a scroll-reactive app bar and snapping request carousel;
- persistent Home, Requests, Wallet, and Activity navigation;
- connection, request, policy, and Activity details presented as a bottom-sheet stack;
- content-hugging acknowledgement and signature reviews, with pinned v4 actions and compact advisory verdict cards;
- animated sheet promotion and backplates using the design's 260 ms entrance, 240 ms exit, and 300 ms stack motion;
- v4 request-list cards and connection details, including the status, fact, Rules, and destructive card hierarchy;
- Global rules retained as the first-class rules layer above paired servers;
- Material icons, compact identifier styling, source-ordered content, and v4 action hierarchy across the existing screens.

No request, transaction, policy, wallet, pairing, storage, or sidecar behavior changed. In particular, a policy still never approves or blocks an action, transaction validation still runs before policy assessment, and every wallet action still requires the owner's hand.

## Automated verification

Run on 2026-09-13 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, Gradle's pinned Temurin 21 daemon, and Oracle JDK 19.0.2 as the launcher. The installed Android SDK was supplied through `ANDROID_HOME`; no machine-specific repository file was written.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript type checks, 396 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device acceptance cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar acceptance cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 744 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `pnpm check:generated` | PASS — protocol code and fixtures are current; this UI ticket changes neither |
| `pnpm build` | PASS — sidecar and test-agent TypeScript builds |
| Android unit suite | PASS — 744 tests, including exact theme tokens/opacity, the opaque-only source guard, Home carousel structure and connection-scoped keys, persistent navigation, sheet routes, and updated screen behavior |
| Opaque-surface source audit | PASS — no alpha colour, transparent colour, gradient, non-zero shadow/tonal elevation, blur, or graphics-layer use in app UI source |
| `git diff --check` | PASS |

The required commands include the unchanged stage-boundary guards. They continue to enforce the one wallet adapter call site, read-only transaction decoder and policy layer, separated storage locations, sidecar-only chain access, no background component, and role separation.

## Device and visual checks

**Physical Seeker: NOT RUN.** A Seeker was attached and the isolated `io.github.brrenat.seekervault.see64visual` visual fixture installed successfully beside the real app, but the device remained securely locked during the verification window, so no trustworthy dark/light screenshot comparison was recorded. The installed production package and its data were not replaced. Real Seed Vault Wallet hand-off, camera pairing, system light/dark transition, largest system text size, and TalkBack traversal have not been exercised on hardware for SEE-64. No request was approved, no wallet was opened, and no transaction was signed or sent.

The dark and light HTML reference states were inspected directly. Automated Compose tests cover semantics and interaction paths, but they are not a substitute for a pixel comparison on the 390 by 844 Seeker viewport. The remaining owner check is therefore visual and device-specific: compare Home at the top and beyond 48 dp scroll, each root destination, nested sheets, Global and connection rules, all request verdict states, pairing, wallet, Activity, light/dark, largest text, and TalkBack against the attached design.
