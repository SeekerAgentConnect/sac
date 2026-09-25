package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Restricted feeds (SEE-156, docs/wiki/restricted-feeds.md).
//
// A public feed's publisher knows nothing about who reads it, and the four tables above still hold
// nothing about anybody. A restricted feed's publisher is the party that decides who may read, so it
// has to know what it decided about: a wallet that proved it controls itself, a device key that
// wallet bound, the decision, the one-use invitation that carries it to the device, and the gateway
// grant it became. Those are the four tables here, and they are the only place in this file a wallet
// appears. There is still no column for a signal's outcome, an amount, a transaction or anything an
// owner decided about a signal: what happens after a device reads a signal stays on that device.
//
// Every write that changes a decision is one transaction, so a revocation and the redemption it
// races cannot both win, and a redemption is consumed exactly once.

// Version 4 is restricted-feed access.
const schemaV4 = `
-- One wallet-ownership challenge: fresh, expiring, used at most once, and bound to the wallet,
-- the device key and the attempt it was issued for. The message the wallet signs is rebuilt from
-- these columns, never stored as text somebody else wrote.
CREATE TABLE access_challenge (
  challenge_id  TEXT PRIMARY KEY,
  wallet        TEXT NOT NULL,
  device_key    BLOB NOT NULL,
  installation  TEXT NOT NULL,
  label         TEXT NOT NULL,
  nonce         TEXT NOT NULL,
  issued_at_ms  INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  used_at_ms    INTEGER
);
CREATE INDEX access_challenge_by_expiry ON access_challenge(expires_at_ms);

-- One device a wallet asked access for, and the publisher's decision about it. The label is the
-- phone's own name for itself, shown as exactly that; the installation is the fingerprint of the
-- device key, which is what the wallet's signature bound. state is pending, approved, rejected or
-- revoked. A second device of the same wallet is a second row with its own decision.
CREATE TABLE access_device (
  device_id       TEXT PRIMARY KEY,
  wallet          TEXT NOT NULL,
  installation    TEXT NOT NULL,
  device_key      BLOB NOT NULL,
  label           TEXT NOT NULL,
  subscriber_ref  TEXT NOT NULL,
  state           TEXT NOT NULL,
  requested_at_ms INTEGER NOT NULL,
  decided_at_ms   INTEGER,
  decided_by      TEXT NOT NULL DEFAULT '',
  revoked_at_ms   INTEGER
);
CREATE INDEX access_device_by_wallet ON access_device(wallet);
-- At most one live request or approval per wallet and device key; a rejected or revoked one stays
-- as history and a new request is a new row.
CREATE UNIQUE INDEX access_device_live
  ON access_device(wallet, installation) WHERE state IN ('pending', 'approved');

-- A single-use invitation that carries an approval to the one device it is for. It is kept as
-- written, because the phone polls for it and the operator's page shows it as a link: it is a
-- five-minute capability that does nothing without the device key it is bound to.
CREATE TABLE access_invitation (
  token            TEXT PRIMARY KEY,
  device_id        TEXT NOT NULL REFERENCES access_device(device_id) ON DELETE CASCADE,
  issued_at_ms     INTEGER NOT NULL,
  expires_at_ms    INTEGER NOT NULL,
  used_at_ms       INTEGER,
  superseded_at_ms INTEGER
);
CREATE INDEX access_invitation_by_device ON access_invitation(device_id);

-- The gateway grant a redeemed invitation became, and whether the gateway has been told. It is an
-- outbox, like a signal: revision is what this publisher wants the gateway to hold, and
-- synced_revision is what the gateway confirmed. Only the session's digest is kept — the session
-- itself went to the phone once and exists nowhere else.
CREATE TABLE access_grant (
  grant_id        TEXT PRIMARY KEY,
  device_id       TEXT NOT NULL REFERENCES access_device(device_id) ON DELETE CASCADE,
  session_digest  BLOB NOT NULL UNIQUE,
  state           TEXT NOT NULL,
  created_at_ms   INTEGER NOT NULL,
  expires_at_ms   INTEGER NOT NULL,
  revoked_at_ms   INTEGER,
  revision        INTEGER NOT NULL,
  synced_revision INTEGER NOT NULL,
  attempts        INTEGER NOT NULL,
  due_at_ms       INTEGER NOT NULL,
  sync_error      TEXT NOT NULL,
  synced_at_ms    INTEGER
);
CREATE INDEX access_grant_by_device ON access_grant(device_id);
CREATE INDEX access_grant_pending ON access_grant(due_at_ms) WHERE synced_revision < revision;
`

