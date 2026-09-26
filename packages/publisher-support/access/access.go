// Package access is a restricted feed's publisher side (SEE-156, docs/wiki/restricted-feeds.md):
// proving wallet ownership, recording the device that proved it, the decision about it, the
// one-use invitation that carries an approval to that device, and the gateway grant the invitation
// becomes.
//
// It is shared library code for any publisher built on publisher-support. The demo that ships it is
// demo-signals (CopyTrading), which runs restricted; demo-prediction stays public and never constructs a
// [Service]. The decision itself is pluggable: [ManualApproval] leaves every request for an
// operator, and a publisher with its own rule — a subscription, a feature entitlement — implements
// [Eligibility] instead.
//
// # What the publisher learns, and what the gateway does not
//
// The publisher sees the wallet address, because deciding who may read is its job; nobody else
// does. The gateway is told an opaque subscriber reference, a device reference, a grant ID and the
// digest of a session, and enforces them. The wallet's signature never leaves this process, and the
// session exists in exactly two places: in the phone that redeemed the invitation, and — as a
// digest — at the gateway.
package access

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/ids"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// Decision is an eligibility rule's answer.
type Decision = store.Decision

const (
	// Undecided leaves a request pending for the operator.
	Undecided = store.Undecided
	// Eligible approves a request at once, and keeps an approved device approved.
	Eligible = store.Eligible
	// Ineligible rejects a request at once, and revokes an approved device the next time it is
	// checked — at redemption, and whenever its grant is renewed.
	Ineligible = store.Ineligible
)

// Subject is who a decision is about: a wallet that proved it controls itself, and the device it
// bound. The label is the phone's own claim and is never a reason to decide anything.
type Subject struct {
	Wallet       string
	Installation string
	Label        string
	Channel      string
}

// Eligibility is the publisher's access policy (SEE-156): the hook another server replaces to check
// a subscription or a feature entitlement instead of asking an operator.
//
// Decide is asked when a wallet first proves itself, again when the device redeems its invitation,
// and again whenever the device's grant is renewed. An error is not a decision: the request stays
// as it is, and the question is asked again later.
type Eligibility interface {
	Decide(ctx context.Context, subject Subject) (Decision, error)
}

// ManualApproval is the demo's policy: every wallet-verified request waits for the operator, and the
// operator's decision stands until the operator changes it.
type ManualApproval struct{}

func (ManualApproval) Decide(context.Context, Subject) (Decision, error) { return Undecided, nil }

// Settings is a restricted feed's configuration.
type Settings struct {
	ServerID   string
	GatewayURL string
	// AuthOrigin is where phones reach this service: the origin the gateway's operator registered,
	// and the first line of every challenge a wallet signs.
	AuthOrigin string
	// How long a challenge may be answered, an invitation redeemed, and a grant run before this
	// publisher renews it.
	ChallengeLifetime  time.Duration
	InvitationLifetime time.Duration
	GrantLifetime      time.Duration
	// Skew is how far a device's clock may disagree with this one on a signed status or redemption.
	Skew time.Duration
}

// Defaults for [Settings].
const (
	DefaultChallengeLifetime  = 5 * time.Minute
	DefaultInvitationLifetime = 5 * time.Minute
	DefaultGrantLifetime      = 6 * time.Hour
	DefaultSkew               = 5 * time.Minute
	// MostLabelBytes bounds the phone's own name for itself.
	MostLabelBytes = 64
)

// Service is the restricted feed's publisher side.
type Service struct {
	store       *store.Store
	settings    Settings
	eligibility Eligibility
	syncer      *Syncer
	log         *slog.Logger
	now         func() time.Time
	newID       func() string
}

// Plan is what a [Service] needs.
type Plan struct {
	Store       *store.Store
	Settings    Settings
	Eligibility Eligibility
	Syncer      *Syncer
	Log         *slog.Logger
	Now         func() time.Time
	NewID       func() string
}

