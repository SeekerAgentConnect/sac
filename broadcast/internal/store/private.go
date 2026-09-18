package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2"
)

var (
	ErrNoInvitation      = errors.New("no such invitation")
	ErrInvitationExpired = errors.New("invitation expired")
	ErrInvitationRevoked = errors.New("invitation revoked")
	ErrInvitationUsed    = errors.New("invitation already used")
	ErrNoBinding         = errors.New("no active binding")
	ErrNoPrivateRequest  = errors.New("no such private request")
	ErrResultConflict    = errors.New("a different result is already held")
)

// Invitation is the gateway's server-facing record. TokenHash is kept only long enough to
// resolve and redeem the capability; the raw token is never written to the database.
type Invitation struct {
	ID           string
	ServerID     string
	UserRef      string
	CreatedAt    time.Time
	ExpiresAt    time.Time
	RevokedAt    *time.Time
	RedeemedAt   *time.Time
	ConnectionID string
}

// Binding is the only durable association gateway-private onboarding creates.
type Binding struct {
	ConnectionID string
	ServerID     string
	UserRef      string
	DeviceName   string
	CreatedAt    time.Time
	RevokedAt    *time.Time
	Sequence     uint64
}

type StoredPrivateRequest struct {
	Document     *requestv2.Request
	ConnectionID string
	Sequence     uint64
	Result       *gatewayv1.DeviceResult
	UpdatedAt    time.Time
}

func (s *Store) CreateInvitation(ctx context.Context, invitation Invitation, tokenHash []byte) error {
	return s.Write(ctx, func(tx *Tx) error {
		_, err := tx.tx.ExecContext(ctx, `INSERT INTO invitation
		  (invitation_id, token_hash, server_id, user_ref, created_at_ms, expires_at_ms)
		  VALUES (?, ?, ?, ?, ?, ?)`, invitation.ID, tokenHash, invitation.ServerID,
			invitation.UserRef, milliseconds(invitation.CreatedAt), milliseconds(invitation.ExpiresAt))
		if err != nil {
			return fmt.Errorf("create invitation: %w", err)
		}
		return nil
	})
}

func (s *Store) Invitation(ctx context.Context, serverID, invitationID string) (*Invitation, error) {
	return readInvitation(ctx, s.reader,
		`WHERE server_id = ? AND invitation_id = ?`, serverID, invitationID)
}

func (s *Store) InvitationForToken(ctx context.Context, hash []byte) (*Invitation, error) {
	return readInvitation(ctx, s.reader, `WHERE token_hash = ?`, hash)
}

func readInvitation(ctx context.Context, from querier, where string, args ...any) (*Invitation, error) {
	var created, expires int64
	var revoked, redeemed sql.NullInt64
	var connection sql.NullString
	one := &Invitation{}
	err := from.QueryRowContext(ctx, `SELECT invitation_id, server_id, user_ref,
		created_at_ms, expires_at_ms, revoked_at_ms, redeemed_at_ms, connection_id FROM invitation `+where,
		args...).Scan(&one.ID, &one.ServerID, &one.UserRef, &created, &expires, &revoked, &redeemed, &connection)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read invitation: %w", err)
	}
	one.CreatedAt, one.ExpiresAt = instant(created), instant(expires)
	if revoked.Valid {
		at := instant(revoked.Int64)
		one.RevokedAt = &at
	}
	if redeemed.Valid {
		at := instant(redeemed.Int64)
		one.RedeemedAt = &at
	}
	if connection.Valid {
		one.ConnectionID = connection.String
	}
	return one, nil
}

// Redeem atomically consumes one unexpired invitation and creates its binding. All decisions are
// made again inside this transaction: a preview may race another device, but only one can commit.
func (s *Store) Redeem(
	ctx context.Context,
	tokenHash []byte,
	connectionID string,
	credentialHash []byte,
	deviceName string,
	at time.Time,
) (*Invitation, error) {
	var redeemed *Invitation
	err := s.Write(ctx, func(tx *Tx) error {
		one, err := readInvitation(ctx, tx.tx, `WHERE token_hash = ?`, tokenHash)
		if err != nil {
			return err
		}
		if one == nil {
			return ErrNoInvitation
		}
		if one.RedeemedAt != nil {
			return ErrInvitationUsed
		}
		if one.RevokedAt != nil {
			return ErrInvitationRevoked
		}
		if !at.Before(one.ExpiresAt) {
			return ErrInvitationExpired
		}
		if _, err := tx.tx.ExecContext(ctx, `INSERT INTO device_binding
			(connection_id, server_id, user_ref, credential_hash, device_name, created_at_ms)
			VALUES (?, ?, ?, ?, ?, ?)`, connectionID, one.ServerID, one.UserRef,
			credentialHash, deviceName, milliseconds(at)); err != nil {
			return fmt.Errorf("create binding: %w", err)
		}
		outcome, err := tx.tx.ExecContext(ctx, `UPDATE invitation
			SET redeemed_at_ms = ?, connection_id = ?
			WHERE invitation_id = ? AND redeemed_at_ms IS NULL`,
			milliseconds(at), connectionID, one.ID)
		if err != nil {
			return fmt.Errorf("consume invitation: %w", err)
		}
		changed, err := outcome.RowsAffected()
		if err != nil {
			return err
		}
		if changed != 1 {
			return ErrInvitationUsed
		}
		one.ConnectionID = connectionID
		when := at.UTC()
		one.RedeemedAt = &when
		redeemed = one
		return nil
	})
	return redeemed, err
}

