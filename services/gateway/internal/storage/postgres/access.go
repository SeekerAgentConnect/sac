package postgres

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Restricted feeds (SEE-156, docs/wiki/restricted-feeds.md).
//
// Every statement here is scoped to one server in the statement itself. That is what makes "a
// publisher cannot touch another publisher's grants" and "a session for one channel cannot read
// another" properties of the queries rather than checks somebody has to remember: a grant ID or a
// session digest that belongs to someone else simply matches no row.

// policyOf is the column value for an access policy. The empty policy is public, which is what
// every registration made before SEE-156 means.
func policyOf(access storage.Access) storage.AccessPolicy {
	if access.Policy == "" {
		return storage.PublicAccess
	}
	return access.Policy
}

// Access is a registered publisher's access policy.
func (s *Store) Access(ctx context.Context, serverID string) (storage.Access, error) {
	return accessOf(ctx, s.reader, serverID)
}

// Access inside a publication, so a manifest is stamped with the policy the same transaction
// reads.
func (t *Tx) Access(ctx context.Context, serverID string) (storage.Access, error) {
	return accessOf(ctx, t.tx, serverID)
}

func accessOf(ctx context.Context, from querier, serverID string) (storage.Access, error) {
	var (
		access storage.Access
		policy string
		epoch  int64
	)
	err := from.QueryRowContext(ctx,
		`SELECT access_policy, auth_origin, access_epoch FROM `+Schema+`.publisher WHERE server_id = $1`,
		serverID).Scan(&policy, &access.AuthOrigin, &epoch)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return access, fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
	case err != nil:
		return access, fmt.Errorf("read access: %w", err)
	}
	access.Policy = storage.AccessPolicy(policy)
	access.Epoch = uint64(epoch)
	return access, nil
}

