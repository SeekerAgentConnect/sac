package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"google.golang.org/protobuf/proto"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

type StoredManifest = storage.StoredManifest
type StoredProposal = storage.StoredProposal
type StoredRequest = storage.StoredRequest
type NoticeKind = storage.NoticeKind

const (
	ManifestNotice = storage.ManifestNotice
	ProposalNotice = storage.ProposalNotice
)

// Manifest reads what the gateway holds for a server, or nil when it holds nothing. A registered
// publisher that has not published a manifest yet is this case too: registering grants the ability
// to publish and says nothing about the server.
func (s *Store) Manifest(ctx context.Context, serverID string) (*StoredManifest, error) {
	return readManifest(ctx, s.reader, serverID)
}

// Manifest inside a write, which is what AdvanceManifest decides against.
func (t *Tx) Manifest(ctx context.Context, serverID string) (*StoredManifest, error) {
	return readManifest(ctx, t.tx, serverID)
}

func readManifest(ctx context.Context, from querier, serverID string) (*StoredManifest, error) {
	var (
		document []byte
		updated  int64
	)
	err := from.QueryRowContext(ctx,
		`SELECT document, updated_at_ms FROM manifest WHERE server_id = ?`,
		serverID).Scan(&document, &updated)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read manifest: %w", err)
	}
	manifest := &serverv1.ServerManifest{}
	if err := proto.Unmarshal(document, manifest); err != nil {
		return nil, fmt.Errorf("read manifest %s: %w", serverID, err)
	}
	return &StoredManifest{Document: manifest, UpdatedAt: instant(updated)}, nil
}

// PutManifest stores a manifest and, in the same transaction, the notice that fans it out. It
// returns the channel's sequence after the publication: a manifest change moves the same counter a
// proposal does, because a subscriber that keeps up by sequence has to learn about both.
func (t *Tx) PutManifest(ctx context.Context, manifest *serverv1.ServerManifest, at time.Time) (uint64, error) {
	document, err := proto.Marshal(manifest)
	if err != nil {
		return 0, fmt.Errorf("write manifest: %w", err)
	}
	channel := manifest.GetFeed().GetChannel()
	var sequence uint64
	if channel != "" {
		sequence, err = t.bump(ctx, channel)
		if err != nil {
			return 0, err
		}
	}
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO manifest (server_id, settings_revision, document, updated_at_ms)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT (server_id) DO UPDATE SET
		   settings_revision = excluded.settings_revision,
		   document = excluded.document,
		   updated_at_ms = excluded.updated_at_ms`,
		manifest.GetServerId(), int64(manifest.GetSettingsRevision()), document,
		milliseconds(at)); err != nil {
		return 0, fmt.Errorf("write manifest: %w", err)
	}
	if channel != "" {
		if err := t.notice(ctx, channel, ManifestNotice, "",
			manifest.GetSettingsRevision(), sequence, at); err != nil {
			return 0, err
		}
	}
	return sequence, nil
}

// Proposal reads one proposal, or nil when the channel does not hold it — never published, or no
// longer kept (Sweep).
func (s *Store) Proposal(ctx context.Context, channel, proposalID string) (*StoredProposal, error) {
	return readProposal(ctx, s.reader, channel, proposalID)
}

// Proposal inside a write, which is what AdvanceProposal decides against.
func (t *Tx) Proposal(ctx context.Context, channel, proposalID string) (*StoredProposal, error) {
	return readProposal(ctx, t.tx, channel, proposalID)
}

func (s *Store) Request(ctx context.Context, channel, requestID string) (*StoredRequest, error) {
	return readRequest(ctx, s.reader, channel, requestID)
}

func (t *Tx) Request(ctx context.Context, channel, requestID string) (*StoredRequest, error) {
	return readRequest(ctx, t.tx, channel, requestID)
}

func readRequest(ctx context.Context, from querier, channel, requestID string) (*StoredRequest, error) {
	var document []byte
	var sequence, updated int64
	err := from.QueryRowContext(ctx,
		`SELECT document, sequence, updated_at_ms FROM proposal WHERE channel = ? AND proposal_id = ?`,
		channel, requestID).Scan(&document, &sequence, &updated)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read request: %w", err)
	}
	return storedRequest(document, sequence, updated, channel, requestID)
}

func readProposal(ctx context.Context, from querier, channel, proposalID string) (*StoredProposal, error) {
	var (
		document []byte
		sequence int64
		updated  int64
	)
	err := from.QueryRowContext(ctx,
		`SELECT document, sequence, updated_at_ms FROM proposal
		  WHERE channel = ? AND proposal_id = ?`,
		channel, proposalID).Scan(&document, &sequence, &updated)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("read proposal: %w", err)
	}
	return storedProposal(document, sequence, updated, channel, proposalID)
}

func storedProposal(document []byte, sequence, updated int64, channel, proposalID string) (*StoredProposal, error) {
	common := &requestv2.Request{}
	if err := proto.Unmarshal(document, common); err == nil && common.GetContractVersion() > 0 && common.GetIdentity() != nil {
		return &StoredProposal{Document: rules.ProposalFromRequest(common), Sequence: uint64(sequence), UpdatedAt: instant(updated)}, nil
	}
	proposal := &proposalv1.Proposal{}
	if err := proto.Unmarshal(document, proposal); err != nil {
		return nil, fmt.Errorf("read proposal %s/%s: %w", channel, proposalID, err)
	}
	return &StoredProposal{
		Document:  proposal,
		Sequence:  uint64(sequence),
		UpdatedAt: instant(updated),
		Legacy:    true,
	}, nil
}

func storedRequest(document []byte, sequence, updated int64, channel, requestID string) (*StoredRequest, error) {
	common := &requestv2.Request{}
	if err := proto.Unmarshal(document, common); err == nil && common.GetContractVersion() > 0 && common.GetIdentity() != nil {
		return &StoredRequest{Document: common, Sequence: uint64(sequence), UpdatedAt: instant(updated)}, nil
	}
	proposal := &proposalv1.Proposal{}
	if err := proto.Unmarshal(document, proposal); err != nil {
		return nil, fmt.Errorf("read request %s/%s: %w", channel, requestID, err)
	}
	if proposal.GetProposalId() == "" {
		return nil, fmt.Errorf("read request %s/%s: unknown document encoding", channel, requestID)
	}
	return &StoredRequest{Document: rules.RequestFromProposal(proposal), Sequence: uint64(sequence), UpdatedAt: instant(updated), Legacy: true}, nil
}

// Page reads up to limit proposals of a channel, ordered by proposal ID and starting after the one
// named.
//
// The order is the identity's rather than the sequence's on purpose. A walk that ordered by
// sequence would have documents move between pages as they were republished — a proposal updated
// during the walk would be read twice, or not at all — while an ID does not change for as long as
// the proposal exists. So the walk is stable even while the feed moves underneath it, and what a
// reader ends up with is described exactly in ListProposalsResponse.snapshot_sequence.
func (s *Store) Page(ctx context.Context, channel, after string, limit int) ([]*StoredProposal, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT proposal_id, document, sequence, updated_at_ms FROM proposal
		  WHERE channel = ? AND proposal_id > ?
		  ORDER BY proposal_id LIMIT ?`,
		channel, after, limit)
	if err != nil {
		return nil, fmt.Errorf("read page: %w", err)
	}
	defer rows.Close()
	var page []*StoredProposal
	for rows.Next() {
		var (
			proposalID string
			document   []byte
			sequence   int64
			updated    int64
		)
		if err := rows.Scan(&proposalID, &document, &sequence, &updated); err != nil {
			return nil, fmt.Errorf("read page: %w", err)
		}
		stored, err := storedProposal(document, sequence, updated, channel, proposalID)
		if err != nil {
			return nil, err
		}
		page = append(page, stored)
	}
	return page, rows.Err()
}