// New builds the service.
func New(plan Plan) *Service {
	settings := plan.Settings
	if settings.ChallengeLifetime <= 0 {
		settings.ChallengeLifetime = DefaultChallengeLifetime
	}
	if settings.InvitationLifetime <= 0 {
		settings.InvitationLifetime = DefaultInvitationLifetime
	}
	if settings.GrantLifetime <= 0 {
		settings.GrantLifetime = DefaultGrantLifetime
	}
	if settings.Skew <= 0 {
		settings.Skew = DefaultSkew
	}
	eligibility := plan.Eligibility
	if eligibility == nil {
		eligibility = ManualApproval{}
	}
	now := plan.Now
	if now == nil {
		now = time.Now
	}
	newID := plan.NewID
	if newID == nil {
		newID = ids.New
	}
	return &Service{
		store: plan.Store, settings: settings, eligibility: eligibility, syncer: plan.Syncer,
		log: plan.Log, now: now, newID: newID,
	}
}

// Channel is the feed this service decides about.
func (s *Service) Channel() string { return signals.ChannelFor(s.settings.ServerID) }

// Problem is a refusal with a stable code a phone can branch on.
type Problem struct {
	Status int
	Code   string
	Detail string
}

func (p *Problem) Error() string { return p.Code + ": " + p.Detail }

func refusal(status int, code, detail string) *Problem {
	return &Problem{Status: status, Code: code, Detail: detail}
}

// ChallengeRequest is a phone asking to prove a wallet for a device key.
type ChallengeRequest struct {
	Channel   string
	Wallet    string
	DeviceKey []byte
	Label     string
}

// ChallengeAnswer is the challenge, as fields and as the text the wallet will sign.
type ChallengeAnswer struct {
	Challenge Challenge
	Message   string
}

// Challenge issues a fresh, expiring, one-use challenge bound to this feed, the wallet and the
// device key.
func (s *Service) Challenge(ctx context.Context, asked ChallengeRequest) (*ChallengeAnswer, error) {
	if asked.Channel != s.Channel() {
		return nil, refusal(404, "unknown_feed", "this service decides access to another feed")
	}
	if _, err := WalletKey(asked.Wallet); err != nil {
		return nil, refusal(400, "bad_wallet", "wallet must be a base58 Solana address")
	}
	if _, err := DeviceKey(asked.DeviceKey); err != nil {
		return nil, refusal(400, "bad_device_key",
			"device_key must be an X.509 SubjectPublicKeyInfo holding a P-256 key")
	}
	label := strings.TrimSpace(asked.Label)
	if label == "" || !signals.Printable(label, MostLabelBytes, false) {
		return nil, refusal(400, "bad_label",
			fmt.Sprintf("label must be 1 to %d bytes of printable text", MostLabelBytes))
	}
	issued := s.now().UTC().Truncate(time.Second)
	challenge := Challenge{
		AuthOrigin:   s.settings.AuthOrigin,
		Channel:      s.Channel(),
		Wallet:       asked.Wallet,
		Installation: Installation(asked.DeviceKey),
		Attempt:      s.newID(),
		Nonce:        randomText(16),
		IssuedAt:     issued,
		ExpiresAt:    issued.Add(s.settings.ChallengeLifetime),
	}
	if err := s.store.PutChallenge(ctx, store.AccessChallenge{
		ID: challenge.Attempt, Wallet: challenge.Wallet, DeviceKey: asked.DeviceKey,
		Installation: challenge.Installation, Label: label, Nonce: challenge.Nonce,
		IssuedAt: challenge.IssuedAt, ExpiresAt: challenge.ExpiresAt,
	}); err != nil {
		return nil, err
	}
	return &ChallengeAnswer{Challenge: challenge, Message: string(challenge.Message())}, nil
}

// Answer is a phone's answer to a challenge: the wallet's signature over the message, and the device
// key's over the same bytes, which proves the phone holds the key the wallet bound.
type Answer struct {
	Attempt         string
	WalletSignature []byte
	DeviceSignature []byte
}