// SetAccess is the operator choosing a feed's policy. A change of policy or origin moves the epoch,
// so every stream name issued under the old policy goes quiet; setting the same policy again
// changes nothing.
func (s *Store) SetAccess(ctx context.Context, serverID string, access storage.Access) error {
	if !access.Valid() {
		return fmt.Errorf("not an access policy: %q with origin %q", access.Policy, access.AuthOrigin)
	}
	return s.write(ctx, func(tx *Tx) error {
		held, err := tx.Access(ctx, serverID)
		if err != nil {
			return err
		}
		if policyOf(held) == policyOf(access) && held.AuthOrigin == access.AuthOrigin {
			return nil
		}
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.publisher SET access_policy = $1, auth_origin = $2, access_epoch = access_epoch + 1
			  WHERE server_id = $3`,
			string(policyOf(access)), access.AuthOrigin, serverID); err != nil {
			return fmt.Errorf("set access: %w", err)
		}
		return nil
	})
}

const grantColumns = `SELECT grant_id, server_id, subscriber_ref, device_ref, created_at_ms,
	        renewed_at_ms, expires_at_ms, revoked_at_ms, push_target <> ''`

func scanGrant(from scanner) (storage.Grant, error) {
	var (
		grant                     storage.Grant
		created, renewed, expires int64
		revoked                   sql.NullInt64
		target                    bool
	)
	if err := from.Scan(&grant.GrantID, &grant.ServerID, &grant.SubscriberRef, &grant.DeviceRef,
		&created, &renewed, &expires, &revoked, &target); err != nil {
		return grant, err
	}
	grant.CreatedAt, grant.RenewedAt, grant.ExpiresAt =
		instant(created), instant(renewed), instant(expires)
	if revoked.Valid {
		at := instant(revoked.Int64)
		grant.RevokedAt = &at
	}
	grant.HasPushTarget = target
	return grant, nil
}

// GrantFor finds the grant a session opens on this server's channel, live or not: the caller
// decides what a revoked or expired one means, because the answer to the reader differs.
func (s *Store) GrantFor(ctx context.Context, serverID string, digest []byte) (*storage.Grant, error) {
	grant, err := scanGrant(s.reader.QueryRowContext(ctx,
		grantColumns+` FROM `+Schema+`.access_grant WHERE server_id = $1 AND session_digest = $2`,
		serverID, digest))
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("find grant: %w", err)
	}
	return &grant, nil
}

// Grants lists one publisher's grants for the operator, newest first.
func (s *Store) Grants(ctx context.Context, serverID string) ([]storage.Grant, error) {
	rows, err := s.reader.QueryContext(ctx,
		grantColumns+` FROM `+Schema+`.access_grant WHERE server_id = $1 ORDER BY created_at_ms DESC, grant_id`,
		serverID)
	if err != nil {
		return nil, fmt.Errorf("list grants: %w", err)
	}
	defer rows.Close()
	var grants []storage.Grant
	for rows.Next() {
		grant, err := scanGrant(rows)
		if err != nil {
			return nil, fmt.Errorf("list grants: %w", err)
		}
		grants = append(grants, grant)
	}
	return grants, rows.Err()
}

// PutGrant creates or renews one grant.
//
// The checks are in the transaction that writes, so a revocation that commits first is seen by the
// renewal that follows it, and a renewal can never undo one.
func (s *Store) PutGrant(ctx context.Context, asked storage.GrantRequest) (storage.Grant, error) {
	var granted storage.Grant
	err := s.write(ctx, func(tx *Tx) error {
		access, err := tx.Access(ctx, asked.ServerID)
		if err != nil {
			return err
		}
		if !access.Restricted() {
			return storage.ErrNotRestricted
		}
		var (
			owner   string
			digest  []byte
			device  string
			expires int64
			revoked sql.NullInt64
		)
		err = tx.tx.QueryRowContext(ctx,
			`SELECT server_id, session_digest, device_ref, expires_at_ms, revoked_at_ms
			   FROM `+Schema+`.access_grant WHERE grant_id = $1`, asked.GrantID).
			Scan(&owner, &digest, &device, &expires, &revoked)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			// A session is one grant's and never two: a digest another grant already holds is a
			// binding that moved, whoever holds it.
			var taken int
			err := tx.tx.QueryRowContext(ctx,
				`SELECT 1 FROM `+Schema+`.access_grant WHERE session_digest = $1`, asked.SessionDigest).Scan(&taken)
			if err == nil {
				return storage.ErrGrantMismatch
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return fmt.Errorf("read grant: %w", err)
			}
			if _, err := tx.tx.ExecContext(ctx,
				`INSERT INTO `+Schema+`.access_grant
				   (grant_id, server_id, subscriber_ref, device_ref, session_digest,
				    created_at_ms, renewed_at_ms, expires_at_ms, revoked_at_ms, push_target)
				 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NULL, '')`,
				asked.GrantID, asked.ServerID, asked.SubscriberRef, asked.DeviceRef,
				asked.SessionDigest, milliseconds(asked.At), milliseconds(asked.At),
				milliseconds(asked.ExpiresAt)); err != nil {
				return fmt.Errorf("grant: %w", err)
			}
		case err != nil:
			return fmt.Errorf("read grant: %w", err)
		case owner != asked.ServerID:
			return storage.ErrNoGrant
		case revoked.Valid:
			return storage.ErrGrantRevoked
		case !bytes.Equal(digest, asked.SessionDigest) || device != asked.DeviceRef:
			return storage.ErrGrantMismatch
		default:
			// A renewal never shortens a grant: a late retry of an older renewal must not cut
			// access short that a newer one already extended.
			until := max(expires, milliseconds(asked.ExpiresAt))
			if _, err := tx.tx.ExecContext(ctx,
				`UPDATE `+Schema+`.access_grant SET renewed_at_ms = $1, expires_at_ms = $2, subscriber_ref = $3
				  WHERE grant_id = $4 AND server_id = $5`,
				milliseconds(asked.At), until, asked.SubscriberRef,
				asked.GrantID, asked.ServerID); err != nil {
				return fmt.Errorf("renew grant: %w", err)
			}
		}
		granted, err = scanGrant(tx.tx.QueryRowContext(ctx,
			grantColumns+` FROM `+Schema+`.access_grant WHERE grant_id = $1`, asked.GrantID))
		return err
	})
	return granted, err
}

// RevokeGrants ends grants on one server's channel, moves its epoch and retires the old stream
// name, all in one transaction.
func (s *Store) RevokeGrants(ctx context.Context, serverID string, grantIDs []string, at time.Time) (int, []storage.PushTarget, error) {
	var (
		revoked int
		targets []storage.PushTarget
	)
	err := s.write(ctx, func(tx *Tx) error {
		access, err := tx.Access(ctx, serverID)
		if err != nil {
			return err
		}
		// Every name first, before anything is written: a batch naming one grant the caller does
		// not hold revokes nothing, so a publisher cannot learn about another's grants by watching
		// which half of a batch took effect.
		for _, id := range grantIDs {
			var one int
			err := tx.tx.QueryRowContext(ctx,
				`SELECT 1 FROM `+Schema+`.access_grant WHERE grant_id = $1 AND server_id = $2`,
				id, serverID).Scan(&one)
			if errors.Is(err, sql.ErrNoRows) {
				return storage.ErrNoGrant
			}
			if err != nil {
				return fmt.Errorf("find grant: %w", err)
			}
		}
		for _, id := range grantIDs {
			var (
				target string
				ended  sql.NullInt64
			)
			if err := tx.tx.QueryRowContext(ctx,
				`SELECT push_target, revoked_at_ms FROM `+Schema+`.access_grant
				  WHERE grant_id = $1 AND server_id = $2`, id, serverID).
				Scan(&target, &ended); err != nil {
				return fmt.Errorf("read grant: %w", err)
			}
			if ended.Valid {
				continue
			}
			if _, err := tx.tx.ExecContext(ctx,
				`UPDATE `+Schema+`.access_grant SET revoked_at_ms = $1, push_target = ''
				  WHERE grant_id = $2 AND server_id = $3`,
				milliseconds(at), id, serverID); err != nil {
				return fmt.Errorf("revoke grant: %w", err)
			}
			revoked++
			if target != "" {
				targets = append(targets, storage.PushTarget{GrantID: id, Target: target})
			}
		}
		if revoked == 0 {
			return nil
		}
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.publisher SET access_epoch = access_epoch + 1 WHERE server_id = $1`,
			serverID); err != nil {
			return fmt.Errorf("move access epoch: %w", err)
		}
		channel := rules.ChannelFor(serverID)
		var sequence int64
		err = tx.tx.QueryRowContext(ctx,
			`SELECT sequence FROM `+Schema+`.channel_sequence WHERE channel = $1`, channel).Scan(&sequence)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return fmt.Errorf("read sequence: %w", err)
		}
		return tx.notice(ctx, channel, storage.AccessNotice,
			strconv.FormatUint(access.Epoch, 10), access.Epoch, uint64(sequence), at)
	})
	if err != nil {
		return 0, nil, err
	}
	return revoked, targets, nil
}

