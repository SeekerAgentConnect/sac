package sqlite

import (
	"context"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Publisher is a registered publisher, as the operator's tool lists one.
type Publisher = storage.Publisher

// Credential is one of a publisher's credentials, without the credential: the ID is a prefix of
// the hash, which is what the operator names to revoke one.
type Credential = storage.Credential

// ErrNoPublisher is returned when a server ID has not been registered.
var ErrNoPublisher = storage.ErrNoPublisher

// CredentialID is the operator's handle for one credential: the first four bytes of its hash, in
// hex. It is not the credential and cannot be turned back into one, and it is short enough to type
// into a revocation.
func CredentialID(hash []byte) string {
	if len(hash) < 4 {
		return ""
	}
	return hex.EncodeToString(hash[:4])
}

// Register adds a publisher if it is new and gives it a credential. It is the one operation that
// grants the ability to publish, it is not reachable over any network, and the caller — cmd/
// feed-gatewayctl — is the only thing that calls it.
func (s *Store) Register(ctx context.Context, serverID, label string, hash []byte, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO publisher (server_id, label, created_at_ms) VALUES (?, ?, ?)
			 ON CONFLICT (server_id) DO NOTHING`,
			serverID, label, milliseconds(at)); err != nil {
			return fmt.Errorf("register publisher: %w", err)
		}
		return tx.addCredential(ctx, serverID, label, hash, at)
	})
}

// AddCredential is a rotation: the publisher keeps publishing with what it has while the new
// credential is deployed, and the old one is revoked afterwards. Both work in between, which is
// the whole point of rotation being two steps rather than one.
func (s *Store) AddCredential(ctx context.Context, serverID, label string, hash []byte, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		known, err := tx.PublisherExists(ctx, serverID)
		if err != nil {
			return err
		}
		if !known {
			return fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
		}
		return tx.addCredential(ctx, serverID, label, hash, at)
	})
}

func (t *Tx) addCredential(ctx context.Context, serverID, label string, hash []byte, at time.Time) error {
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO publisher_credential
		   (credential_hash, server_id, label, created_at_ms, revoked_at_ms)
		 VALUES (?, ?, ?, ?, NULL)`,
		hash, serverID, label, milliseconds(at)); err != nil {
		return fmt.Errorf("add credential: %w", err)
	}
	return nil
}

// Revoke ends one credential by its ID. A revoked credential is kept rather than deleted, so an
// operator can still see that it existed and when it stopped working.
func (s *Store) Revoke(ctx context.Context, id string, at time.Time) (int64, error) {
	prefix, err := hex.DecodeString(id)
	if err != nil || len(prefix) == 0 {
		return 0, fmt.Errorf("not a credential ID: %s", id)
	}
	var revoked int64
	err = s.write(ctx, func(tx *Tx) error {
		// SQLite compares BLOBs with substr, so this matches the hash by its own prefix rather
		// than by anything derived: a truncated ID revokes whatever it uniquely names.
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE publisher_credential SET revoked_at_ms = ?
			 WHERE substr(credential_hash, 1, ?) = ? AND revoked_at_ms IS NULL`,
			milliseconds(at), len(prefix), prefix)
		if err != nil {
			return fmt.Errorf("revoke credential: %w", err)
		}
		revoked, err = outcome.RowsAffected()
		return err
	})
	return revoked, err
}

// RevokeAll ends every credential a publisher holds. Its documents stay: a publisher that can no
// longer publish is not a publisher whose proposals should vanish from the phones that hold them.
func (s *Store) RevokeAll(ctx context.Context, serverID string, at time.Time) (int64, error) {
	var revoked int64
	err := s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE publisher_credential SET revoked_at_ms = ?
			 WHERE server_id = ? AND revoked_at_ms IS NULL`,
			milliseconds(at), serverID)
		if err != nil {
			return fmt.Errorf("revoke credentials: %w", err)
		}
		revoked, err = outcome.RowsAffected()
		return err
	})
	return revoked, err
}

