package postgres

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
// internal/credential, so the CLI, the admin surface and both stores name a credential the same
// way; this is this store's own spelling of it.
func CredentialID(hash []byte) string { return credential.ID(hash) }

// Register adds a publisher and gives it its first credential, in one transaction, and answers
// with that credential's ID.
//
// A server ID that is already registered is refused rather than merged into. Adding a credential
// to an existing publisher is rotation, which is AddCredential and says so; doing it under the
// name "register" would let a second registration of the same identity quietly hand out the
// ability to publish as an existing publisher (SEE-141).
func (s *Store) Register(ctx context.Context, registration Registration, capability storage.Capability, hash []byte, at time.Time) (string, error) {
	if !capability.Valid() {
		return "", fmt.Errorf("not a capability: %s", capability)
	}
	if !registration.Access.Valid() {
		return "", fmt.Errorf("not an access policy: %q with origin %q",
			registration.Access.Policy, registration.Access.AuthOrigin)
	}
	err := s.write(ctx, func(tx *Tx) error {
		known, err := tx.PublisherExists(ctx, registration.ServerID)
		if err != nil {
			return err
		}
		if known {
			return fmt.Errorf("%w: %s", ErrPublisherExists, registration.ServerID)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO `+Schema+`.publisher
			   (server_id, label, host, created_at_ms, publishing, relaying, access_policy, auth_origin)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
			registration.ServerID, registration.Label, registration.Host, milliseconds(at),
			registration.Publishing, registration.Relaying,
			string(policyOf(registration.Access)), registration.Access.AuthOrigin); err != nil {
			return fmt.Errorf("register publisher: %w", err)
		}
		return tx.addCredential(ctx, registration.ServerID, registration.Label, capability, hash, at)
	})
	if err != nil {
		return "", err
	}
	return CredentialID(hash), nil
}