// Device states.
const (
	DevicePending  = "pending"
	DeviceApproved = "approved"
	DeviceRejected = "rejected"
	DeviceRevoked  = "revoked"
)

// Grant states.
const (
	GrantActive  = "active"
	GrantRevoked = "revoked"
)

var (
	ErrNoChallenge       = errors.New("no such challenge")
	ErrChallengeUsed     = errors.New("that challenge was already used")
	ErrChallengeExpired  = errors.New("that challenge expired")
	ErrNoDevice          = errors.New("no such device")
	ErrDeviceState       = errors.New("the device is not in a state that allows this")
	ErrNoInvitation      = errors.New("no such invitation")
	ErrInvitationUsed    = errors.New("that invitation was already used")
	ErrInvitationExpired = errors.New("that invitation expired")
	ErrInvitationStale   = errors.New("that invitation was replaced by a newer one")
)

// AccessChallenge is one issued challenge.
type AccessChallenge struct {
	ID           string
	Wallet       string
	DeviceKey    []byte
	Installation string
	Label        string
	Nonce        string
	IssuedAt     time.Time
	ExpiresAt    time.Time
}

// AccessDevice is one device's request and the decision about it, with its newest invitation and
// grant.
type AccessDevice struct {
	ID            string
	Wallet        string
	Installation  string
	DeviceKey     []byte
	Label         string
	SubscriberRef string
	State         string
	RequestedAt   time.Time
	DecidedAt     *time.Time
	DecidedBy     string
	RevokedAt     *time.Time
	Invitation    *AccessInvitation
	Grant         *AccessGrant
}

// AccessInvitation is a device's newest invitation.
type AccessInvitation struct {
	Token        string
	IssuedAt     time.Time
	ExpiresAt    time.Time
	UsedAt       *time.Time
	SupersededAt *time.Time
}

// Live says whether the invitation may still be redeemed at this instant.
func (i AccessInvitation) Live(at time.Time) bool {
	return i.UsedAt == nil && i.SupersededAt == nil && at.Before(i.ExpiresAt)
}

// AccessGrant is a device's newest gateway grant and its synchronization state.
type AccessGrant struct {
	ID             string
	DeviceID       string
	SessionDigest  []byte
	State          string
	CreatedAt      time.Time
	ExpiresAt      time.Time
	RevokedAt      *time.Time
	Revision       uint64
	SyncedRevision uint64
	Attempts       int
	SyncError      string
	SyncedAt       *time.Time
	// For the gateway call: the device's references, carried so the syncer needs no second read.
	SubscriberRef string
	Installation  string
}

// Synced says whether the gateway has confirmed what this publisher wants it to hold.
func (g AccessGrant) Synced() bool { return g.SyncedRevision >= g.Revision }

// NewGrant is what a redemption asks the store to record.
type NewGrant struct {
	ID            string
	SessionDigest []byte
	ExpiresAt     time.Time
}

