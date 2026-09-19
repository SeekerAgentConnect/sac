# Gateway-private retirement (SEE-130)

Gateway-private pairing is retired. The shared feed gateway serves only anonymous public reads and
authenticated publisher writes. It has no invitation page, invitation/device RPCs, device
credentials, private request store, result return route, or private connection revocation API.

The two active connection modes are:

| Mode | Onboarding | Traffic and results |
| --- | --- | --- |
| `direct` | A one-use `seekervault://pair` link or QR from the independent server | SAC calls that server; declared results return to it |
| `gateway_feed` | A public `seekervault://feed` reference | SAC reads the shared gateway; owner choices and outcomes stay on the phone |

An old `seekervault://invite` link is recognized only to show that invitations were retired. SAC
extracts no credential and calls no endpoint. Ask the server operator for a fresh direct pairing
code. The direct server's existing `pnpm pair` command prints both a QR and the complete link; a
website, bot, or CLI may deliver that same link without changing the transport.

## Existing phone records

On first load, a stored `gateway_private` connection is rewritten to an inert retirement marker.
Its label, former origin, server identity, device name, pairing time and local historical activity
remain visible. SAC deletes its device credential, clears pending notification/sync ownership,
marks waiting result records undeliverable, removes unfinished private proposal work, and never
interprets the record as direct or as a feed. The detail sheet explains the retirement and offers
**Pair directly**, which opens the ordinary blank pairing-code flow; no endpoint or credential is
invented or converted.

## Gateway database migration

Opening a schema-v2 broadcast database applies schema v3 in one transaction. It removes legacy
private manifests and drops `private_request`, `invitation`, and `device_binding`, in foreign-key
order. The six public tables, publisher credentials, manifest/proposal bytes, revisions, channel
sequences, and pending notices remain. A newer schema is refused by an older binary.

Back up the SQLite volume before deploying the migration. Rollback means stopping the new gateway,
restoring that v2 backup, and then starting the old binary; do not point the old binary at the v3
file and do not recreate private rows from application logs. See
[`broadcast-gateway.md`](broadcast-gateway.md#migration-and-rollback).

Protocol numbers and names used by the removed mode remain reserved, and a compatibility test
deny-lists every removed service, method request/response, result, invitation, and manifest type.
This prevents a later feature from assigning old bytes a new meaning.
