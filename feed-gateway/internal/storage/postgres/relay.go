package postgres

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// The relay's records (SEE-144). This file is the whole of the gateway's private push routing:
// which installations exist, which servers they authorized, and where an invalidation goes.
//
// Nothing here holds a request, an approval, a signature, a result or anything an owner decided.
// The most private value in it is an installation's current FCM target, which has to be
// recoverable because delivery needs it — so it is never returned by a listing, never rendered on
// an admin page and never written to a log. Everything else that could be presented as a
// credential (an installation's secret, a server's push handle) is kept only as its SHA-256, the
// same way a publishing credential is.
//
// This is also the half of the store that the move off a container-local file was for. A binding
// is an authorization a phone gave once and expects to hold; on ephemeral storage it did not
// survive the deployment it was made against, and the app's reconciliation existed to notice that
// and enroll again. It still does, and now it has less to do.

var (
	// ErrNoInstallation, ErrNoBinding and ErrNotPermitted are the storage contract's.
	ErrNoInstallation = storage.ErrNoInstallation
	ErrNoBinding      = storage.ErrNoBinding
	ErrNotPermitted   = storage.ErrNotPermitted
)

// RelayServerFor resolves a relay credential to the server it may wake devices for.
//
// It is a second resolver beside PublisherFor rather than a flag on the first, because the two are
// separate grants over separate data. A credential issued for publishing resolves to nothing here
// however valid it is, and a relay credential resolves to nothing in the publisher API — so the
// separation is a property of the queries rather than of a check somebody has to remember.
//
// The server's own capability is part of the condition. An operator who disables relay for a
// server stops every credential it holds from the next call, without revoking any of them, and
// enabling it again makes the same ones work.
func (s *Store) RelayServerFor(ctx context.Context, hash []byte) (string, error) {
	var serverID string
	err := s.reader.QueryRowContext(ctx,
		`SELECT c.server_id FROM `+Schema+`.publisher_credential c
		   JOIN `+Schema+`.publisher p ON p.server_id = c.server_id
		  WHERE c.credential_hash = $1 AND c.revoked_at_ms IS NULL
		    AND c.capability = $2 AND p.relaying`,
		hash, string(storage.Relaying)).Scan(&serverID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "", nil
	case err != nil:
		return "", fmt.Errorf("resolve relay credential: %w", err)
	}
	return serverID, nil
}

// --- installations ------------------------------------------------------------

// Enroll records a new installation and its first target.
//
// The identity and the secret are minted by the caller and the secret is stored only as its hash,
// so the gateway cannot hand anyone the ability to act as this installation — not an operator
// reading the database, not a backup, not a log. Whoever holds the secret is the installation, and
// the only moment it exists in full is the answer that created it.
func (s *Store) Enroll(ctx context.Context, installationID string, secretHash []byte, target string, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO `+Schema+`.relay_installation
			   (installation_id, secret_hash, target, created_at_ms, seen_at_ms)
			 VALUES ($1, $2, $3, $4, $5)`,
			installationID, secretHash, target, milliseconds(at), milliseconds(at)); err != nil {
			return fmt.Errorf("enroll installation: %w", err)
		}
		return nil
	})
}

// SetTarget replaces an installation's target, having proved ownership in the same transaction.
//
// This is the rule the whole design rests on: knowing or submitting a device's FCM target is not
// authorization to change where that device's wake-ups go. The secret is, and only the device that
// enrolled has it. The same call is the renewal — an installation that keeps its target current
// keeps being seen, and the sweep bounds the ones that stop.
func (s *Store) SetTarget(ctx context.Context, installationID string, secretHash []byte, target string, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_installation SET target = $1, seen_at_ms = $2
			  WHERE installation_id = $3 AND secret_hash = $4`,
			target, milliseconds(at), installationID, secretHash)
		if err != nil {
			return fmt.Errorf("replace target: %w", err)
		}
		return one(outcome, ErrNoInstallation)
	})
}

// Installation is the authenticated read an app makes to find out whether this gateway still holds
// its enrollment. It is how a phone discovers that the gateway's database was lost — the answer is
// ErrNoInstallation, and re-enrollment follows — and it never returns the target.
func (s *Store) Installation(ctx context.Context, installationID string, secretHash []byte) (*storage.RelayInstallation, error) {
	var (
		held          storage.RelayInstallation
		created, seen int64
		target        string
	)
	err := s.reader.QueryRowContext(ctx,
		`SELECT installation_id, target, created_at_ms, seen_at_ms
		   FROM `+Schema+`.relay_installation WHERE installation_id = $1 AND secret_hash = $2`,
		installationID, secretHash).Scan(&held.ID, &target, &created, &seen)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, ErrNoInstallation
	case err != nil:
		return nil, fmt.Errorf("read installation: %w", err)
	}
	held.CreatedAt, held.SeenAt, held.HasTarget = instant(created), instant(seen), target != ""
	return &held, nil
}