// Request verifies an answer and records the device as a request, decided by the eligibility rule
// when it decides at once and left pending for the operator otherwise.
func (s *Service) Request(ctx context.Context, answer Answer) (*store.AccessDevice, error) {
	held, used, err := s.store.Challenge(ctx, answer.Attempt)
	if errors.Is(err, store.ErrNoChallenge) {
		return nil, refusal(404, "unknown_challenge", "no challenge of that attempt was issued")
	}
	if err != nil {
		return nil, err
	}
	now := s.now()
	if used {
		return nil, refusal(409, "challenge_used", "that challenge was already answered; ask for another")
	}
	if !now.Before(held.ExpiresAt) {
		return nil, refusal(410, "challenge_expired", "that challenge expired; ask for another")
	}
	challenge := s.rebuild(*held)
	message := challenge.Message()
	if !VerifyWallet(held.Wallet, message, answer.WalletSignature) ||
		!VerifyDevice(held.DeviceKey, message, answer.DeviceSignature) {
		// A forged or mismatched answer spends the challenge: it was the one attempt.
		if err := s.store.SpendChallenge(ctx, held.ID, now); err != nil {
			return nil, err
		}
		return nil, refusal(401, "bad_signature",
			"the signatures do not prove this wallet and this device key for that challenge")
	}
	decision, err := s.eligibility.Decide(ctx, Subject{
		Wallet: held.Wallet, Installation: held.Installation, Label: held.Label, Channel: s.Channel(),
	})
	if err != nil {
		s.log.Warn("the eligibility rule did not decide; the request waits for the operator",
			"error", err)
		decision = Undecided
	}
	device, err := s.store.RequestAccess(ctx, *held, now, s.newID(), "sub-"+randomText(12),
		decision, s.invitation)
	if errors.Is(err, store.ErrChallengeUsed) {
		return nil, refusal(409, "challenge_used", "that challenge was already answered; ask for another")
	}
	if err != nil {
		return nil, err
	}
	s.log.Info("a wallet-verified device asked for access", "request", device.ID,
		"state", device.State, "installation", device.Installation)
	return device, nil
}

// rebuild is the challenge as the store holds it, in the form the wallet signed.
func (s *Service) rebuild(held store.AccessChallenge) Challenge {
	return Challenge{
		AuthOrigin: s.settings.AuthOrigin, Channel: s.Channel(), Wallet: held.Wallet,
		Installation: held.Installation, Attempt: held.ID, Nonce: held.Nonce,
		IssuedAt: held.IssuedAt, ExpiresAt: held.ExpiresAt,
	}
}

// invitation mints one invitation token and its expiry.
func (s *Service) invitation() (string, time.Time) {
	return randomText(32), s.now().Add(s.settings.InvitationLifetime)
}

// Signed is a device-key signature over a statement made at a moment.
type Signed struct {
	AtMillis        int64
	DeviceSignature []byte
}

// fresh says whether a signed moment is close enough to now.
func (s *Service) fresh(atMillis int64) bool {
	at := time.UnixMilli(atMillis)
	now := s.now()
	return at.After(now.Add(-s.settings.Skew)) && at.Before(now.Add(s.settings.Skew))
}

// Status is a device's decision, as the device may learn it.
type Status struct {
	RequestID  string
	State      string
	Invitation *store.AccessInvitation
	// Link is the invitation as a link, for a device that would rather scan it.
	Link string
	// Connected says the device redeemed an invitation and holds a live grant.
	Connected bool
}

