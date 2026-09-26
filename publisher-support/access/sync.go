package access

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// Grants is the part of the gateway's publisher API the syncer calls.
type Grants interface {
	DescribeAccess(ctx context.Context) (gateway.Access, error)
	GrantAccess(ctx context.Context, grant gateway.Grant) (time.Duration, error)
	RevokeAccess(ctx context.Context, grantIDs ...string) (gateway.Status, error)
}

// Syncer tells the gateway what this publisher decided, and keeps telling it until it confirms.
//
// Every grant is an outbox row (publisher-support/store): the state this publisher wants the gateway
// to hold, at a revision, and the revision the gateway confirmed. A grant, a renewal and a
// revocation are each a new revision, sent until the gateway answers, with backoff in between, and
// the operator's page shows the ones that have not landed with their last error — a revocation is
// never reported as done before the gateway enforces it.
//
// # The offline bound
//
// A grant runs for the lifetime this publisher asks for (PUBLISHER_ACCESS_GRANT_HOURS, six hours by
// default; the gateway caps it at BROADCAST_MAX_GRANT_HOURS) and is renewed when a third of it is
// left. The expiry recorded is the one the gateway acknowledged, and a third is a third of the
// lifetime the gateway actually grants, so a gateway cap shorter than the lifetime asked for moves
// the renewal earlier instead of letting the grant lapse. So when this publisher cannot reach the gateway, an approved device keeps reading for at
// most one grant lifetime, and a device revoked in that time keeps reading for at most what was left
// of its grant — never longer, because nothing renews a grant this publisher has revoked.
type Syncer struct {
	store       *store.Store
	grants      Grants
	eligibility Eligibility
	lifetime    time.Duration
	channel     string
	log         *slog.Logger
	now         func() time.Time
	backoff     func(attempts int) time.Duration
	wake        chan struct{}
	idle        time.Duration
	// One pass at a time, so a redemption's immediate attempt and the loop do not both send the
	// same grant. It also guards the two fields below.
	passing sync.Mutex
	// capped is the lifetime the gateway was last seen to cap a grant at, zero while it has granted
	// what it was asked for.
	capped time.Duration
	// unchecked is the devices due for renewal whose eligibility rule answered an error: their
	// grants are not renewed, and the rule is asked again after a backoff.
	unchecked map[string]recheck
}

// recheck is when a failed eligibility check is asked again.
type recheck struct {
	attempts int
	due      time.Time
}

// SyncPlan is what a [Syncer] needs.
type SyncPlan struct {
	Store       *store.Store
	Grants      Grants
	Eligibility Eligibility
	Lifetime    time.Duration
	Channel     string
	Log         *slog.Logger
	Now         func() time.Time
	Backoff     func(attempts int) time.Duration
	Idle        time.Duration
}

// NewSyncer builds one.
func NewSyncer(plan SyncPlan) *Syncer {
	syncer := &Syncer{
		store: plan.Store, grants: plan.Grants, eligibility: plan.Eligibility,
		lifetime: plan.Lifetime, channel: plan.Channel, log: plan.Log, now: plan.Now,
		backoff: plan.Backoff, wake: make(chan struct{}, 1), idle: plan.Idle,
		unchecked: map[string]recheck{},
	}
	if syncer.lifetime <= 0 {
		syncer.lifetime = DefaultGrantLifetime
	}
	if syncer.eligibility == nil {
		syncer.eligibility = ManualApproval{}
	}
	if syncer.now == nil {
		syncer.now = time.Now
	}
	if syncer.backoff == nil {
		syncer.backoff = gateway.Backoff
	}
	if syncer.idle <= 0 {
		syncer.idle = 15 * time.Second
	}
	return syncer
}

// Wake asks for a pass now. It never blocks.
func (s *Syncer) Wake() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