// ForgetInstallation removes an installation and, through the foreign key, every binding it
// authorized. It is what "disconnect everything" does on the phone, and it is proved the same way
// as a target replacement: without the secret it does nothing at all.
func (s *Store) ForgetInstallation(ctx context.Context, installationID string, secretHash []byte) error {
	return s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`DELETE FROM `+Schema+`.relay_installation WHERE installation_id = $1 AND secret_hash = $2`,
			installationID, secretHash)
		if err != nil {
			return fmt.Errorf("forget installation: %w", err)
		}
		return one(outcome, ErrNoInstallation)
	})
}

// --- bindings -----------------------------------------------------------------

// Bind is the authorization itself: this installation agrees that this registered server may wake
// it, for one of the connections the phone holds.
//
// Three things are checked in the one transaction that writes it, because a check on the other
// side of a wait is not a check:
//
//   - the caller is the installation (its secret),
//   - the server is registered and the operator has enabled relay for it (ErrNotPermitted),
//   - any binding this installation already had for the same server and connection is revoked,
//
// so a rebinding — after a reinstall, a reconnection, or a gateway that lost its database —
// replaces rather than accumulates, and one phone cannot grow an unbounded set of live handles for
// the same connection.
func (s *Store) Bind(ctx context.Context, request storage.RelayBindingRequest) error {
	return s.write(ctx, func(tx *Tx) error {
		if err := tx.owns(ctx, request.InstallationID, request.SecretHash); err != nil {
			return err
		}
		var relaying bool
		err := tx.tx.QueryRowContext(ctx,
			`SELECT relaying FROM `+Schema+`.publisher WHERE server_id = $1`,
			request.ServerID).Scan(&relaying)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			// The same answer as a server whose relay is switched off. A phone may learn that this
			// binding was refused, never whether the gateway has heard of the server it named.
			return ErrNotPermitted
		case err != nil:
			return fmt.Errorf("read server capability: %w", err)
		case !relaying:
			return ErrNotPermitted
		}
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_binding SET revoked_at_ms = $1
			  WHERE installation_id = $2 AND server_id = $3 AND connection_ref = $4
			    AND revoked_at_ms IS NULL`,
			milliseconds(request.CreatedAt), request.InstallationID, request.ServerID,
			request.Connection); err != nil {
			return fmt.Errorf("replace binding: %w", err)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO `+Schema+`.relay_binding
			   (handle_hash, binding_id, installation_id, server_id, connection_ref,
			    created_at_ms, expires_at_ms, revoked_at_ms, sends, last_sent_at_ms)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, NULL, 0, NULL)`,
			request.HandleHash, request.HandleID, request.InstallationID, request.ServerID,
			request.Connection, milliseconds(request.CreatedAt),
			milliseconds(request.ExpiresAt)); err != nil {
			return fmt.Errorf("authorize binding: %w", err)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_installation SET seen_at_ms = $1 WHERE installation_id = $2`,
			milliseconds(request.CreatedAt), request.InstallationID); err != nil {
			return fmt.Errorf("mark installation seen: %w", err)
		}
		return nil
	})
}

// Unbind revokes one of this installation's bindings. It is what removing a connection does, and
// it is idempotent: revoking one that is already revoked says so rather than failing, because the
// phone retries this call and a retry must not be an error.
func (s *Store) Unbind(ctx context.Context, installationID string, secretHash []byte, bindingID string, at time.Time) (bool, error) {
	var revoked bool
	err := s.write(ctx, func(tx *Tx) error {
		if err := tx.owns(ctx, installationID, secretHash); err != nil {
			return err
		}
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_binding SET revoked_at_ms = $1
			  WHERE binding_id = $2 AND installation_id = $3 AND revoked_at_ms IS NULL`,
			milliseconds(at), bindingID, installationID)
		if err != nil {
			return fmt.Errorf("revoke binding: %w", err)
		}
		changed, err := outcome.RowsAffected()
		revoked = changed > 0
		return err
	})
	return revoked, err
}

