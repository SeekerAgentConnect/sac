package relay

import (
	"context"
	"log/slog"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Restricted sends a restricted channel's hints to the devices its publisher approved (SEE-156,
// docs/wiki/restricted-feeds.md).
//
// A public channel's hint goes to a topic anyone may join. A restricted channel's must not: a topic
// is membership Firebase owns and nobody here can revoke, so a device whose access ended would keep
// hearing that the feed moved. So the hint goes to each live grant's own push target instead, read
// at the moment of sending — a grant revoked a second ago is not among them — and the message is
// the same content-free hint a topic carries. A hint grants nothing: the phone it wakes reads the
// feed under its own session, and the gateway checks that read like any other.
type Restricted struct {
	Targets interface {
		PushTargets(context.Context, string, time.Time) ([]storage.PushTarget, error)
		PushTargetRejected(context.Context, string, string) error
	}
	Send func(ctx context.Context, target string, timeSensitive bool) (Outcome, error)
	Log  *slog.Logger
	Now  func() time.Time
}

// Dispatch hints every approved device of a restricted channel that a document changed.
//
// Like the topic relay it reports no failure: the document is committed and the broker has it, and
// a hint that could defer the notice would have the broker publish it again. The retired stream
// name's AccessNotice is not a document and wakes nobody through here — the revoked devices are
// told once, when the revocation commits (Revoked), and the approved ones are already streaming.
func (r Restricted) Dispatch(ctx context.Context, delivery dispatch.Delivery) error {
	if !delivery.Restricted || delivery.Kind == storage.AccessNotice {
		return nil
	}
	serverID := rules.ServerOf(delivery.Channel)
	targets, err := r.Targets.PushTargets(ctx, serverID, r.Now())
	if err != nil {
		r.Log.Warn("restricted hints not sent: the grants could not be read", "error", err)
		return nil
	}
	for _, target := range targets {
		r.one(ctx, target, delivery.Kind == storage.ProposalNotice)
	}
	return nil
}

// Revoked tells the devices whose grants just ended to look now. Best effort, and bounded: the
// revocation is already enforced by the gateway whatever becomes of this, and the device that reads
// in answer is refused.
func (r Restricted) Revoked(targets []storage.PushTarget) {
	ctx, stop := context.WithTimeout(context.Background(), 30*time.Second)
	defer stop()
	for _, target := range targets {
		if _, err := r.Send(ctx, target.Target, true); err != nil {
			r.Log.Info("a revocation hint was not delivered", "grant", target.GrantID, "reason", err)
		}
	}
}

func (r Restricted) one(ctx context.Context, target storage.PushTarget, timeSensitive bool) {
	outcome, err := r.Send(ctx, target.Target, timeSensitive)
	switch outcome {
	case TargetGone:
		if err := r.Targets.PushTargetRejected(ctx, target.GrantID, target.Target); err != nil {
			r.Log.Warn("a finished push target was not cleared", "grant", target.GrantID, "error", err)
		}
	case Delivered:
	default:
		r.Log.Info("a restricted hint was not delivered", "grant", target.GrantID, "reason", err)
	}
}
