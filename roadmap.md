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

## Trader community outreach

Run advertising and outreach in trading communities to attract traders who will publish signals and connect their own communities to the app.

- Reach traders where they already share calls: trading channels, groups and social accounts.
- Explain how to run a feed, publish signals and invite followers who approve each trade on their own device.
- Help community owners bring their existing audience into the app as subscribers of their feed.

## Telegram bot adapter

Build an adapter that lets a Telegram bot act as a feed server and publish events to the app.

- Turn messages and commands posted through the bot into feed events on a channel the publisher owns.
- Map a Telegram channel or group to a feed so an existing audience can subscribe in the app.
- Keep the publisher's bot token and configuration on the publisher's own infrastructure.

## Discord bot adapter

Build the same adapter for Discord bots, so a Discord server can publish its events as a feed.

- Turn bot messages and slash commands into feed events.
- Map Discord channels to feeds and reuse the publishing path of the Telegram adapter.

## Legal review of Jupiter predictions

Review the legal status of placing prediction-market orders through Jupiter and prepare the app's legal documents.

- Assess which jurisdictions allow prediction-market participation and where it must be restricted.
- Prepare terms of use, a privacy policy and risk disclosures covering signals, swaps and predictions.
- Show the relevant notices in the app before a user places a prediction order.

## Support for more wallets

Add support for wallets beyond the current Mobile Wallet Adapter and Seed Vault Wallet setup.

- Let the user connect and choose between several wallet apps.
- Keep the same review and approval flow on the device whichever wallet signs.

## Automatic approval by rules

Let the owner approve matching requests automatically according to rules they define.

- Approve a request without manual review only when it matches an explicit rule the owner has enabled.
- Bound automatic approvals with limits such as amount, asset, counterparty, feed and time window.
- Record every automatic approval in the activity history with the rule that allowed it, and let the owner pause automation at any time.

## Flexible type-safe rules with Jev and an LLM

Make rules more expressive by describing them in a type-safe rule language (Jev) with LLM assistance.

- Let the owner describe a rule in plain language and have an LLM draft it in the typed rule language.
- Type-check each rule against the request schema before it can be saved, so an invalid rule is refused rather than guessed.
- Show the compiled rule to the owner for confirmation; the LLM drafts rules but never approves requests.
