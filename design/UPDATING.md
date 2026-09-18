# Updating the design guide

A design change flows through the exported guide before it flows into Kotlin. Use this fixed
procedure for every refresh; do not mix a guide refresh with a Compose implementation.

1. Change the design in Claude Design. Every changed or new component keeps its
   `data-component`/`data-variant` annotations and uses the shared builders such as `btn`,
   `iconBtn`, and `pill`.
2. Export all three offline pages, overwriting `design/export/components.html`,
   `design/export/app.html`, and `design/export/flow.html`. Keep those filenames stable, export them
   from the same shared source, and always replace all three together.
3. From the repository root, run `pnpm run design:capture`. Pass options directly because the root
   script already supplies npm's separator, for example `pnpm run design:capture --check`.
4. Read `git status --short design/` and `git diff -- design/`. A changed `variant.html` is the exact
   numeric spec; new folders identify new components or variants; deleted generated files identify
   removals; a `tokens.json` diff is a theme change.
5. Update the hand-written `spec.md` for every changed or new component. Update `navigation.md` if
   the flow changed.
6. Add a dated entry to `design/CHANGELOG.md` that records the export hashes from `manifest.json`
   and names the changed tokens, components, and screens. SEE-111 owns creating that changelog in
   the initial guide; do not substitute the repository-wide changelog for it.
7. Commit the guide refresh as its own pull request, with no Kotlin changes.
8. File follow-up implementation work from the diff: a token change becomes a `:designsystem`
   theme update whose token-contract test initially fails; a component change becomes one task per
   composable with its HTML diff; a screen-only change becomes a screen task. Every follow-up
   re-records its Roborazzi goldens.
9. Run the sanity checks: capture twice and require no second-run diff; require every
   `data-component` to have a component folder and `spec.md`; require every variant named in a spec
   to exist in the generated references.