// Bindings lists one installation's bindings, revoked ones included, for the reconciliation an app
// runs when it starts or reconnects. The handles are not in it and cannot be: only their hashes
// were ever stored, so a binding whose handle the phone lost is re-created rather than recovered.
func (s *Store) Bindings(ctx context.Context, installationID string, secretHash []byte) ([]storage.RelayBinding, error) {
	if err := s.owns(ctx, installationID, secretHash); err != nil {
		return nil, err
	}
	rows, err := s.reader.QueryContext(ctx,
		`SELECT binding_id, installation_id, server_id, connection_ref,
		        created_at_ms, expires_at_ms, revoked_at_ms, sends, last_sent_at_ms
		   FROM `+Schema+`.relay_binding WHERE installation_id = $1
		  ORDER BY created_at_ms, binding_id`,
		installationID)
	if err != nil {
		return nil, fmt.Errorf("list bindings: %w", err)
	}
	defer rows.Close()
	var bindings []storage.RelayBinding
	for rows.Next() {
		binding, err := scanBinding(rows)
		if err != nil {
			return nil, err
		}
		bindings = append(bindings, binding)
	}
	return bindings, rows.Err()
}

// --- sending ------------------------------------------------------------------

// TargetFor resolves a presented handle to the device it may wake, for one authenticated server.
//
// The server's identity is part of the query rather than something compared afterwards, so a
// handle that belongs to another server's binding simply does not match: cross-server use, a
// fabricated handle, a revoked one and an expired one are one refusal with one shape, and a server
// learns only that this handle does not authorize it.
//
// A binding whose installation has no target answers ErrNoBinding too. There is nothing to wake,
// and saying which of the two was missing would tell a server something about a device.
func (s *Store) TargetFor(ctx context.Context, serverID string, handleHash []byte, at time.Time) (*storage.RelayTarget, error) {
	var target storage.RelayTarget
	err := s.reader.QueryRowContext(ctx,
		`SELECT b.binding_id, b.installation_id, i.target
		   FROM `+Schema+`.relay_binding b JOIN `+Schema+`.relay_installation i
		     ON i.installation_id = b.installation_id
		  WHERE b.handle_hash = $1 AND b.server_id = $2
		    AND b.revoked_at_ms IS NULL AND b.expires_at_ms > $3 AND i.target <> ''`,
		handleHash, serverID, milliseconds(at)).
		Scan(&target.BindingID, &target.InstallationID, &target.Target)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, ErrNoBinding
	case err != nil:
		return nil, fmt.Errorf("resolve handle: %w", err)
	}
	return &target, nil
}

