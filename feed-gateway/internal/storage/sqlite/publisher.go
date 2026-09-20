package sqlite

import (
	"context"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Publisher is a registered publisher, as the operator's tool lists one.
type Publisher = storage.Publisher

// Credential is one of a publisher's credentials, without the credential: the ID is a prefix of
// the hash, which is what the operator names to revoke one.
type Credential = storage.Credential

// Registration is what registering a publisher says: an identity, an operator's label, and the
// developer's own host when they gave one.
type Registration = storage.Registration

var (
	// ErrNoPublisher is returned when a server ID has not been registered.
	ErrNoPublisher = storage.ErrNoPublisher
	// ErrPublisherExists is returned when one already has been.
	ErrPublisherExists = storage.ErrPublisherExists
)

// CredentialID is the operator's handle for one credential. The derivation lives in
// internal/credential, so the CLI, the admin surface and this store all name a credential the same
// way; this is the store's own spelling of it.
func CredentialID(hash []byte) string { return credential.ID(hash) }

// Register adds a publisher and gives it its first credential, in one transaction, and answers
// with that credential's ID.
//
// A server ID that is already registered is refused rather than merged into. Adding a credential
// to an existing publisher is rotation, which is AddCredential and says so; doing it under the
// name "register" would let a second registration of the same identity quietly hand out the
// ability to publish as an existing publisher (SEE-141).
func (s *Store) Register(ctx context.Context, registration Registration, hash []byte, at time.Time) (string, error) {
	err := s.write(ctx, func(tx *Tx) error {
		known, err := tx.PublisherExists(ctx, registration.ServerID)
		if err != nil {
			return err
		}
		if known {
			return fmt.Errorf("%w: %s", ErrPublisherExists, registration.ServerID)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO publisher (server_id, label, host, created_at_ms) VALUES (?, ?, ?, ?)`,
			registration.ServerID, registration.Label, registration.Host,
			milliseconds(at)); err != nil {
			return fmt.Errorf("register publisher: %w", err)
		}
		return tx.addCredential(ctx, registration.ServerID, registration.Label, hash, at)
	})
	if err != nil {
		return "", err
	}
	return CredentialID(hash), nil
}

// AddCredential is a rotation: the publisher keeps publishing with what it has while the new
// credential is deployed, and the old one is revoked afterwards. Both work in between, which is
// the whole point of rotation being two steps rather than one.
func (s *Store) AddCredential(ctx context.Context, serverID, label string, hash []byte, at time.Time) (string, error) {
	err := s.write(ctx, func(tx *Tx) error {
		known, err := tx.PublisherExists(ctx, serverID)
		if err != nil {
			return err
		}
		if !known {
			return fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
		}
		return tx.addCredential(ctx, serverID, label, hash, at)
	})
	if err != nil {
		return "", err
	}
	return CredentialID(hash), nil
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
		`SELECT p.server_id, p.label, p.host, p.created_at_ms,
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
		if err := rows.Scan(&publisher.ServerID, &publisher.Label, &publisher.Host, &created,
			&publisher.Active); err != nil {
			return nil, fmt.Errorf("list publishers: %w", err)
		}
		publisher.CreatedAt = instant(created)
		publishers = append(publishers, publisher)
	}
	return publishers, rows.Err()
}

// Publisher is one registration, or nil when the server has not been registered.
func (s *Store) Publisher(ctx context.Context, serverID string) (*Publisher, error) {
	var publisher Publisher
	var created int64
	err := s.reader.QueryRowContext(ctx,
		`SELECT p.server_id, p.label, p.host, p.created_at_ms,
		        (SELECT COUNT(*) FROM publisher_credential c
		          WHERE c.server_id = p.server_id AND c.revoked_at_ms IS NULL)
		   FROM publisher p WHERE p.server_id = ?`, serverID).
		Scan(&publisher.ServerID, &publisher.Label, &publisher.Host, &created, &publisher.Active)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read publisher: %w", err)
	}
	publisher.CreatedAt = instant(created)
	return &publisher, nil
}

// Publications is how many documents a channel currently holds, which is the durable evidence the
// operator's view has that a publisher has published at all. It counts rows and reads none of
// them: nothing about who subscribed is knowable here, because nothing about it is stored.
func (s *Store) Publications(ctx context.Context, channel string) (int, error) {
	var count int
	if err := s.reader.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM proposal WHERE channel = ?`, channel).Scan(&count); err != nil {
		return 0, fmt.Errorf("count publications: %w", err)
	}
	return count, nil
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