// PutChallenge records one issued challenge.
func (s *Store) PutChallenge(ctx context.Context, challenge AccessChallenge) error {
	_, err := s.writer.ExecContext(ctx,
		`INSERT INTO access_challenge (challenge_id, wallet, device_key, installation, label, nonce,
		                               issued_at_ms, expires_at_ms, used_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
		challenge.ID, challenge.Wallet, challenge.DeviceKey, challenge.Installation,
		challenge.Label, challenge.Nonce, milliseconds(challenge.IssuedAt),
		milliseconds(challenge.ExpiresAt))
	if err != nil {
		return fmt.Errorf("record challenge: %w", err)
	}
	return nil
}

// Decision is what an eligibility rule answered for a proven wallet and device.
type Decision int

const (
	// Undecided leaves the request pending for the operator.
	Undecided Decision = iota
	// Eligible approves it at once.
	Eligible
	// Ineligible rejects it at once.
	Ineligible
)

// Challenge reads one issued challenge, whether or not it was used.
func (s *Store) Challenge(ctx context.Context, challengeID string) (*AccessChallenge, bool, error) {
	var (
		challenge       AccessChallenge
		issued, expires int64
		used            sql.NullInt64
	)
	err := s.reader.QueryRowContext(ctx,
		`SELECT challenge_id, wallet, device_key, installation, label, nonce, issued_at_ms,
		        expires_at_ms, used_at_ms
		   FROM access_challenge WHERE challenge_id = ?`, challengeID).
		Scan(&challenge.ID, &challenge.Wallet, &challenge.DeviceKey, &challenge.Installation,
			&challenge.Label, &challenge.Nonce, &issued, &expires, &used)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, false, ErrNoChallenge
	case err != nil:
		return nil, false, fmt.Errorf("read challenge: %w", err)
	}
	challenge.IssuedAt, challenge.ExpiresAt = instant(issued), instant(expires)
	return &challenge, used.Valid, nil
}

// SpendChallenge consumes a challenge without recording anything, for an answer that failed
// verification: a challenge is one attempt, whatever the attempt was.
func (s *Store) SpendChallenge(ctx context.Context, challengeID string, at time.Time) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE access_challenge SET used_at_ms = ? WHERE challenge_id = ? AND used_at_ms IS NULL`,
		milliseconds(at), challengeID)
	return err
}