// Status answers a device's question about its own request, signed with its device key.
func (s *Service) Status(ctx context.Context, requestID string, signed Signed) (*Status, error) {
	device, err := s.store.Device(ctx, requestID)
	if errors.Is(err, store.ErrNoDevice) {
		return nil, refusal(404, "unknown_request", "no request of that ID")
	}
	if err != nil {
		return nil, err
	}
	if !s.fresh(signed.AtMillis) {
		return nil, refusal(401, "stale_proof", "the signed moment is too far from this server's clock")
	}
	if !VerifyDevice(device.DeviceKey, StatusStatement(requestID, signed.AtMillis), signed.DeviceSignature) {
		// The same answer as an unknown request: a caller without the key learns nothing.
		return nil, refusal(404, "unknown_request", "no request of that ID")
	}
	status := &Status{RequestID: device.ID, State: device.State}
	if device.State == store.DeviceApproved {
		if invitation := device.Invitation; invitation != nil && invitation.Live(s.now()) {
			status.Invitation = invitation
			status.Link = s.Link(invitation.Token)
		}
		status.Connected = device.Grant != nil && device.Grant.State == store.GrantActive &&
			device.Invitation != nil && device.Invitation.UsedAt != nil
	}
	return status, nil
}

// Link is an invitation as a link: the feed reference with the invitation beside it. It is useful
// only to the device whose key it is bound to, so showing it to the operator, or as a QR code, gives
// nobody else anything.
func (s *Service) Link(token string) string {
	return "seekervault://feed?v=1&gateway=" + url.QueryEscape(s.settings.GatewayURL) +
		"&server=" + url.QueryEscape(s.settings.ServerID) + "&access=restricted" +
		"&invitation=" + url.QueryEscape(token)
}

// Redemption is a device redeeming its invitation.
type Redemption struct {
	Channel    string
	Invitation string
	Signed
}

// Session is what a redeemed invitation gives the device: the session it presents to the gateway,
// and whether the gateway has been told yet.
type Session struct {
	RequestID string
	Session   string
	GrantID   string
	// Until is when the grant runs out unless this publisher renews it, which it does while the
	// device stays approved.
	Until time.Time
	// Synced says the gateway already holds the grant. When it does not, the phone keeps trying
	// its reads: this publisher keeps trying the gateway.
	Synced bool
}

// Redeem consumes an invitation and grants the device access. Everything the invitation was bound
// to is checked again: the device key's signature (a copied invitation is useless without the key),
// the feed, the expiry, the single use, and — the one that can change — whether the device is still
// approved right now.
func (s *Service) Redeem(ctx context.Context, asked Redemption) (*Session, error) {
	if asked.Channel != s.Channel() {
		return nil, refusal(404, "unknown_feed", "this service decides access to another feed")
	}
	if !s.fresh(asked.AtMillis) {
		return nil, refusal(401, "stale_proof", "the signed moment is too far from this server's clock")
	}
	statement := RedeemStatement(asked.Channel, asked.Invitation, asked.AtMillis)
	var ineligible bool
	verify := func(device store.AccessDevice) error {
		if !VerifyDevice(device.DeviceKey, statement, asked.DeviceSignature) {
			return refusal(404, "unknown_invitation", "no invitation of that token for this device")
		}
		return nil
	}
	// The rule is asked before the transaction, because it may be a network call; its answer is
	// applied inside it.
	if device, err := s.deviceForInvitation(ctx, asked.Invitation); err == nil && device != nil {
		decision, err := s.eligibility.Decide(ctx, Subject{
			Wallet: device.Wallet, Installation: device.Installation, Label: device.Label,
			Channel: s.Channel(),
		})
		if err == nil && decision == Ineligible && VerifyDevice(device.DeviceKey, statement, asked.DeviceSignature) {
			ineligible = true
			if err := s.store.Revoke(ctx, device.ID, s.now()); err != nil && !errors.Is(err, store.ErrDeviceState) {
				return nil, err
			}
		}
	}
	if ineligible {
		return nil, refusal(403, "not_approved", "this device is not approved for this feed")
	}
	session := randomText(32)
	digest := sha256.Sum256([]byte(session))
	grant := store.NewGrant{
		ID: s.newID(), SessionDigest: digest[:], ExpiresAt: s.now().Add(s.settings.GrantLifetime),
	}
	device, err := s.store.Redeem(ctx, asked.Invitation, s.now(), verify, grant)
	var problem *Problem
	switch {
	case errors.As(err, &problem):
		return nil, problem
	case errors.Is(err, store.ErrNoInvitation):
		return nil, refusal(404, "unknown_invitation", "no invitation of that token for this device")
	case errors.Is(err, store.ErrInvitationUsed):
		return nil, refusal(409, "invitation_used", "that invitation was already redeemed")
	case errors.Is(err, store.ErrInvitationStale):
		return nil, refusal(409, "invitation_superseded", "a newer invitation replaced that one")
	case errors.Is(err, store.ErrInvitationExpired):
		return nil, refusal(410, "invitation_expired",
			"that invitation expired; the publisher can issue another while the device is approved")
	case errors.Is(err, store.ErrDeviceState):
		return nil, refusal(403, "not_approved", "this device is not approved for this feed")
	case err != nil:
		return nil, err
	}
	answer := &Session{RequestID: device.ID, Session: session, GrantID: grant.ID, Until: grant.ExpiresAt}
	if s.syncer != nil {
		answer.Synced = s.syncer.SyncNow(ctx, grant.ID)
	}
	s.log.Info("an approved device redeemed its invitation", "request", device.ID,
		"grant", grant.ID, "gateway_synced", answer.Synced)
	return answer, nil
}