// RevokeInvitation permanently closes one pending capability under its authenticated server. It
// is idempotent so a backend that lost the first answer may safely retry, but a completed
// connection has to be revoked as a binding instead.
func (s *Store) RevokeInvitation(
	ctx context.Context,
	serverID, invitationID string,
	at time.Time,
) error {
	return s.Write(ctx, func(tx *Tx) error {
		one, err := readInvitation(ctx, tx.tx,
			`WHERE server_id = ? AND invitation_id = ?`, serverID, invitationID)
		if err != nil {
			return err
		}
		if one == nil {
			return ErrNoInvitation
		}
		if one.RedeemedAt != nil {
			return ErrInvitationUsed
		}
		if one.RevokedAt != nil {
			return nil
		}
		_, err = tx.tx.ExecContext(ctx, `UPDATE invitation SET revoked_at_ms = ?
			WHERE server_id = ? AND invitation_id = ? AND revoked_at_ms IS NULL
			AND redeemed_at_ms IS NULL`, milliseconds(at), serverID, invitationID)
		if err != nil {
			return fmt.Errorf("revoke invitation: %w", err)
		}
		return nil
	})
}

func (s *Store) DeviceFor(ctx context.Context, hash []byte) (*Binding, error) {
	return readBinding(ctx, s.reader,
		`WHERE credential_hash = ? AND revoked_at_ms IS NULL`, hash)
}

func (s *Store) ActiveBinding(
	ctx context.Context,
	serverID, userRef, connectionID string,
) (*Binding, error) {
	return readBinding(ctx, s.reader,
		`WHERE server_id = ? AND user_ref = ? AND connection_id = ? AND revoked_at_ms IS NULL`,
		serverID, userRef, connectionID)
}

func readBinding(ctx context.Context, from querier, where string, args ...any) (*Binding, error) {
	var created int64
	var revoked sql.NullInt64
	var sequence int64
	one := &Binding{}
	err := from.QueryRowContext(ctx, `SELECT connection_id, server_id, user_ref, device_name,
		created_at_ms, revoked_at_ms, sequence FROM device_binding `+where, args...).Scan(
		&one.ConnectionID, &one.ServerID, &one.UserRef, &one.DeviceName, &created, &revoked, &sequence)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read binding: %w", err)
	}
	one.CreatedAt, one.Sequence = instant(created), uint64(sequence)
	if revoked.Valid {
		at := instant(revoked.Int64)
		one.RevokedAt = &at
	}
	return one, nil
}

func (s *Store) RevokeBinding(ctx context.Context, connectionID string, at time.Time) error {
	return s.revokeBinding(ctx, "connection_id = ?", []any{connectionID}, at)
}

// RevokeBindingForServer gives an authenticated origin control of exactly its own binding. It is
// the recovery path when the old device is unavailable and therefore cannot revoke itself.
func (s *Store) RevokeBindingForServer(
	ctx context.Context,
	serverID, connectionID string,
	at time.Time,
) error {
	return s.revokeBinding(ctx, "server_id = ? AND connection_id = ?", []any{serverID, connectionID}, at)
}

func (s *Store) revokeBinding(ctx context.Context, where string, args []any, at time.Time) error {
	return s.Write(ctx, func(tx *Tx) error {
		values := append([]any{milliseconds(at)}, args...)
		outcome, err := tx.tx.ExecContext(ctx, `UPDATE device_binding SET revoked_at_ms = ?
			WHERE `+where+` AND revoked_at_ms IS NULL`, values...)
		if err != nil {
			return fmt.Errorf("revoke binding: %w", err)
		}
		changed, err := outcome.RowsAffected()
		if err != nil {
			return err
		}
		if changed != 1 {
			return ErrNoBinding
		}
		return nil
	})
}

func (s *Store) PrivateRequest(ctx context.Context, serverID, requestID string) (*StoredPrivateRequest, error) {
	return readPrivateRequest(ctx, s.reader, serverID, requestID)
}

func (t *Tx) PrivateRequest(ctx context.Context, serverID, requestID string) (*StoredPrivateRequest, error) {
	return readPrivateRequest(ctx, t.tx, serverID, requestID)
}