// Forget removes a publisher and everything it published. It is the operator's deliberate act and
// the only way anything leaves this store besides retention: a phone that holds one of these
// proposals keeps it until the owner removes the feed, because what a phone decided is the phone's
// (SEE-89).
func (s *Store) Forget(ctx context.Context, serverID, channel string) error {
	return s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx, `DELETE FROM publisher WHERE server_id = ?`, serverID)
		if err != nil {
			return fmt.Errorf("forget publisher: %w", err)
		}
		removed, err := outcome.RowsAffected()
		if err != nil {
			return err
		}
		if removed == 0 {
			return fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
		}
		// The manifest, the credentials and the proposals go with the publisher through the
		// foreign keys; the channel's own rows are keyed by channel and are deleted here.
		for _, statement := range []string{
			`DELETE FROM channel_sequence WHERE channel = ?`,
			`DELETE FROM notice WHERE channel = ?`,
		} {
			if _, err := tx.tx.ExecContext(ctx, statement, channel); err != nil {
				return fmt.Errorf("forget channel: %w", err)
			}
		}
		return nil
	})
}

// PublisherFor resolves a credential hash to the server it publishes as, or "" when the hash is
// unknown or revoked. It is the whole of what a credential says: which server, and nothing about
// what may be done to it, because there is only one thing a publisher can do.
func (s *Store) PublisherFor(ctx context.Context, hash []byte) (string, error) {
	var serverID string
	err := s.reader.QueryRowContext(ctx,
		`SELECT server_id FROM publisher_credential
		 WHERE credential_hash = ? AND revoked_at_ms IS NULL`, hash).Scan(&serverID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "", nil
	case err != nil:
		return "", fmt.Errorf("resolve credential: %w", err)
	}
	return serverID, nil
}

// Publishers lists what is registered, for the operator's tool.
func (s *Store) Publishers(ctx context.Context) ([]Publisher, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT p.server_id, p.label, p.created_at_ms,
		        (SELECT COUNT(*) FROM publisher_credential c
		          WHERE c.server_id = p.server_id AND c.revoked_at_ms IS NULL)
		   FROM publisher p ORDER BY p.created_at_ms, p.server_id`)
	if err != nil {
		return nil, fmt.Errorf("list publishers: %w", err)
	}
	defer rows.Close()
	var publishers []Publisher
	for rows.Next() {
		var publisher Publisher
		var created int64
		if err := rows.Scan(&publisher.ServerID, &publisher.Label, &created, &publisher.Active); err != nil {
			return nil, fmt.Errorf("list publishers: %w", err)
		}
		publisher.CreatedAt = instant(created)
		publishers = append(publishers, publisher)
	}
	return publishers, rows.Err()
}

// Credentials lists one publisher's credentials, revoked ones included.
func (s *Store) Credentials(ctx context.Context, serverID string) ([]Credential, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT credential_hash, server_id, label, created_at_ms, revoked_at_ms
		   FROM publisher_credential WHERE server_id = ? ORDER BY created_at_ms`, serverID)
	if err != nil {
		return nil, fmt.Errorf("list credentials: %w", err)
	}
	defer rows.Close()
	var credentials []Credential
	for rows.Next() {
		var (
			hash    []byte
			created int64
			revoked sql.NullInt64
			one     Credential
		)
		if err := rows.Scan(&hash, &one.ServerID, &one.Label, &created, &revoked); err != nil {
			return nil, fmt.Errorf("list credentials: %w", err)
		}
		one.ID = CredentialID(hash)
		one.CreatedAt = instant(created)
		if revoked.Valid {
			at := instant(revoked.Int64)
			one.RevokedAt = &at
		}
		credentials = append(credentials, one)
	}
	return credentials, rows.Err()
}

// PublisherExists says whether a server has been registered. A read for a server that has not been
// is answered as unknown rather than as an empty feed, so a phone is never told that a publisher it
// cannot reach has nothing to propose.
func (s *Store) PublisherExists(ctx context.Context, serverID string) (bool, error) {
	return exists(ctx, s.reader, serverID)
}

// PublisherExists inside a write, for the checks a publication makes first.
func (t *Tx) PublisherExists(ctx context.Context, serverID string) (bool, error) {
	return exists(ctx, t.tx, serverID)
}

type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func exists(ctx context.Context, from querier, serverID string) (bool, error) {
	var one int
	err := from.QueryRowContext(ctx,
		`SELECT 1 FROM publisher WHERE server_id = ?`, serverID).Scan(&one)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("find publisher: %w", err)
	}
	return true, nil
}