// SetPushTarget registers where a live grant's hints go.
func (s *Store) SetPushTarget(ctx context.Context, serverID string, digest []byte, target string, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.access_grant SET push_target = $1
			  WHERE server_id = $2 AND session_digest = $3 AND revoked_at_ms IS NULL
			    AND expires_at_ms > $4`,
			target, serverID, digest, milliseconds(at))
		if err != nil {
			return fmt.Errorf("set push target: %w", err)
		}
		changed, err := outcome.RowsAffected()
		if err != nil {
			return err
		}
		if changed == 0 {
			return storage.ErrNoGrant
		}
		return nil
	})
}

// PushTargets is every live grant's target on one server's channel.
func (s *Store) PushTargets(ctx context.Context, serverID string, at time.Time) ([]storage.PushTarget, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT grant_id, push_target FROM `+Schema+`.access_grant
		  WHERE server_id = $1 AND revoked_at_ms IS NULL AND expires_at_ms > $2 AND push_target != ''
		  ORDER BY grant_id`,
		serverID, milliseconds(at))
	if err != nil {
		return nil, fmt.Errorf("list push targets: %w", err)
	}
	defer rows.Close()
	var targets []storage.PushTarget
	for rows.Next() {
		var target storage.PushTarget
		if err := rows.Scan(&target.GrantID, &target.Target); err != nil {
			return nil, fmt.Errorf("list push targets: %w", err)
		}
		targets = append(targets, target)
	}
	return targets, rows.Err()
}

// PushTargetRejected clears a target the push endpoint refused, unless the device has registered
// another since.
func (s *Store) PushTargetRejected(ctx context.Context, grantID, target string) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.access_grant SET push_target = '' WHERE grant_id = $1 AND push_target = $2`,
			grantID, target); err != nil {
			return fmt.Errorf("clear push target: %w", err)
		}
		return nil
	})
}

// SweepGrants forgets grants that ended before the instant given.
func (s *Store) SweepGrants(ctx context.Context, before time.Time) (int64, error) {
	var removed int64
	err := s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`DELETE FROM `+Schema+`.access_grant
			  WHERE (revoked_at_ms IS NOT NULL AND revoked_at_ms < $1) OR expires_at_ms < $2`,
			milliseconds(before), milliseconds(before))
		if err != nil {
			return fmt.Errorf("sweep grants: %w", err)
		}
		removed, err = outcome.RowsAffected()
		return err
	})
	return removed, err
}