// SetCapabilities is the operator turning a capability on or off. It writes no credential and
// revokes none: what a server may do and what a credential is are separate facts, so a capability
// switched off and on again finds the same credentials working, while a revoked credential never
// works again. That difference is the whole reason these are two operations.
func (s *Store) SetCapabilities(ctx context.Context, serverID string, publishing, relaying bool) error {
	return s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.publisher SET publishing = $1, relaying = $2 WHERE server_id = $3`,
			publishing, relaying, serverID)
		if err != nil {
			return fmt.Errorf("set capabilities: %w", err)
		}
		changed, err := outcome.RowsAffected()
		if err != nil {
			return err
		}
		if changed == 0 {
			return fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
		}
		return nil
	})
}

// AddCredential is a rotation: the publisher keeps publishing with what it has while the new
// credential is deployed, and the old one is revoked afterwards. Both work in between, which is
// the whole point of rotation being two steps rather than one.
func (s *Store) AddCredential(ctx context.Context, serverID, label string, capability storage.Capability, hash []byte, at time.Time) (string, error) {
	if !capability.Valid() {
		return "", fmt.Errorf("not a capability: %s", capability)
	}
	err := s.write(ctx, func(tx *Tx) error {
		known, err := tx.PublisherExists(ctx, serverID)
		if err != nil {
			return err
		}
		if !known {
			return fmt.Errorf("%w: %s", ErrNoPublisher, serverID)
		}
		return tx.addCredential(ctx, serverID, label, capability, hash, at)
	})
	if err != nil {
		return "", err
	}
	return CredentialID(hash), nil
}

// addCredential writes the capability with the credential rather than deriving it from the server.
// A server may hold both kinds at once, and a credential that carried no capability would have to
// be interpreted by whoever presented it — which is the caller, which is the one party that must
// not decide what its own credential is for (SEE-144).
func (t *Tx) addCredential(ctx context.Context, serverID, label string, capability storage.Capability, hash []byte, at time.Time) error {
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO `+Schema+`.publisher_credential
		   (credential_hash, server_id, label, capability, created_at_ms, revoked_at_ms)
		 VALUES ($1, $2, $3, $4, $5, NULL)`,
		hash, serverID, label, string(capability), milliseconds(at)); err != nil {
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
		// The hash is matched by its own leading bytes, so a truncated ID revokes whatever it
		// uniquely names. substring on a bytea is the byte-wise comparison SQLite's substr makes
		// on a BLOB, which is what keeps an ID meaning the same thing in both stores.
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.publisher_credential SET revoked_at_ms = $1
			 WHERE substring(credential_hash FROM 1 FOR $2) = $3 AND revoked_at_ms IS NULL`,
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
			`UPDATE `+Schema+`.publisher_credential SET revoked_at_ms = $1
			 WHERE server_id = $2 AND revoked_at_ms IS NULL`,
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
		outcome, err := tx.tx.ExecContext(ctx,
			`DELETE FROM `+Schema+`.publisher WHERE server_id = $1`, serverID)
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
			`DELETE FROM ` + Schema + `.channel_sequence WHERE channel = $1`,
			`DELETE FROM ` + Schema + `.notice WHERE channel = $1`,
		} {
			if _, err := tx.tx.ExecContext(ctx, statement, channel); err != nil {
				return fmt.Errorf("forget channel: %w", err)
			}
		}
		return nil
	})
}

// PublisherFor resolves a credential hash to the server it publishes as, or "" when the hash is
// unknown, revoked, issued for something other than publishing, or belongs to a server whose
// publishing the operator has switched off.
//
// Since SEE-144 there are two things a registered server can be given, so the capability is part
// of the condition rather than assumed. A relay credential resolves to nothing here however valid
// it is — the publisher API cannot be reached with one, and there is no branch anywhere that could
// decide otherwise, because the query does not return a row.
func (s *Store) PublisherFor(ctx context.Context, hash []byte) (string, error) {
	var serverID string
	err := s.reader.QueryRowContext(ctx,
		`SELECT c.server_id FROM `+Schema+`.publisher_credential c
		   JOIN `+Schema+`.publisher p ON p.server_id = c.server_id
		  WHERE c.credential_hash = $1 AND c.revoked_at_ms IS NULL
		    AND c.capability = $2 AND p.publishing`,
		hash, string(storage.Publishing)).Scan(&serverID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "", nil
	case err != nil:
		return "", fmt.Errorf("resolve credential: %w", err)
	}
	return serverID, nil
}

// PublisherSeen records that this publisher's own server is running (SEE-150). A check-in for a
// server_id nothing is registered under changes no row and is not an error, and the write only ever
// moves the instant forward: see the SQLite store, where both are argued.
func (s *Store) PublisherSeen(ctx context.Context, serverID string, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		_, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.publisher SET last_seen_at_ms = $1
			  WHERE server_id = $2 AND (last_seen_at_ms IS NULL OR last_seen_at_ms < $1)`,
			milliseconds(at), serverID)
		if err != nil {
			return fmt.Errorf("record a publisher check-in: %w", err)
		}
		return nil
	})
}

// PublisherLastSeen is when this publisher last checked in, or the zero time when it never has —
// which is also what an unregistered server_id answers.
func (s *Store) PublisherLastSeen(ctx context.Context, serverID string) (time.Time, error) {
	var seen sql.NullInt64
	err := s.reader.QueryRowContext(ctx,
		`SELECT last_seen_at_ms FROM `+Schema+`.publisher WHERE server_id = $1`,
		serverID).Scan(&seen)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return time.Time{}, nil
	case err != nil:
		return time.Time{}, fmt.Errorf("read a publisher check-in: %w", err)
	}
	if !seen.Valid {
		return time.Time{}, nil
	}
	return instant(seen.Int64), nil
}

// Publishers lists what is registered, for the operator's tool.
func (s *Store) Publishers(ctx context.Context) ([]Publisher, error) {
	rows, err := s.reader.QueryContext(ctx, publisherColumns+
		` FROM `+Schema+`.publisher p ORDER BY p.created_at_ms, p.server_id`)
	if err != nil {
		return nil, fmt.Errorf("list publishers: %w", err)
	}
	defer rows.Close()
	var publishers []Publisher
	for rows.Next() {
		publisher, err := scanPublisher(rows)
		if err != nil {
			return nil, err
		}
		publishers = append(publishers, publisher)
	}
	return publishers, rows.Err()
}

