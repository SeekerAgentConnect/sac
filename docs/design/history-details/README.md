# History item details — design brief (SEE-161)

The design source of truth for the read-only History record page, as attached to Linear SEE-161
(`history-details.zip`). It is committed unchanged so the implementation and its review can be
checked against the same references.

- [`TASK.md`](TASK.md) — the brief: navigation, the fixed page order, every atom, rules, and the
  acceptance checklist.
- `screens/` — the first viewport of each variant, plus the History list with its filter.
- `atoms/` — one element per image at 2×.

The zip does not include the Claude Design export (`SAC v4 History Details.dc.html`) the brief
names, so these references are not part of the generated guide under `design/`. When that export
exists, it goes through `design/UPDATING.md` as its own design-only change.

The implementation is described in [`docs/wiki/history-details.md`](../../wiki/history-details.md).