func (s *Store) RequestPage(ctx context.Context, channel, after string, limit int) ([]*StoredRequest, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT proposal_id, document, sequence, updated_at_ms FROM proposal
		  WHERE channel = ? AND proposal_id > ? ORDER BY proposal_id LIMIT ?`, channel, after, limit)
	if err != nil {
		return nil, fmt.Errorf("read request page: %w", err)
	}
	defer rows.Close()
	var page []*StoredRequest
	for rows.Next() {
		var requestID string
		var document []byte
		var sequence, updated int64
		if err := rows.Scan(&requestID, &document, &sequence, &updated); err != nil {
			return nil, fmt.Errorf("read request page: %w", err)
		}
		stored, err := storedRequest(document, sequence, updated, channel, requestID)
		if err != nil {
			return nil, err
		}
		page = append(page, stored)
	}
	return page, rows.Err()
}

// Count is how many proposals a channel holds, for the bound a publisher stays inside.
func (t *Tx) Count(ctx context.Context, channel string) (int, error) {
	var count int
	if err := t.tx.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM proposal WHERE channel = ?`, channel).Scan(&count); err != nil {
		return 0, fmt.Errorf("count proposals: %w", err)
	}
	return count, nil
}

// PutProposal stores a proposal and the notice that fans it out, in one transaction, and returns
// the channel's sequence after it.
func (t *Tx) PutProposal(ctx context.Context, proposal *proposalv1.Proposal, at time.Time) (uint64, error) {
	document, err := proto.Marshal(proposal)
	if err != nil {
		return 0, fmt.Errorf("write proposal: %w", err)
	}
	channel := proposal.GetChannel()
	sequence, err := t.bump(ctx, channel)
	if err != nil {
		return 0, err
	}
	cancelled := 0
	if proposal.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		cancelled = 1
	}
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO proposal
		   (channel, proposal_id, server_id, revision, cancelled, expires_at_ms, sequence,
		    document, updated_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (channel, proposal_id) DO UPDATE SET
		   revision = excluded.revision,
		   cancelled = excluded.cancelled,
		   expires_at_ms = excluded.expires_at_ms,
		   sequence = excluded.sequence,
		   document = excluded.document,
		   updated_at_ms = excluded.updated_at_ms`,
		channel, proposal.GetProposalId(), proposal.GetServerId(),
		int64(proposal.GetRevision()), cancelled,
		proposal.GetExpiresAt().AsTime().UnixMilli(), int64(sequence), document,
		milliseconds(at)); err != nil {
		return 0, fmt.Errorf("write proposal: %w", err)
	}
	if err := t.notice(ctx, channel, ProposalNotice, proposal.GetProposalId(),
		proposal.GetRevision(), sequence, at); err != nil {
		return 0, err
	}
	return sequence, nil
}

// PutRequest stores a common request in the existing proposal table. Keeping the physical schema
// preserves every deployed database; the row identity and retention columns already name exactly
// the common envelope's feed scope, request identity, revision, status and expiry.
func (t *Tx) PutRequest(ctx context.Context, request *requestv2.Request, at time.Time) (uint64, error) {
	document, err := proto.Marshal(request)
	if err != nil {
		return 0, fmt.Errorf("write request: %w", err)
	}
	channel := request.GetAudience().GetFeed().GetChannel()
	sequence, err := t.bump(ctx, channel)
	if err != nil {
		return 0, err
	}
	cancelled := 0
	if request.GetLifecycle().GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
		cancelled = 1
	}
	identity := request.GetIdentity()
	lifecycle := request.GetLifecycle()
	if _, err := t.tx.ExecContext(ctx,
		`INSERT INTO proposal
		   (channel, proposal_id, server_id, revision, cancelled, expires_at_ms, sequence, document, updated_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (channel, proposal_id) DO UPDATE SET
		   revision = excluded.revision, cancelled = excluded.cancelled,
		   expires_at_ms = excluded.expires_at_ms, sequence = excluded.sequence,
		   document = excluded.document, updated_at_ms = excluded.updated_at_ms`,
		channel, identity.GetRequestId(), identity.GetSourceId(), int64(lifecycle.GetRevision()), cancelled,
		lifecycle.GetExpiresAt().AsTime().UnixMilli(), int64(sequence), document, milliseconds(at)); err != nil {
		return 0, fmt.Errorf("write request: %w", err)
	}
	if err := t.notice(ctx, channel, ProposalNotice, identity.GetRequestId(), lifecycle.GetRevision(), sequence, at); err != nil {
		return 0, err
	}
	return sequence, nil
}

// Sequence is the channel's count of accepted publications, or zero when it has none. A reader
// gets it as a snapshot boundary and as the answer to "has anything changed since?".
func (s *Store) Sequence(ctx context.Context, channel string) (uint64, error) {
	var sequence int64
	err := s.reader.QueryRowContext(ctx,
		`SELECT sequence FROM channel_sequence WHERE channel = ?`, channel).Scan(&sequence)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return 0, nil
	case err != nil:
		return 0, fmt.Errorf("read sequence: %w", err)
	}
	return uint64(sequence), nil
}

func (t *Tx) bump(ctx context.Context, channel string) (uint64, error) {
	var sequence int64
	if err := t.tx.QueryRowContext(ctx,
		`INSERT INTO channel_sequence (channel, sequence) VALUES (?, 1)
		 ON CONFLICT (channel) DO UPDATE SET sequence = sequence + 1
		 RETURNING sequence`, channel).Scan(&sequence); err != nil {
		return 0, fmt.Errorf("advance sequence: %w", err)
	}
	return uint64(sequence), nil
}

// Sweep is retention: it removes every proposal whose expiry passed before the given instant, and
// says how many went.
//
// Expiry is the one clock a proposal has that everybody agrees on, so it is the one retention uses.
// A cancelled proposal keeps its own expiry and is kept until then as well, so a phone that was
// switched off learns that a proposal was withdrawn rather than simply failing to find it — which
// is the difference between "the publisher took this back" and "something went wrong".
//
// Nothing on a phone is deleted by this. A proposal a phone holds is the phone's until the owner
// removes the feed (SEE-89); retention is only about how long the gateway keeps serving one.
func (s *Store) Sweep(ctx context.Context, before time.Time) (int64, error) {
	var removed int64
	err := s.write(ctx, func(tx *Tx) error {
		outcome, err := tx.tx.ExecContext(ctx,
			`DELETE FROM proposal WHERE expires_at_ms < ?`, milliseconds(before))
		if err != nil {
			return fmt.Errorf("sweep proposals: %w", err)
		}
		removed, err = outcome.RowsAffected()
		return err
	})
	return removed, err
}