func (s *Service) deviceForInvitation(ctx context.Context, token string) (*store.AccessDevice, error) {
	return s.store.DeviceForInvitation(ctx, token)
}

// --- the operator's decisions --------------------------------------------------

// Devices lists every request and its state.
func (s *Service) Devices(ctx context.Context) ([]store.AccessDevice, error) {
	return s.store.Devices(ctx)
}

// Approve approves a pending request and issues its invitation.
func (s *Service) Approve(ctx context.Context, requestID, by string) (*store.AccessDevice, error) {
	device, err := s.store.Decide(ctx, requestID, true, by, s.now(), s.invitation)
	return device, s.decided(err)
}

// Reject rejects a pending request. A rejected device receives no grant.
func (s *Service) Reject(ctx context.Context, requestID, by string) (*store.AccessDevice, error) {
	device, err := s.store.Decide(ctx, requestID, false, by, s.now(), s.invitation)
	return device, s.decided(err)
}

// Reissue gives an approved device a fresh invitation and supersedes the old one.
func (s *Service) Reissue(ctx context.Context, requestID string) (*store.AccessDevice, error) {
	device, err := s.store.Reissue(ctx, requestID, s.now(), s.invitation)
	return device, s.decided(err)
}

// Revoke revokes one device. The gateway is told by the syncer; until it confirms, the device's
// state says the revocation is pending there.
func (s *Service) Revoke(ctx context.Context, requestID string) error {
	err := s.decided(s.store.Revoke(ctx, requestID, s.now()))
	if err == nil && s.syncer != nil {
		s.syncer.Wake()
	}
	return err
}

// RevokeWallet revokes every device of one wallet.
func (s *Service) RevokeWallet(ctx context.Context, wallet string) (int, error) {
	if _, err := WalletKey(wallet); err != nil {
		return 0, refusal(400, "bad_wallet", "wallet must be a base58 Solana address")
	}
	revoked, err := s.store.RevokeWallet(ctx, wallet, s.now())
	if err == nil && s.syncer != nil {
		s.syncer.Wake()
	}
	return revoked, err
}

func (s *Service) decided(err error) error {
	switch {
	case errors.Is(err, store.ErrNoDevice):
		return refusal(404, "unknown_request", "no request of that ID")
	case errors.Is(err, store.ErrDeviceState):
		return refusal(409, "wrong_state", "that request is not in a state that allows this")
	}
	return err
}

// randomText is n random bytes written as base64url, for nonces, invitations and sessions.
func randomText(n int) string {
	raw := make([]byte, n)
	if _, err := rand.Read(raw); err != nil {
		panic("access: no randomness available: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(raw)
}
