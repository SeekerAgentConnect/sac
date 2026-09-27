# Design-system molecules

SEE-118 adds the second reusable component tier to Android's visual-only `:designsystem` module.
The ten stateless APIs compose theme values and the SEE-117 atoms; they do not know about app
models, navigation, storage, or side effects beyond caller-provided event lambdas.

## Components

| Design component | Compose API | State or content |
| --- | --- | --- |
| `fact-row` | `FactRow` | plain, monospace, or character-wrapping monospace values |
| `daily-row` | `DailyRow` | within, over, or no limit; global or connection scope |
| `segmented` | `Segmented` | two or three caller-supplied options and one selected index |
| `tab-bar` | `SeekerTabBar` | Pending or History |
| `nav-item` | `NavItem` | rest or selected, with caller-supplied icon and label |
| `section-header` | `SectionHeader` | optional small trailing action |
| `text-field` | `SeekerTextField` | value, placeholder, rest/error state, and validation message |
| `notice-card` | `NoticeCard` | sandbox or stale-rules notice |
| `empty-state` | `EmptyState` | Inbox or Rules presentation with caller-supplied copy |
| `filter-bar` | `FilterBar` | source, visible/total count, and Clear action |

The ticket's Compose names supersede older proposed names still present in the component specs,
matching the precedent set by SEE-117. The spec enums remain the public state contracts. Text-field
placeholder support is additive because the ticket requires it and the existing captured variants
continue to render concrete values.

`FactRowValueStyle.MonoWrap` keeps the label readable while allowing an identifier to break at any
character. `Segmented` validates that its option count agrees with its typed two/three-option axis.
The navigation item stays independently reusable; its gallery preview also composes four items into
the 390dp Home / Inbox / Wallet / Activity bottom bar required by the ticket.

## Visual verification

The 22 captured guide variants each have one exact-copy dark `@Preview` and `@DesignRef` recorded at
3×. The four-item navigation-bar preview is additional acceptance
evidence because the generated component guide contains only single-item references.

The guide and ticket disagree on one atom choice: the generated filter-bar HTML shows a medium
Clear button, while SEE-118 explicitly requires the small button. `FilterBar` follows the ticket;
the visual review records that intentional difference rather than hiding it with component-local
styling.