// RequestAccess consumes a verified challenge and records the device it proved, in one
// transaction. The consumption is conditional, so of two answers to one challenge exactly one
// records anything. A wallet and device key that already have a live request or approval get that
// record back rather than a second one. invitation is called only when the decision approves.
func (s *Store) RequestAccess(
	ctx context.Context,
	challenge AccessChallenge,
	at time.Time,
	deviceID string,
	subscriberRef string,
	decision Decision,
	invitation func() (string, time.Time),
) (*AccessDevice, error) {
	var recorded string
	err := s.inTransaction(ctx, func(transaction *sql.Tx) error {
		consumed, err := transaction.ExecContext(ctx,
			`UPDATE access_challenge SET used_at_ms = ?
			  WHERE challenge_id = ? AND used_at_ms IS NULL AND expires_at_ms > ?`,
			milliseconds(at), challenge.ID, milliseconds(at))
		if err != nil {
			return fmt.Errorf("consume challenge: %w", err)
		}
		if changed, err := consumed.RowsAffected(); err != nil {
			return err
		} else if changed != 1 {
			return ErrChallengeUsed
		}
		existing, err := liveDevice(ctx, transaction, challenge.Wallet, challenge.Installation)
		if err != nil {
			return err
		}
		if existing != "" {
			recorded = existing
			return nil
		}
		reference := subscriberRef
		if held, err := subscriberOf(ctx, transaction, challenge.Wallet); err != nil {
			return err
		} else if held != "" {
			reference = held
		}
		state := DevicePending
		var decided any
		switch decision {
		case Eligible:
			state, decided = DeviceApproved, milliseconds(at)
		case Ineligible:
			state, decided = DeviceRejected, milliseconds(at)
		}
		if _, err := transaction.ExecContext(ctx,
			`INSERT INTO access_device (device_id, wallet, installation, device_key, label,
			                            subscriber_ref, state, requested_at_ms, decided_at_ms,
			                            decided_by, revoked_at_ms)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
			deviceID, challenge.Wallet, challenge.Installation, challenge.DeviceKey,
			challenge.Label, reference, state, milliseconds(at), decided,
			byRule(decision)); err != nil {
			return fmt.Errorf("record device: %w", err)
		}
		recorded = deviceID
		if state == DeviceApproved {
			token, until := invitation()
			return issueInvitation(ctx, transaction, deviceID, token, at, until)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.Device(ctx, recorded)
}

func byRule(decision Decision) string {
	if decision == Undecided {
		return ""
	}
	return "eligibility rule"
}

func liveDevice(ctx context.Context, transaction *sql.Tx, wallet, installation string) (string, error) {
	var id string
	err := transaction.QueryRowContext(ctx,
		`SELECT device_id FROM access_device
		  WHERE wallet = ? AND installation = ? AND state IN ('pending', 'approved')`,
		wallet, installation).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return id, err
}

// subscriberOf keeps one opaque reference per wallet, so the gateway's view of two devices of one
// wallet agrees without the gateway ever learning the wallet.
func subscriberOf(ctx context.Context, transaction *sql.Tx, wallet string) (string, error) {
	var reference string
	err := transaction.QueryRowContext(ctx,
		`SELECT subscriber_ref FROM access_device WHERE wallet = ? LIMIT 1`, wallet).Scan(&reference)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return reference, err
}

func issueInvitation(ctx context.Context, transaction *sql.Tx, deviceID, token string, at, until time.Time) error {
	// A new invitation supersedes every one before it that was not used, so only the newest can
	// ever be redeemed.
	if _, err := transaction.ExecContext(ctx,
		`UPDATE access_invitation SET superseded_at_ms = ?
		  WHERE device_id = ? AND used_at_ms IS NULL AND superseded_at_ms IS NULL`,
		milliseconds(at), deviceID); err != nil {
		return fmt.Errorf("supersede invitations: %w", err)
	}
	if _, err := transaction.ExecContext(ctx,
		`INSERT INTO access_invitation (token, device_id, issued_at_ms, expires_at_ms, used_at_ms,
		                                superseded_at_ms)
		 VALUES (?, ?, ?, ?, NULL, NULL)`,
		token, deviceID, milliseconds(at), milliseconds(until)); err != nil {
		return fmt.Errorf("issue invitation: %w", err)
	}
	return nil
}

// Decide approves or rejects a pending device. Approving issues its invitation.
func (s *Store) Decide(ctx context.Context, deviceID string, approve bool, by string, at time.Time, invitation func() (string, time.Time)) (*AccessDevice, error) {
	err := s.inTransaction(ctx, func(transaction *sql.Tx) error {
		state, err := stateOf(ctx, transaction, deviceID)
		if err != nil {
			return err
		}
		if state != DevicePending {
			return ErrDeviceState
		}
		next := DeviceRejected
		if approve {
			next = DeviceApproved
		}
		if _, err := transaction.ExecContext(ctx,
			`UPDATE access_device SET state = ?, decided_at_ms = ?, decided_by = ? WHERE device_id = ?`,
			next, milliseconds(at), by, deviceID); err != nil {
			return fmt.Errorf("decide: %w", err)
		}
		if approve {
			token, until := invitation()
			return issueInvitation(ctx, transaction, deviceID, token, at, until)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.Device(ctx, deviceID)
}

// Reissue gives an approved device a fresh invitation and supersedes the old one. It is refused for
// any device that is not approved right now, so a reissue can never undo a revocation.
func (s *Store) Reissue(ctx context.Context, deviceID string, at time.Time, invitation func() (string, time.Time)) (*AccessDevice, error) {
	err := s.inTransaction(ctx, func(transaction *sql.Tx) error {
		state, err := stateOf(ctx, transaction, deviceID)
		if err != nil {
			return err
		}
		if state != DeviceApproved {
			return ErrDeviceState
		}
		token, until := invitation()
		return issueInvitation(ctx, transaction, deviceID, token, at, until)
	})
	if err != nil {
		return nil, err
	}
	return s.Device(ctx, deviceID)
}

// Revoke ends one device's access: its state, every invitation it has not used, and every grant it
// holds, which become revocations for the gateway to confirm. A pending device is revoked as well,
// so a decision that has not been made yet cannot be made later by mistake.
func (s *Store) Revoke(ctx context.Context, deviceID string, at time.Time) error {
	return s.inTransaction(ctx, func(transaction *sql.Tx) error {
		state, err := stateOf(ctx, transaction, deviceID)
		if err != nil {
			return err
		}
		if state != DevicePending && state != DeviceApproved {
			return ErrDeviceState
		}
		return revokeDevice(ctx, transaction, deviceID, at)
	})
}

// RevokeWallet revokes every device of one wallet that is pending or approved, and answers how
// many.
func (s *Store) RevokeWallet(ctx context.Context, wallet string, at time.Time) (int, error) {
	revoked := 0
	err := s.inTransaction(ctx, func(transaction *sql.Tx) error {
		rows, err := transaction.QueryContext(ctx,
			`SELECT device_id FROM access_device
			  WHERE wallet = ? AND state IN ('pending', 'approved') ORDER BY device_id`, wallet)
		if err != nil {
			return err
		}
		var ids []string
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				_ = rows.Close()
				return err
			}
			ids = append(ids, id)
		}
		if err := rows.Close(); err != nil {
			return err
		}
		for _, id := range ids {
			if err := revokeDevice(ctx, transaction, id, at); err != nil {
				return err
			}
			revoked++
		}
		return nil
	})
	return revoked, err
}

func revokeDevice(ctx context.Context, transaction *sql.Tx, deviceID string, at time.Time) error {
	if _, err := transaction.ExecContext(ctx,
		`UPDATE access_device SET state = 'revoked', revoked_at_ms = ? WHERE device_id = ?`,
		milliseconds(at), deviceID); err != nil {
		return fmt.Errorf("revoke device: %w", err)
	}
	if _, err := transaction.ExecContext(ctx,
		`UPDATE access_invitation SET superseded_at_ms = ?
		  WHERE device_id = ? AND used_at_ms IS NULL AND superseded_at_ms IS NULL`,
		milliseconds(at), deviceID); err != nil {
		return fmt.Errorf("supersede invitations: %w", err)
	}
	return revokeGrants(ctx, transaction, `device_id = ?`, deviceID, at)
}

// revokeGrants turns active grants into revocations the gateway still has to confirm. The revision
// moves, so the syncer sends the revocation even if the grant it replaces was never confirmed.
func revokeGrants(ctx context.Context, transaction *sql.Tx, where string, argument any, at time.Time) error {
	if _, err := transaction.ExecContext(ctx,
		`UPDATE access_grant SET state = 'revoked', revoked_at_ms = ?, revision = revision + 1,
		        attempts = 0, due_at_ms = ?, sync_error = ''
		  WHERE state = 'active' AND `+where,
		milliseconds(at), milliseconds(at), argument); err != nil {
		return fmt.Errorf("revoke grants: %w", err)
	}
	return nil
}

// Redeem consumes an invitation and records the grant it becomes, in one transaction.
//
// verify is called inside the transaction with the device the invitation is for, and must check the
// device-key signature — the binding that makes a copied invitation useless. The approval, the
// expiry and the single use are checked here, against the stored state, after verify and before
// the invitation is consumed. A previous grant of the same device is revoked, so one device holds
// one live session.
func (s *Store) Redeem(ctx context.Context, token string, at time.Time, verify func(AccessDevice) error, grant NewGrant) (*AccessDevice, error) {
	var deviceID string
	err := s.inTransaction(ctx, func(transaction *sql.Tx) error {
		var (
			invitation       AccessInvitation
			issued, expires  int64
			used, superseded sql.NullInt64
		)
		err := transaction.QueryRowContext(ctx,
			`SELECT device_id, issued_at_ms, expires_at_ms, used_at_ms, superseded_at_ms
			   FROM access_invitation WHERE token = ?`, token).
			Scan(&deviceID, &issued, &expires, &used, &superseded)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			return ErrNoInvitation
		case err != nil:
			return fmt.Errorf("read invitation: %w", err)
		}
		invitation.ExpiresAt = instant(expires)
		device, err := deviceIn(ctx, transaction, deviceID)
		if err != nil {
			return err
		}
		if err := verify(*device); err != nil {
			return err
		}
		switch {
		case used.Valid:
			return ErrInvitationUsed
		case superseded.Valid:
			return ErrInvitationStale
		case !at.Before(invitation.ExpiresAt):
			return ErrInvitationExpired
		case device.State != DeviceApproved:
			// Revoked or never approved: the invitation carried a decision that no longer stands.
			return ErrDeviceState
		}
		consumed, err := transaction.ExecContext(ctx,
			`UPDATE access_invitation SET used_at_ms = ? WHERE token = ? AND used_at_ms IS NULL`,
			milliseconds(at), token)
		if err != nil {
			return fmt.Errorf("consume invitation: %w", err)
		}
		if changed, err := consumed.RowsAffected(); err != nil {
			return err
		} else if changed != 1 {
			return ErrInvitationUsed
		}
		if err := revokeGrants(ctx, transaction, `device_id = ?`, deviceID, at); err != nil {
			return err
		}
		if _, err := transaction.ExecContext(ctx,
			`INSERT INTO access_grant (grant_id, device_id, session_digest, state, created_at_ms,
			                           expires_at_ms, revoked_at_ms, revision, synced_revision,
			                           attempts, due_at_ms, sync_error, synced_at_ms)
			 VALUES (?, ?, ?, 'active', ?, ?, NULL, 1, 0, 0, ?, '', NULL)`,
			grant.ID, deviceID, grant.SessionDigest, milliseconds(at),
			milliseconds(grant.ExpiresAt), milliseconds(at)); err != nil {
			return fmt.Errorf("record grant: %w", err)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.Device(ctx, deviceID)
}

// Device is one device with its newest invitation and grant.
func (s *Store) Device(ctx context.Context, deviceID string) (*AccessDevice, error) {
	return deviceIn(ctx, s.reader, deviceID)
}

type queryer interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

const deviceColumns = `SELECT device_id, wallet, installation, device_key, label, subscriber_ref,
	        state, requested_at_ms, decided_at_ms, decided_by, revoked_at_ms FROM access_device`

type rowScanner interface{ Scan(into ...any) error }

func scanDevice(from rowScanner) (AccessDevice, error) {
	var (
		device           AccessDevice
		requested        int64
		decided, revoked sql.NullInt64
	)
	err := from.Scan(&device.ID, &device.Wallet, &device.Installation, &device.DeviceKey,
		&device.Label, &device.SubscriberRef, &device.State, &requested, &decided,
		&device.DecidedBy, &revoked)
	if err != nil {
		return device, err
	}
	device.RequestedAt = instant(requested)
	device.DecidedAt = optionalInstant(decided)
	device.RevokedAt = optionalInstant(revoked)
	return device, nil
}

func optionalInstant(value sql.NullInt64) *time.Time {
	if !value.Valid {
		return nil
	}
	at := instant(value.Int64)
	return &at
}

func deviceIn(ctx context.Context, from queryer, deviceID string) (*AccessDevice, error) {
	device, err := scanDevice(from.QueryRowContext(ctx, deviceColumns+` WHERE device_id = ?`, deviceID))
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, ErrNoDevice
	case err != nil:
		return nil, fmt.Errorf("read device: %w", err)
	}
	if err := attach(ctx, from, &device); err != nil {
		return nil, err
	}
	return &device, nil
}

// attach reads a device's newest invitation and newest grant.
func attach(ctx context.Context, from queryer, device *AccessDevice) error {
	var (
		invitation       AccessInvitation
		issued, expires  int64
		used, superseded sql.NullInt64
	)
	err := from.QueryRowContext(ctx,
		`SELECT token, issued_at_ms, expires_at_ms, used_at_ms, superseded_at_ms
		   FROM access_invitation WHERE device_id = ?
		  ORDER BY issued_at_ms DESC, rowid DESC LIMIT 1`, device.ID).
		Scan(&invitation.Token, &issued, &expires, &used, &superseded)
	switch {
	case errors.Is(err, sql.ErrNoRows):
	case err != nil:
		return fmt.Errorf("read invitation: %w", err)
	default:
		invitation.IssuedAt, invitation.ExpiresAt = instant(issued), instant(expires)
		invitation.UsedAt, invitation.SupersededAt = optionalInstant(used), optionalInstant(superseded)
		device.Invitation = &invitation
	}
	grant, err := scanGrant(from.QueryRowContext(ctx,
		grantColumns+` WHERE g.device_id = ? ORDER BY g.created_at_ms DESC, g.rowid DESC LIMIT 1`,
		device.ID))
	switch {
	case errors.Is(err, sql.ErrNoRows):
	case err != nil:
		return fmt.Errorf("read grant: %w", err)
	default:
		device.Grant = &grant
	}
	return nil
}

const grantColumns = `SELECT g.grant_id, g.device_id, g.session_digest, g.state, g.created_at_ms,
	        g.expires_at_ms, g.revoked_at_ms, g.revision, g.synced_revision, g.attempts,
	        g.sync_error, g.synced_at_ms, d.subscriber_ref, d.installation
	   FROM access_grant g JOIN access_device d ON d.device_id = g.device_id`

func scanGrant(from rowScanner) (AccessGrant, error) {
	var (
		grant                    AccessGrant
		created, expires         int64
		revoked, synced          sql.NullInt64
		revision, syncedRevision int64
	)
	err := from.Scan(&grant.ID, &grant.DeviceID, &grant.SessionDigest, &grant.State, &created,
		&expires, &revoked, &revision, &syncedRevision, &grant.Attempts, &grant.SyncError, &synced,
		&grant.SubscriberRef, &grant.Installation)
	if err != nil {
		return grant, err
	}
	grant.CreatedAt, grant.ExpiresAt = instant(created), instant(expires)
	grant.RevokedAt, grant.SyncedAt = optionalInstant(revoked), optionalInstant(synced)
	grant.Revision, grant.SyncedRevision = uint64(revision), uint64(syncedRevision)
	return grant, nil
}

// Devices lists every device, newest request first, for the operator.
func (s *Store) Devices(ctx context.Context) ([]AccessDevice, error) {
	rows, err := s.reader.QueryContext(ctx, deviceColumns+` ORDER BY requested_at_ms DESC, device_id`)
	if err != nil {
		return nil, fmt.Errorf("list devices: %w", err)
	}
	var devices []AccessDevice
	for rows.Next() {
		device, err := scanDevice(rows)
		if err != nil {
			_ = rows.Close()
			return nil, fmt.Errorf("list devices: %w", err)
		}
		devices = append(devices, device)
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	for index := range devices {
		if err := attach(ctx, s.reader, &devices[index]); err != nil {
			return nil, err
		}
	}
	return devices, nil
}

// GrantsDue is the grants whose gateway state is not confirmed and whose retry is due.
func (s *Store) GrantsDue(ctx context.Context, at time.Time, limit int) ([]AccessGrant, error) {
	rows, err := s.reader.QueryContext(ctx,
		grantColumns+` WHERE g.synced_revision < g.revision AND g.due_at_ms <= ?
		  ORDER BY g.due_at_ms, g.grant_id LIMIT ?`, milliseconds(at), limit)
	if err != nil {
		return nil, fmt.Errorf("read due grants: %w", err)
	}
	defer func() { _ = rows.Close() }()
	var grants []AccessGrant
	for rows.Next() {
		grant, err := scanGrant(rows)
		if err != nil {
			return nil, fmt.Errorf("read due grants: %w", err)
		}
		grants = append(grants, grant)
	}
	return grants, rows.Err()
}

// GrantSynced records that the gateway confirmed a grant's state at this revision. It is
// conditional on the revision, so a revocation written while the grant was in flight stays pending.
func (s *Store) GrantSynced(ctx context.Context, grantID string, revision uint64, at time.Time) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE access_grant SET synced_revision = ?, attempts = 0, sync_error = '', synced_at_ms = ?
		  WHERE grant_id = ? AND revision = ?`,
		int64(revision), milliseconds(at), grantID, int64(revision))
	return err
}

// GrantSyncFailed counts a failed attempt and schedules the next.
func (s *Store) GrantSyncFailed(ctx context.Context, grantID string, due time.Time, reason string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE access_grant SET attempts = attempts + 1, due_at_ms = ?, sync_error = ?
		  WHERE grant_id = ?`,
		milliseconds(due), reason, grantID)
	return err
}

// GrantRevokedByGateway records that the gateway already holds the grant as revoked: a renewal
// can never bring it back, so this side stops asking and says so.
func (s *Store) GrantRevokedByGateway(ctx context.Context, grantID string, at time.Time) error {
	return s.inTransaction(ctx, func(transaction *sql.Tx) error {
		_, err := transaction.ExecContext(ctx,
			`UPDATE access_grant SET state = 'revoked', revoked_at_ms = COALESCE(revoked_at_ms, ?),
			        synced_revision = revision, attempts = 0, sync_error = '', synced_at_ms = ?
			  WHERE grant_id = ?`,
			milliseconds(at), milliseconds(at), grantID)
		return err
	})
}

// Renew moves the expiry of every active grant that runs out within `before` to `until`, as a new
// revision the syncer sends. It answers how many it renewed.
func (s *Store) Renew(ctx context.Context, at time.Time, before time.Duration, until time.Time) (int, error) {
	outcome, err := s.writer.ExecContext(ctx,
		`UPDATE access_grant SET expires_at_ms = ?, revision = revision + 1, due_at_ms = ?
		  WHERE state = 'active' AND expires_at_ms < ? AND synced_revision >= revision
		    AND device_id IN (SELECT device_id FROM access_device WHERE state = 'approved')`,
		milliseconds(until), milliseconds(at), milliseconds(at.Add(before)))
	if err != nil {
		return 0, fmt.Errorf("renew grants: %w", err)
	}
	renewed, err := outcome.RowsAffected()
	return int(renewed), err
}

// SweepChallenges forgets challenges that expired before the instant given.
func (s *Store) SweepChallenges(ctx context.Context, before time.Time) (int64, error) {
	outcome, err := s.writer.ExecContext(ctx,
		`DELETE FROM access_challenge WHERE expires_at_ms < ?`, milliseconds(before))
	if err != nil {
		return 0, err
	}
	return outcome.RowsAffected()
}

func stateOf(ctx context.Context, transaction *sql.Tx, deviceID string) (string, error) {
	var state string
	err := transaction.QueryRowContext(ctx,
		`SELECT state FROM access_device WHERE device_id = ?`, deviceID).Scan(&state)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNoDevice
	}
	return state, err
}

func (s *Store) inTransaction(ctx context.Context, fn func(*sql.Tx) error) error {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = transaction.Rollback() }()
	if err := fn(transaction); err != nil {
		return err
	}
	return transaction.Commit()
}

// DeviceForInvitation is the device an invitation was issued to, whatever the invitation's state.
func (s *Store) DeviceForInvitation(ctx context.Context, token string) (*AccessDevice, error) {
	var deviceID string
	err := s.reader.QueryRowContext(ctx,
		`SELECT device_id FROM access_invitation WHERE token = ?`, token).Scan(&deviceID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoInvitation
	}
	if err != nil {
		return nil, err
	}
	return s.Device(ctx, deviceID)
}
