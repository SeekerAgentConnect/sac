# Replacing retired gateway invitations with direct pairing

Gateway-private invitations and device routing were removed in SEE-130. There is no replacement
gateway endpoint and no credential conversion. Choose one of the two active paths:

- For a request addressed to one owner, run a compatible direct server and give that owner its
  one-use `seekervault://pair` link or QR.
- For the same public publication to many subscribers, publish a `gateway_feed` and distribute its
  `seekervault://feed` reference.

## Direct onboarding

The repository's direct server already supplies the required link and QR:

```sh
pnpm pair
```

Deliver the printed `seekervault://pair` value through the operator's website, bot, or CLI, or let
the owner scan its QR. Android opens the same Add connection confirmation from a cold or warm link;
nothing is stored until the owner confirms. The code is single-use and should be treated as a
secret until pairing completes.

An owner with a retired gateway connection opens its detail sheet, reads why it is inert, and taps
**Pair directly**. That action opens the blank direct pairing flow. They must scan or paste a fresh
code from the server operator; SAC never derives a direct URL or credential from the old record.

## Operator migration

1. Back up the broadcast SQLite volume and record the deployed commit.
2. Deploy the SEE-130 gateway. Its startup transaction migrates schema v2 to v3 and removes private
   routing data while preserving public feed data.
3. Confirm the read and publisher listeners are healthy and that an old invitation URL returns
   404.
4. Issue fresh direct pairing codes to owners who still need addressed requests.
5. If rollback is required, stop the new gateway, restore the v2 backup, then run the old binary.

Old invitation links may still be pasted into SAC only so it can explain the retirement. They do
not resolve, redeem, or contact the former gateway. Detailed behavior and verification are in
[`../wiki/gateway-pairing.md`](../wiki/gateway-pairing.md) and
[`../testing/see-130.md`](../testing/see-130.md).
