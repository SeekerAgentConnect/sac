# Feed onboarding

SEE-107 makes a public broadcast feed addable from the app's existing **Add connection** flow. It
does not add a deep link or intent filter: the owner scans, types or pastes the reference while that
screen is open.

## The input boundary

The flow accepts two schemes, and only one path handles either input:

```text
seekervault://pair?...
seekervault://feed?v=1&gateway=<origin>&server=<server ID>
```

`ConnectionsViewModel.onCode` tries a pairing code first. A valid pairing code goes only to pairing;
an input that is recognizably a malformed pairing code reports a pairing problem and never reaches
the feed path. Only an input that is not a pairing code is offered to `FeedReferences.parse`. A valid
feed reference goes only to feed onboarding, and each `FeedReferenceProblem` has feed-specific copy.
Both parsers apply the same Android cleartext policy to the supplied host.

## What the owner confirms

Before anything is stored, the existing Add connection sheet shows:

- the gateway origin;
- the server ID;
- that this is a public broadcast with no credential; and
- that adding it does not contact the publisher.

**Cancel** returns to entry and writes nothing. **Add feed** calls `ConnectionRepository.addFeed`
once. The gateway then supplies the manifest; the reference is never trusted to provide a display
name, channel, environment or plugin requirement.

## Outcomes

| Repository outcome | Owner-facing result |
| --- | --- |
| `Added` | Open the new connection. Its manifest display name, Sandbox or Production environment, and required client plugins are visible. |
| `Already` | Explain that the feed is already present and nothing was stored; offer to open it. |
| `Refused(ManifestProblem)` | Show the manifest problem code. Nothing is stored. |
| `Failed(CheckOutcome)` | State the gateway/check failure. Only transient unreachable or failed checks offer **Retry**. |
| `NoGateway` | State that this build has no gateway client for the reference. |

Storage failure is also stated without presenting it as a gateway refusal. Pairing keeps its existing
messages and behavior.

## What starts after `Added`

The connection store publishes its new connection immediately. Existing application-scoped owners
already observe that flow, so no restart or extra UI-owned transport was added:

1. `ForegroundFeedManager`, if the app is foregrounded, reopens the gateway listener for the new
   channel and performs its first authoritative snapshot before relying on live delivery.
2. `FeedTopicManager` asks the gateway for the feed's public topic and subscribes on its serialized
   Firebase channel.
3. A gateway configured without push answers `no_push`; that is expected. Snapshot, foreground
   stream and manual reads continue without Firebase.

The stream and topic managers still own their own lifecycles. The onboarding sheet receives no
transport, credential, wallet or plugin authority.

## Evidence

Routing, every parse and add outcome, one-call confirmation, cancellation, retryability and the
stock Compose presentation are covered by `ConnectionsViewModelTest` and `AddConnectionRouteTest`.
`ConnectionDetailsScreenTest` holds the required-plugin field. `ForegroundFeedManagerTest` and
`FeedTopicManagerTest` separately prove that a feed added while the app is already foregrounded
starts its first snapshot/stream and topic subscription without an app restart. Repository tests hold
the no-second-write behavior for `Already`.

Command and physical-device results are recorded in [`docs/testing/see-107.md`](../testing/see-107.md).