// Publisher is one registration, or nil when the server has not been registered.
func (s *Store) Publisher(ctx context.Context, serverID string) (*Publisher, error) {
	publisher, err := scanPublisher(s.reader.QueryRowContext(ctx,
		publisherColumns+` FROM `+Schema+`.publisher p WHERE p.server_id = $1`, serverID))
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, err
	}
	return &publisher, nil
}

// publisherColumns is one registration as both readers want it: what the operator recorded, what
// they have enabled, and how many credentials of each kind would be accepted right now. The two
// counts are separate because the two grants are, and a page that showed one number would let a
// relay-only server look like it could publish.
const publisherColumns = `SELECT p.server_id, p.label, p.host, p.created_at_ms,
	        p.publishing, p.relaying,
	        (SELECT COUNT(*) FROM ` + Schema + `.publisher_credential c
	          WHERE c.server_id = p.server_id AND c.revoked_at_ms IS NULL
	            AND c.capability = 'publish'),
	        (SELECT COUNT(*) FROM ` + Schema + `.publisher_credential c
	          WHERE c.server_id = p.server_id AND c.revoked_at_ms IS NULL
	            AND c.capability = 'relay'),
	        p.access_policy, p.auth_origin, p.access_epoch,
	        (SELECT COUNT(*) FROM ` + Schema + `.access_grant g
	          WHERE g.server_id = p.server_id AND g.revoked_at_ms IS NULL
	            AND g.expires_at_ms > (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)`

func scanPublisher(from scanner) (Publisher, error) {
	var (
		publisher Publisher
		created   int64
		policy    string
		epoch     int64
	)
	if err := from.Scan(&publisher.ServerID, &publisher.Label, &publisher.Host, &created,
		&publisher.Publishing, &publisher.Relaying, &publisher.Active,
		&publisher.ActiveRelay, &policy, &publisher.Access.AuthOrigin, &epoch,
		&publisher.Grants); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return publisher, err
		}
		return publisher, fmt.Errorf("read publisher: %w", err)
	}
	publisher.CreatedAt = instant(created)
	publisher.Access.Policy = storage.AccessPolicy(policy)
	publisher.Access.Epoch = uint64(epoch)
	return publisher, nil
}

// Publications is how many documents a channel currently holds, which is the durable evidence the
// operator's view has that a publisher has published at all. It counts rows and reads none of
// them: nothing about who subscribed is knowable here, because nothing about it is stored.
func (s *Store) Publications(ctx context.Context, channel string) (int, error) {
	var count int
	if err := s.reader.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM `+Schema+`.proposal WHERE channel = $1`, channel).Scan(&count); err != nil {
		return 0, fmt.Errorf("count publications: %w", err)
	}
	return count, nil
}

// Credentials lists one publisher's credentials, revoked ones included.
func (s *Store) Credentials(ctx context.Context, serverID string) ([]Credential, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT credential_hash, server_id, label, capability, created_at_ms, revoked_at_ms
		   FROM `+Schema+`.publisher_credential WHERE server_id = $1 ORDER BY created_at_ms`, serverID)
	if err != nil {
		return nil, fmt.Errorf("list credentials: %w", err)
	}
	defer rows.Close()
	var credentials []Credential
	for rows.Next() {
		var (
			hash       []byte
			capability string
			created    int64
			revoked    sql.NullInt64
			one        Credential
		)
		if err := rows.Scan(&hash, &one.ServerID, &one.Label, &capability, &created,
			&revoked); err != nil {
			return nil, fmt.Errorf("list credentials: %w", err)
		}
		one.Capability = storage.Capability(capability)
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

func exists(ctx context.Context, from querier, serverID string) (bool, error) {
	var one int
	err := from.QueryRowContext(ctx,
		`SELECT 1 FROM `+Schema+`.publisher WHERE server_id = $1`, serverID).Scan(&one)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("find publisher: %w", err)
	}
	return true, nil
}
