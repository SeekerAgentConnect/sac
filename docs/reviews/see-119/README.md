# SEE-119 visual review

Each image places the design reference on the left and the Roborazzi capture on the right. These
43 images are the complete set of variants present under the twelve SEE-119 component directories.
Every pair was reviewed after the final capture pass.

## Final difference list

- Component structure, copy, token colours, spacing, radii, fixed dimensions, and state styling:
  no material differences remain after correcting the warning glyphs, TermsCard flex sizing, and
  compact wallet-address shortening.
- Natural-height rows differ by up to a few logical pixels because Chrome and Android crop Roboto
  line boxes differently. Material Symbols and Compose Material Icons also retain small vector-path
  differences. Neither changes layout intent or component state.
- Ticket precedence: the generated carousel specimen is 422px wide and renders both tiles in the
  rail state. SEE-119 explicitly requires a 358dp content viewport with the centred tile
  highlighted, so the Android evidence intentionally shows a lime first tile and the ticket-sized
  crop while preserving SEE-81 endpoint snapping.
- Missing specimens: there is no generated “couldn't tell server” WalletBanner warning variant, so
  it was not invented. The unchosen OwnerInput specimen exists only for swap. The ticket's
  daily-row TermsCard contract is implemented as an API, while the only generated TermsCard
  specimen remains the quoted swap summary shown below.
- The public `icon-button` atom is still absent from the hand-written guide. A private,
  token-backed icon action is used only inside these organisms so SEE-119 does not publish an
  undocumented atom contract.

## request-tile

### kind-ack-state-centred

![request-tile kind-ack-state-centred](request-tile/kind-ack-state-centred.png)

### kind-ack-state-in-rail

![request-tile kind-ack-state-in-rail](request-tile/kind-ack-state-in-rail.png)

### kind-pred-state-centred

![request-tile kind-pred-state-centred](request-tile/kind-pred-state-centred.png)

### kind-pred-state-in-rail

![request-tile kind-pred-state-in-rail](request-tile/kind-pred-state-in-rail.png)

### kind-sig-state-centred

![request-tile kind-sig-state-centred](request-tile/kind-sig-state-centred.png)

### kind-sig-state-in-rail

![request-tile kind-sig-state-in-rail](request-tile/kind-sig-state-in-rail.png)

### kind-sign-state-centred

![request-tile kind-sign-state-centred](request-tile/kind-sign-state-centred.png)

### kind-sign-state-in-rail

![request-tile kind-sign-state-in-rail](request-tile/kind-sign-state-in-rail.png)

### kind-tx-state-centred

![request-tile kind-tx-state-centred](request-tile/kind-tx-state-centred.png)

### kind-tx-state-in-rail

![request-tile kind-tx-state-in-rail](request-tile/kind-tx-state-in-rail.png)

## request-carousel

### state-rest-centred-0

![request-carousel state-rest-centred-0](request-carousel/state-rest-centred-0.png)

## inbox-row

### network-none-env-production

![inbox-row network-none-env-production](inbox-row/network-none-env-production.png)

### origin-request-verdict-ok

![inbox-row origin-request-verdict-ok](inbox-row/origin-request-verdict-ok.png)

### origin-request-verdict-warning

![inbox-row origin-request-verdict-warning](inbox-row/origin-request-verdict-warning.png)

### origin-signal-count-3-title-two-line

![inbox-row origin-signal-count-3-title-two-line](inbox-row/origin-signal-count-3-title-two-line.png)

### origin-signal-verdict-warning

![inbox-row origin-signal-verdict-warning](inbox-row/origin-signal-verdict-warning.png)

## history-row

### state-cancelled

![history-row state-cancelled](history-row/state-cancelled.png)

### state-dismissed

![history-row state-dismissed](history-row/state-dismissed.png)

### state-expired

![history-row state-expired](history-row/state-expired.png)

### state-sent

![history-row state-sent](history-row/state-sent.png)

### state-simulated

![history-row state-simulated](history-row/state-simulated.png)

### state-unknown

![history-row state-unknown](history-row/state-unknown.png)

## server-row

### state-connected

![server-row state-connected](server-row/state-connected.png)

### state-disconnected

![server-row state-disconnected](server-row/state-disconnected.png)

### state-unreachable

![server-row state-unreachable](server-row/state-unreachable.png)

## rule-row

### kind-action-state-checked

![rule-row kind-action-state-checked](rule-row/kind-action-state-checked.png)

### kind-action-state-unchecked

![rule-row kind-action-state-unchecked](rule-row/kind-action-state-unchecked.png)

### kind-asset-state-readonly

![rule-row kind-asset-state-readonly](rule-row/kind-asset-state-readonly.png)

### kind-asset

![rule-row kind-asset](rule-row/kind-asset.png)

### kind-program

![rule-row kind-program](rule-row/kind-program.png)

### kind-recipient

![rule-row kind-recipient](rule-row/kind-recipient.png)

## verdict-card

### verdict-ok

![verdict-card verdict-ok](verdict-card/verdict-ok.png)

### verdict-warning-count-1

![verdict-card verdict-warning-count-1](verdict-card/verdict-warning-count-1.png)

### verdict-warning-count-3

![verdict-card verdict-warning-count-3](verdict-card/verdict-warning-count-3.png)

## owner-input-card

### state-chosen-kind-prediction

![owner-input-card state-chosen-kind-prediction](owner-input-card/state-chosen-kind-prediction.png)

### state-chosen-kind-swap

![owner-input-card state-chosen-kind-swap](owner-input-card/state-chosen-kind-swap.png)

### state-unchosen-kind-swap

![owner-input-card state-unchosen-kind-swap](owner-input-card/state-unchosen-kind-swap.png)

## terms-card

### kind-swap-state-quoted

![terms-card kind-swap-state-quoted](terms-card/kind-swap-state-quoted.png)

## wallet-banner

### variant-compact

![wallet-banner variant-compact](wallet-banner/variant-compact.png)

### variant-expanded

![wallet-banner variant-expanded](wallet-banner/variant-expanded.png)

## wallet-handoff

### wallet-seed-vault-kind-transfer

![wallet-handoff wallet-seed-vault-kind-transfer](wallet-handoff/wallet-seed-vault-kind-transfer.png)

## sheet-scaffold

### variant-plain

![sheet-scaffold variant-plain](sheet-scaffold/variant-plain.png)

### variant-stacked-over-blurred

![sheet-scaffold variant-stacked-over-blurred](sheet-scaffold/variant-stacked-over-blurred.png)
