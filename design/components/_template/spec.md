# `<component>`

## Purpose

<!-- What the component communicates or lets the owner do. -->

## Tier

<!-- Choose exactly one: atom, molecule, or organism. -->

`<atom | molecule | organism>`

## Compose contract

- Composable: `<ComposableName>`
- Signature:

  ```kotlin
  @Composable
  fun <ComposableName>(
      /* data in */
      /* event lambdas out */
      modifier: Modifier = Modifier,
  )
  ```

- Variants: <!-- Map every `data-variant` axis and value to an enum named for that axis. -->
- Allowed child components: <!-- List only components this component may compose, or `None`. -->

## Builder note

<!-- Record the shared builder used by the Claude Design component page and any constraint an
implementation must preserve. -->

## Generated references

<!-- List every generated `<variant>.html` / `<variant>.png` pair covered by this spec. Do not edit
those generated files. -->

## Open questions

<!-- Use `None` when the contract is complete. Do not silently resolve a design ambiguity in code. -->
