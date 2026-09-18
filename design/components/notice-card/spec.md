# notice-card

## Contract

- **Tier:** `organism`
- **Kotlin name:** `NoticeCard`
- **Allowed dependencies:** none

Tertiary informational warning used for sandbox entry and stale-rule reassessment.

Theme tokens and stock Compose layout/text primitives are always allowed. The dependency list above is exhaustive for other design components.

## Kotlin API

```kotlin
enum class NoticeCardKind { Sandbox, StaleRules }

@Composable
fun NoticeCard(
    kind: NoticeCardKind,
    message: String,
    modifier: Modifier = Modifier,
)
```

The raw bare variants `sandbox-card` and `stale-card` map to `NoticeCardKind.Sandbox` and `NoticeCardKind.StaleRules`.

## Captured variants

| `data-variant` | Exact rendered HTML | Visual reference |
| --- | --- | --- |
| `sandbox-card` | [HTML](./sandbox-card.html) | [PNG](./sandbox-card.png) |
| `stale-card` | [HTML](./stale-card.html) | [PNG](./stale-card.png) |

## Builder note

> Tertiary container, one icon, one paragraph. Sandbox on entry, stale rules mid-review.

The HTML files are the exact-value specification. Use the PNGs only for side-by-side comparison; CSS px map to Compose dp and font px map to sp.

## Open questions

- The bare raw labels intentionally do not use `key=value`; preserve them in preview reference names.
