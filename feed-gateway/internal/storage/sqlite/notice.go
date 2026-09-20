package sqlite

import (
	"context"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Notice is one pending fan-out: a document changed, and subscribers have not been told yet.
//
// It carries no content. What a subscriber is sent is the document as it stands when the notice is
// sent (internal/dispatch), which is why a notice that waited through a restart is still worth
// sending and why two publications that have not gone out yet collapse into one row.
type Notice = storage.Notice

// notice writes or replaces the pending notice for one document. It is called only from inside the
// transaction that writes that document, which is the whole of "persist first, fan out second":
// there is no code path that can notify without having committed, and none that can commit without
// leaving a notice behind.
func (t *Tx) notice(
	ctx context.Context,
	channel string,
	kind NoticeKind,
	proposalID string,
	revision, sequence uint64,
	at time.Time,
) error {
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO notice
		   (channel, kind, proposal_id, revision, sequence, created_at_ms, attempts, ready_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, 0, ?)
		 ON CONFLICT (channel, kind, proposal_id) DO UPDATE SET
		   revision = excluded.revision,
		   sequence = excluded.sequence,
		   attempts = 0,
		   ready_at_ms = excluded.ready_at_ms`,
		channel, string(kind), proposalID, int64(revision), int64(sequence),
		milliseconds(at), milliseconds(at)); err != nil {
		return fmt.Errorf("write notice: %w", err)
	}
	return nil
}

// Notices reads the notices that are due, oldest first. A notice that failed is due again after
// its backoff, which is what ready_at_ms holds.
func (s *Store) Notices(ctx context.Context, due time.Time, limit int) ([]Notice, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT id, channel, kind, proposal_id, revision, sequence, attempts FROM notice
		  WHERE ready_at_ms <= ? ORDER BY ready_at_ms, id LIMIT ?`,
		milliseconds(due), limit)
	if err != nil {
		return nil, fmt.Errorf("read notices: %w", err)
	}
	defer rows.Close()
	var notices []Notice
	for rows.Next() {
		var (
			notice           Notice
			kind             string
			revision, number int64
		)
		if err := rows.Scan(&notice.ID, &notice.Channel, &kind, &notice.ProposalID,
			&revision, &number, &notice.Attempts); err != nil {
			return nil, fmt.Errorf("read notices: %w", err)
		}
		notice.Kind = NoticeKind(kind)
		notice.Revision = uint64(revision)
		notice.Sequence = uint64(number)
		notices = append(notices, notice)
	}
	return notices, rows.Err()
}

// NoticeSent removes a notice that was delivered, and says whether it removed one.
//
// It is conditional on the revision for a reason that only shows up under load: a publication can
// land while a notice is in flight, and the row is then about the newer document. Deleting it
// unconditionally would drop that fan-out silently. So the delete matches the revision that was
// sent, and a row that moved stays pending — one more send, with the document as it stands then.
func (s *Store) NoticeSent(ctx context.Context, id int64, revision uint64) (bool, error) {
	var removed int64
	err := s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`DELETE FROM notice WHERE id = ? AND revision = ?`, id, int64(revision))
		if err != nil {
			return fmt.Errorf("finish notice: %w", err)
		}
		removed, err = outcome.RowsAffected()
		return err
	})
	return removed > 0, err
}

// NoticeDeferred counts one failed attempt and puts the notice back for later. Nothing is dropped
// by failing: the row stays until it is sent, which is what makes the fan-out at-least-once and
// the phone's idempotent apply path the thing that makes at-least-once harmless (SEE-89).
func (s *Store) NoticeDeferred(ctx context.Context, id int64, due time.Time) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`UPDATE notice SET attempts = attempts + 1, ready_at_ms = ? WHERE id = ?`,
			milliseconds(due), id); err != nil {
			return fmt.Errorf("defer notice: %w", err)
		}
		return nil
	})
}

// NoticeDropped removes a notice whose document is gone: retention swept the proposal while the
// notice was waiting. There is nothing to tell a subscriber about a document that no longer
// exists, and the phones that hold it decide for themselves what it means (SEE-89).
func (s *Store) NoticeDropped(ctx context.Context, id int64) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx, `DELETE FROM notice WHERE id = ?`, id); err != nil {
			return fmt.Errorf("drop notice: %w", err)
		}
		return nil
	})
}

// Pending is how many notices are waiting, for a startup line and for the tests that prove a crash
// between committing and fanning out leaves work rather than a gap.
func (s *Store) Pending(ctx context.Context) (int, error) {
	var pending int
	if err := s.reader.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM notice`).Scan(&pending); err != nil {
		return 0, fmt.Errorf("count notices: %w", err)
	}
	return pending, nil
}