// Sent records an accepted invalidation. It is a counter for the operator's page: accepting a send
// is asking Firebase, which is not the same as a phone having been woken, and the page says so.
func (s *Store) Sent(ctx context.Context, bindingID string, at time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_binding SET sends = sends + 1, last_sent_at_ms = $1
			  WHERE binding_id = $2`, milliseconds(at), bindingID); err != nil {
			return fmt.Errorf("record send: %w", err)
		}
		return nil
	})
}

// TargetRejected clears a target the push endpoint refused as permanently invalid, and only while
// it is still the one that failed.
//
// The compare is the point. A rejection is news about a send that started some time ago, and a
// phone that rotated its target in between has already registered the new one; clearing
// unconditionally would unregister a device that had just registered, and the phone would learn
// about it only by never being woken again. It is the same compare-and-clear the sidecar does with
// a direct target and the token cache does with an access token.
func (s *Store) TargetRejected(ctx context.Context, installationID, target string) (bool, error) {
	var cleared bool
	err := s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`UPDATE `+Schema+`.relay_installation SET target = ''
			  WHERE installation_id = $1 AND target = $2`, installationID, target)
		if err != nil {
			return fmt.Errorf("clear target: %w", err)
		}
		changed, err := outcome.RowsAffected()
		cleared = changed > 0
		return err
	})
	return cleared, err
}

// --- the operator's view ------------------------------------------------------

// RelayStatus is the aggregate a server's admin page shows: how many devices have authorized this
// server, how many authorizations have ended, how many invalidations were accepted and when the
// last one was.
//
// It is counts and instants by construction. There is no query here that could return a target, a
// handle or an installation identity, so the admin surface cannot show one by accident.
func (s *Store) RelayStatus(ctx context.Context, serverID string) (storage.RelayStatus, error) {
	var (
		status   storage.RelayStatus
		lastSent sql.NullInt64
		lastBind sql.NullInt64
		sends    sql.NullInt64
	)
	err := s.reader.QueryRowContext(ctx,
		`SELECT
		   COUNT(*) FILTER (WHERE revoked_at_ms IS NULL),
		   COUNT(*) FILTER (WHERE revoked_at_ms IS NOT NULL),
		   SUM(sends), MAX(last_sent_at_ms), MAX(created_at_ms)
		 FROM `+Schema+`.relay_binding WHERE server_id = $1`, serverID).
		Scan(&status.Bindings, &status.Revoked, &sends, &lastSent, &lastBind)
	if err != nil {
		return status, fmt.Errorf("read relay status: %w", err)
	}
	status.Sends = sends.Int64
	if lastSent.Valid {
		at := instant(lastSent.Int64)
		status.LastSentAt = &at
	}
	if lastBind.Valid {
		at := instant(lastBind.Int64)
		status.LastBindAt = &at
	}
	return status, nil
}

// SweepRelay bounds what a grant can outlive.
//
// Every rule here is about something that stops happening rather than something that happens. A
// binding past its expiry is gone, because an authorization that nothing renewed is one nobody is
// using — a phone that is still paired rebinds on its next reconciliation. An installation nothing
// has authenticated as since the idle bound is gone with its bindings, because a device that was
// reinstalled, lost or wiped cannot come back and tidy up after itself. An installation that holds
// no binding at all goes sooner, because enrolling needs no credential and the rows it makes
// should not be kept for weeks on the chance that somebody meant it.
//
// Revoked bindings are removed by the same expiry rather than kept as history. The operator's page
// counts them while they are there, and a revocation that has aged out is not a fact anyone acts
// on — unlike a revoked publishing credential, which an operator may want to recognize by ID.
func (s *Store) SweepRelay(ctx context.Context, retention storage.RelayRetention) (int64, error) {
	var removed int64
	err := s.write(ctx, func(tx *Tx) error {
		bindings, err := tx.tx.ExecContext(ctx,
			`DELETE FROM `+Schema+`.relay_binding WHERE expires_at_ms <= $1`,
			milliseconds(retention.Bindings))
		if err != nil {
			return fmt.Errorf("sweep bindings: %w", err)
		}
		// Bindings go first, so an installation that has just lost its last one is unbound by the
		// time the second statement asks — which is what makes the short grace apply to a device
		// that stopped renewing as well as to one that never bound at all.
		installations, err := tx.tx.ExecContext(ctx,
			`DELETE FROM `+Schema+`.relay_installation
			  WHERE seen_at_ms <= $1
			     OR (seen_at_ms <= $2 AND NOT EXISTS (
			           SELECT 1 FROM `+Schema+`.relay_binding b
			            WHERE b.installation_id = relay_installation.installation_id))`,
			milliseconds(retention.Idle), milliseconds(retention.Unbound))
		if err != nil {
			return fmt.Errorf("sweep installations: %w", err)
		}
		gone, err := bindings.RowsAffected()
		if err != nil {
			return err
		}
		forgotten, err := installations.RowsAffected()
		if err != nil {
			return err
		}
		removed = gone + forgotten
		return nil
	})
	return removed, err
}

// --- plumbing -----------------------------------------------------------------

// owns is the ownership proof, as a read inside whichever transaction is about to act on it.
//
// It is deliberately one query with both halves of the identity in it. Looking the installation up
// and comparing the secret afterwards would be the same rule written so that a caller could get it
// wrong, and this one cannot be called with the check left out — every mutating method starts with
// it, inside the transaction that writes.
func (t *Tx) owns(ctx context.Context, installationID string, secretHash []byte) error {
	return proves(ctx, t.tx, installationID, secretHash)
}

func (s *Store) owns(ctx context.Context, installationID string, secretHash []byte) error {
	return proves(ctx, s.reader, installationID, secretHash)
}

func proves(ctx context.Context, from querier, installationID string, secretHash []byte) error {
	var one int
	err := from.QueryRowContext(ctx,
		`SELECT 1 FROM `+Schema+`.relay_installation
		  WHERE installation_id = $1 AND secret_hash = $2`,
		installationID, secretHash).Scan(&one)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return ErrNoInstallation
	case err != nil:
		return fmt.Errorf("prove installation: %w", err)
	}
	return nil
}

func scanBinding(from scanner) (storage.RelayBinding, error) {
	var (
		binding          storage.RelayBinding
		created, expires int64
		revoked, lastAt  sql.NullInt64
	)
	if err := from.Scan(&binding.ID, &binding.InstallationID, &binding.ServerID,
		&binding.Connection, &created, &expires, &revoked, &binding.Sends,
		&lastAt); err != nil {
		return binding, fmt.Errorf("read binding: %w", err)
	}
	binding.CreatedAt, binding.ExpiresAt = instant(created), instant(expires)
	if revoked.Valid {
		at := instant(revoked.Int64)
		binding.RevokedAt = &at
	}
	if lastAt.Valid {
		at := instant(lastAt.Int64)
		binding.LastSentAt = &at
	}
	return binding, nil
}
