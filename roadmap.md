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

## Smart prediction server by subscription

Build a subscription-based prediction server that turns a user's own prompt, written in the app, into a personal prediction feed and publishes it to that user.

- Let the user describe in plain language which markets they want to follow (topics, events, time horizon, liquidity, limits), and show the server's interpretation of the prompt for confirmation before the feed is created.
- Have the server select matching markets from its prediction providers, create a dedicated feed for that user and publish the matching market proposals to it, keeping each one in step with its source until it closes.
- Deliver the feed only to the subscriber who created it, through the existing Restricted-feed flow, so no other device can read it.
- Let the user edit or replace the prompt, pause the feed or delete it from the app; changes apply to the same feed without re-subscribing.
- Offer access by paid subscription with a clear price, period and feed limit, reusing the renewal and expiry model of paid Restricted feeds.
- Keep the owner in control: the feed proposes markets, while the side, the stake and the approval stay with the owner on their own device.
- Provide a complete walkthrough covering subscribing, writing a prompt, receiving the first proposals, editing the prompt, expiry and renewal.

Prompt interpretation, market selection and subscription management belong to the prediction server; the gateway delivers the resulting feed and enforces access.