func readPrivateRequest(ctx context.Context, from querier, serverID, requestID string) (*StoredPrivateRequest, error) {
	var document, result []byte
	var connection string
	var sequence, updated int64
	err := from.QueryRowContext(ctx, `SELECT document, connection_id, sequence, result, updated_at_ms
		FROM private_request WHERE server_id = ? AND request_id = ?`, serverID, requestID).Scan(
		&document, &connection, &sequence, &result, &updated)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read private request: %w", err)
	}
	request := &requestv2.Request{}
	if err := proto.Unmarshal(document, request); err != nil {
		return nil, fmt.Errorf("read private request %s: %w", requestID, err)
	}
	one := &StoredPrivateRequest{Document: request, ConnectionID: connection,
		Sequence: uint64(sequence), UpdatedAt: instant(updated)}
	if len(result) > 0 {
		one.Result = &gatewayv1.DeviceResult{}
		if err := proto.Unmarshal(result, one.Result); err != nil {
			return nil, fmt.Errorf("read private result %s: %w", requestID, err)
		}
	}
	return one, nil
}

func (t *Tx) PutPrivateRequest(
	ctx context.Context,
	document *requestv2.Request,
	userRef, connectionID string,
	at time.Time,
) (uint64, error) {
	encoded, err := proto.Marshal(document)
	if err != nil {
		return 0, fmt.Errorf("write private request: %w", err)
	}
	var sequence int64
	if err := t.tx.QueryRowContext(ctx, `UPDATE device_binding SET sequence = sequence + 1
		WHERE connection_id = ? AND revoked_at_ms IS NULL RETURNING sequence`, connectionID).Scan(&sequence); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrNoBinding
		}
		return 0, fmt.Errorf("move device sequence: %w", err)
	}
	lifecycle := document.GetLifecycle()
	cancelled := 0
	if lifecycle.GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
		cancelled = 1
	}
	identity := document.GetIdentity()
	_, err = t.tx.ExecContext(ctx, `INSERT INTO private_request
		(server_id, request_id, user_ref, connection_id, revision, cancelled, expires_at_ms,
		 sequence, document, updated_at_ms)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (server_id, request_id) DO UPDATE SET
		 revision = excluded.revision, cancelled = excluded.cancelled,
		 expires_at_ms = excluded.expires_at_ms, sequence = excluded.sequence,
		 document = excluded.document, updated_at_ms = excluded.updated_at_ms`,
		identity.GetSourceId(), identity.GetRequestId(), userRef, connectionID,
		int64(lifecycle.GetRevision()), cancelled, milliseconds(lifecycle.GetExpiresAt().AsTime()),
		sequence, encoded, milliseconds(at))
	if err != nil {
		return 0, fmt.Errorf("write private request: %w", err)
	}
	return uint64(sequence), nil
}

func (s *Store) PrivatePage(ctx context.Context, connectionID, after string, limit int) ([]*StoredPrivateRequest, uint64, error) {
	binding, err := readBinding(ctx, s.reader, `WHERE connection_id = ? AND revoked_at_ms IS NULL`, connectionID)
	if err != nil || binding == nil {
		return nil, 0, err
	}
	rows, err := s.reader.QueryContext(ctx, `SELECT server_id, request_id FROM private_request
		WHERE connection_id = ? AND request_id > ? ORDER BY request_id LIMIT ?`, connectionID, after, limit)
	if err != nil {
		return nil, 0, fmt.Errorf("read private page: %w", err)
	}
	defer rows.Close()
	var page []*StoredPrivateRequest
	for rows.Next() {
		var serverID, requestID string
		if err := rows.Scan(&serverID, &requestID); err != nil {
			return nil, 0, fmt.Errorf("read private page: %w", err)
		}
		one, err := readPrivateRequest(ctx, s.reader, serverID, requestID)
		if err != nil {
			return nil, 0, err
		}
		page = append(page, one)
	}
	return page, binding.Sequence, rows.Err()
}

func (s *Store) PutDeviceResult(
	ctx context.Context,
	serverID, connectionID string,
	result *gatewayv1.DeviceResult,
	at time.Time,
) (bool, error) {
	unchanged := false
	err := s.Write(ctx, func(tx *Tx) error {
		held, err := tx.PrivateRequest(ctx, serverID, result.GetRequestId())
		if err != nil {
			return err
		}
		if held == nil || held.ConnectionID != connectionID {
			return ErrNoPrivateRequest
		}
		if held.Result != nil {
			if proto.Equal(held.Result, result) {
				unchanged = true
				return nil
			}
			return ErrResultConflict
		}
		encoded, err := proto.Marshal(result)
		if err != nil {
			return fmt.Errorf("write device result: %w", err)
		}
		_, err = tx.tx.ExecContext(ctx, `UPDATE private_request SET result = ?, updated_at_ms = ?
			WHERE server_id = ? AND request_id = ? AND result IS NULL`, encoded, milliseconds(at),
			serverID, result.GetRequestId())
		if err != nil {
			return fmt.Errorf("write device result: %w", err)
		}
		return nil
	})
	return unchanged, err
}
