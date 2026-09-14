# SEE-81 — Request carousel alignment and snapping

The complete Linear issue and both embedded screenshots were read through Linear MCP on
2026-09-14. The reported Home capture showed the initial request centred; the expected capture
showed it beginning at the same left content edge as the rest of the Home hierarchy.

## What changed

- the carousel uses a 20 dp content inset instead of viewport-derived centring padding;
- one snap policy targets Start for index zero, Center for interior indices, and End for the final
  index;
- active-card selection measures each visible card against that same index-specific target;
- one request follows the first-item rule and remains left-aligned;
- card dimensions, 12 dp spacing, horizontal gestures, request identity, and tap behavior are
  unchanged.

No request, policy, connection, synchronization, notification, wallet, or sidecar behavior changed.

## Regression proof

The endpoint assertions were changed before the implementation. Against the preceding symmetric
centre-snap code, the focused test failed for the one-card left edge, the five-card first/last edges,
and the two-card right edge. After the implementation, the same suite passed and also confirmed an
interior active card settles at the viewport centre:

```console
ANDROID_HOME=… ./gradlew \
  :app:testDebugUnitTest \
  --tests 'io.github.brrenat.seekervault.connections.ConnectionsScreenTest'
```

The SDK path was supplied only as a process environment value; no machine-specific repository file
was written.

## Automated verification

Run on 2026-09-14 with Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1, Kotlin 2.4.0, and Java 19.0.2.

| Check | Result |
| --- | --- |
| Focused `ConnectionsScreenTest` | PASS — 13 tests, including one-, two-, and five-request endpoint alignment plus interior snapping |
| `pnpm test:push` | PASS — 35 sidecar checks plus the Android Stage 5.3 gate |
| `pnpm test:updates` | PASS — 10 sidecar checks plus the Android update and HTTP/2 interoperability gate |
| `pnpm check` | PASS — Prettier, Buf format/lint, ESLint, both TypeScript typechecks, 430 sidecar tests, and 29 test-agent tests |
| `pnpm test:hello` | PASS — all 9 Stage 1 simulated-device cases |
| `pnpm test:queue` | PASS — all 7 Stage 2 two-sidecar cases |
| `ANDROID_HOME=… pnpm check:android` | PASS — Spotless, all 848 Android unit tests, Android lint, debug APK, and instrumentation APK |
| `git diff --check` | PASS |

## Device and visual checks

**Physical Seeker: NOT RUN.** The two ticket screenshots were inspected, but no hardware screenshot,
gesture check, or pixel comparison was performed. Automated Compose bounds and swipe tests do not
count as a physical-device pass.
