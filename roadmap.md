# Roadmap

Ideas and planned improvements for Seeker Agent Connect.

**Items are listed in no particular order. Their position does not indicate priority or implementation sequence.** Scope and timing may change; these items describe future work, not currently available features.

## Paid Restricted feeds with SKR

Build a reference demo that lets feed publishers sell time-limited access to a Restricted feed, with payment in SKR.

- Show the price, payment network and access duration before the user pays.
- Let the user review and sign the payment with their connected wallet.
- Have the publisher's server verify the on-chain payment and automatically grant access through the existing Restricted-feed flow.
- Support renewal and automatically expire access when the paid period ends.
- Verify the payment's network, token mint, recipient, amount and association with the subscriber; prevent the same payment from being credited twice.
- Support real SKR payments on Mainnet and a separate Devnet demo using a clearly labelled `Demo SKR` test token with no monetary value.
- Provide a complete walkthrough covering payment, access activation, receiving a demo signal, expiry and renewal.

Payment verification and subscription management belong to the publisher's service; the gateway enforces the resulting access grants. This is payment for feed access, with no staking requirement.