// Run syncs until ctx is done.
func (s *Syncer) Run(ctx context.Context) {
	ticker := time.NewTicker(s.idle)
	defer ticker.Stop()
	for {
		if _, err := s.Pass(ctx); err != nil && !errors.Is(err, context.Canceled) {
			s.log.Error("an access sync pass stopped early", "error", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
		case <-ticker.C:
		}
	}
}

// Pass renews what is due for renewal and sends every grant whose gateway state is behind. It
// answers how many the gateway confirmed.
func (s *Syncer) Pass(ctx context.Context) (int, error) {
	s.passing.Lock()
	defer s.passing.Unlock()
	if err := s.renew(ctx); err != nil {
		return 0, err
	}
	// A challenge that expired can no longer be redeemed — the redemption requires an unused one
	// that has not expired — so the row is spent history, and a publisher that ran for a year
	// would otherwise be holding one for every challenge it ever issued. Its failure is not the
	// pass's: telling the gateway about a grant matters more than tidying up after an attempt.
	if _, err := s.store.SweepChallenges(ctx, s.now()); err != nil {
		s.log.Warn("could not forget expired access challenges", "error", err)
	}
	due, err := s.store.GrantsDue(ctx, s.now(), 32)
	if err != nil {
		return 0, err
	}
	confirmed := 0
	for _, grant := range due {
		if s.one(ctx, grant) {
			confirmed++
		}
	}
	return confirmed, nil
}

// SyncNow sends one grant at once, for a redemption that wants the phone able to read immediately.
// It answers whether the gateway confirmed it; when it did not, the loop keeps trying.
func (s *Syncer) SyncNow(ctx context.Context, grantID string) bool {
	s.passing.Lock()
	defer s.passing.Unlock()
	due, err := s.store.GrantsDue(ctx, s.now(), 256)
	if err != nil {
		return false
	}
	for _, grant := range due {
		if grant.ID == grantID {
			return s.one(ctx, grant)
		}
	}
	return false
}

// renew re-asks the eligibility rule about every approved device whose grant is due for renewal,
// revokes the ones it now refuses, and extends the ones it answered for. A device whose rule
// answered an error is neither: its grant keeps the expiry it has, and the rule is asked again
// after a backoff — an eligibility outage must not extend access it can no longer vouch for.
func (s *Syncer) renew(ctx context.Context) error {
	now := s.now()
	before := s.effective() / 3
	devices, err := s.store.Devices(ctx)
	if err != nil {
		return err
	}
	var checked []string
	unchecked := map[string]recheck{}
	for _, device := range devices {
		grant := device.Grant
		if device.State != store.DeviceApproved || grant == nil || grant.State != store.GrantActive ||
			!grant.ExpiresAt.Before(now.Add(before)) {
			continue
		}
		if waiting, failed := s.unchecked[device.ID]; failed && now.Before(waiting.due) {
			unchecked[device.ID] = waiting
			continue
		}
		decision, err := s.eligibility.Decide(ctx, Subject{
			Wallet: device.Wallet, Installation: device.Installation, Label: device.Label,
			Channel: s.channel,
		})
		switch {
		case err != nil:
			attempts := s.unchecked[device.ID].attempts
			unchecked[device.ID] = recheck{attempts: attempts + 1, due: now.Add(s.backoff(attempts))}
			s.log.Warn("the eligibility rule did not answer about a device; its grant is not renewed until it does",
				"request", device.ID, "attempts", attempts+1, "error", err)
		case decision == Ineligible:
			s.log.Info("the eligibility rule no longer admits a device; revoking it", "request", device.ID)
			if err := s.store.Revoke(ctx, device.ID, now); err != nil && !errors.Is(err, store.ErrDeviceState) {
				return err
			}
		default:
			checked = append(checked, device.ID)
		}
	}
	// Only the devices still due and still failing are remembered.
	s.unchecked = unchecked
	renewed, err := s.store.Renew(ctx, now, before, now.Add(s.lifetime), checked)
	if err != nil {
		return err
	}
	if renewed > 0 {
		s.log.Info("access grants renewed", "grants", renewed)
	}
	return nil
}

// effective is the lifetime a grant really runs for: the one this publisher asks for, or the
// gateway's cap when it grants less.
func (s *Syncer) effective() time.Duration {
	if s.capped > 0 && s.capped < s.lifetime {
		return s.capped
	}
	return s.lifetime
}

// learn reads the gateway's cap from what it granted against what it was asked for. The gateway
// answers whole seconds left of the grant it holds, so a lifetime within a minute of the one asked
// for is the one asked for.
func (s *Syncer) learn(requested, granted time.Duration) {
	switch {
	case granted <= 0:
		// A gateway that says nothing about the lifetime is taken at the lifetime asked for.
	case granted+time.Minute < requested:
		if granted != s.capped {
			s.log.Info("the gateway grants access for less than this publisher asks; renewing earlier",
				"asked", requested, "granted", granted)
		}
		s.capped = granted
	case requested > s.capped:
		// It granted more than the cap it used to have: the operator raised or removed it.
		s.capped = 0
	}
}

// one sends one grant's wanted state and records what the gateway said.
func (s *Syncer) one(ctx context.Context, grant store.AccessGrant) bool {
	now := s.now()
	var (
		err          error
		acknowledged time.Time
	)
	if grant.State == store.GrantActive {
		lifetime := grant.ExpiresAt.Sub(now)
		if lifetime < time.Second {
			// Expired before it could be sent: there is nothing left to grant, and the renewal
			// (or the device's next redemption) is what gives it a lifetime again.
			lifetime = time.Second
		}
		var granted time.Duration
		granted, err = s.grants.GrantAccess(ctx, gateway.Grant{
			ID: grant.ID, SubscriberRef: grant.SubscriberRef, DeviceRef: "device-" + grant.Installation,
			SessionDigest: grant.SessionDigest, Lifetime: lifetime,
		})
		if err == nil && granted > 0 {
			// What the gateway enforces, after its BROADCAST_MAX_GRANT_HOURS cap: the renewal is
			// scheduled from this, not from what was asked for.
			s.learn(lifetime, granted)
			acknowledged = now.Add(granted)
		}
	} else {
		_, err = s.grants.RevokeAccess(ctx, grant.ID)
	}
	if err == nil {
		if err := s.store.GrantSynced(ctx, grant.ID, grant.Revision, now, acknowledged); err != nil {
			s.log.Error("a confirmed grant was not recorded", "grant", grant.ID, "error", err)
			return false
		}
		return true
	}
	var refusal *gateway.Refusal
	if errors.As(err, &refusal) && refusal.Problem == "grant_revoked" {
		// The gateway already revoked it, which is final there: this side agrees and stops.
		if err := s.store.GrantRevokedByGateway(ctx, grant.ID, now); err != nil {
			s.log.Error("a gateway revocation was not recorded", "grant", grant.ID, "error", err)
		}
		return true
	}
	reason := err.Error()
	if refusal != nil {
		reason = refusal.Problem + ": " + refusal.Detail
	}
	if len(reason) > 300 {
		reason = reason[:300]
	}
	due := now.Add(s.backoff(grant.Attempts))
	if err := s.store.GrantSyncFailed(ctx, grant.ID, due, reason); err != nil {
		s.log.Error("a failed grant sync was not recorded", "grant", grant.ID, "error", err)
	}
	s.log.Warn("the gateway has not confirmed an access change; it will be sent again",
		"grant", grant.ID, "state", grant.State, "attempts", grant.Attempts+1, "reason", reason)
	return false
}

// Guard is the publication guard a restricted feed's drainer asks before every signal
// (publish.Plan.Guard). It answers a refusal — which defers the signal like an outage — until the
// gateway confirms that it enforces this feed as restricted, at this authentication origin. A
// gateway too old to know, or one that registered the feed as public, never receives a signal.
type Guard struct {
	grants     Grants
	authOrigin string
	now        func() time.Time
	mutex      sync.Mutex
	until      time.Time
}

// NewGuard builds one. A confirmation is trusted for a minute, so a gateway whose operator changes
// the policy stops receiving signals within a minute of the change.
func NewGuard(grants Grants, authOrigin string, now func() time.Time) *Guard {
	if now == nil {
		now = time.Now
	}
	return &Guard{grants: grants, authOrigin: authOrigin, now: now}
}

// Check is the drainer's guard.
func (g *Guard) Check(ctx context.Context) *gateway.Refusal {
	g.mutex.Lock()
	defer g.mutex.Unlock()
	if g.now().Before(g.until) {
		return nil
	}
	access, err := g.grants.DescribeAccess(ctx)
	if err != nil {
		var refusal *gateway.Refusal
		if errors.As(err, &refusal) {
			return &gateway.Refusal{Problem: "access_unconfirmed", Permanent: false,
				Detail: "the gateway did not confirm it enforces this restricted feed (" +
					refusal.Problem + "); nothing is published until it does"}
		}
		return &gateway.Refusal{Problem: "access_unconfirmed", Permanent: false, Detail: err.Error()}
	}
	if !access.Restricted || access.AuthOrigin != g.authOrigin {
		return &gateway.Refusal{Problem: "access_unconfirmed", Permanent: false,
			Detail: "the gateway does not enforce this feed as restricted at " + g.authOrigin +
				"; register it with feed-gatewayctl access --access restricted --auth-origin " +
				g.authOrigin + ". Nothing is published until it does"}
	}
	g.until = g.now().Add(time.Minute)
	return nil
}
