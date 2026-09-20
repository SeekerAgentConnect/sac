package store

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

const (
	server = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	other  = "0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"
)

var now = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

func stamp() Stamp {
	return Stamp{ServerID: server, Environment: "production", GatewayURL: "https://feeds.example.com"}
}

func opened(t *testing.T) *Store {
	t.Helper()
	documents, err := Open(filepath.Join(t.TempDir(), "publisher.db"), stamp())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	return documents
}

func swap() signals.Signal {
	return signals.Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  now.Add(time.Hour),
		Note:       "trimming SOL into USDC",
		Terms: map[string]string{
			signals.InputMint:      signals.WrappedSOL,
			signals.InputDecimals:  "9",
			signals.OutputMint:     "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
			signals.OutputDecimals: "6",
			signals.MaxSlippageBps: "50",
		},
		Operation: "swap",
		PluginID:  "jupiter.swap",
	}
}

// The schema holds a publisher's own documents and nothing about a subscriber. This reads the
// live database rather than the source, so a column added by a migration is caught as surely as
// one added to the string above.
//
// A publisher cannot lose a subscriber's financial history because it never has one: it does not
// learn who reads its channel, and what each owner picks stays on the device that picked it
// (SEE-89, docs/security.md).
func TestNothingAboutASubscriberHasAColumn(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()

	rows, err := documents.reader.QueryContext(ctx,
		`SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	tables := []string{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		tables = append(tables, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	// Six: the four a publisher's own documents need, and the two the Prediction template's
	// discovery keeps (SEE-96). Naming them here rather than counting them is the point — a table
	// added by a migration has to be added to this line, which is where somebody reads what a
	// publisher holds.
	expected := "deployment,discovery,idempotency,manifest,market,signal"
	if strings.Join(tables, ",") != expected {
		t.Fatalf("the tables are %v, expected %s", tables, expected)
	}

	// Every word that would mean this template had started keeping something that is not its own.
	forbidden := []string{
		"wallet", "address", "amount", "quantity", "balance", "decision", "approval",
		"approved", "signature", "signed", "result", "execution", "subscriber", "device",
		"phone", "user", "account", "token", "fcm", "push", "firebase",
	}
	for _, table := range tables {
		columns, err := documents.reader.QueryContext(ctx, `SELECT name FROM pragma_table_info(?)`,
			table)
		if err != nil {
			t.Fatal(err)
		}
		for columns.Next() {
			var column string
			if err := columns.Scan(&column); err != nil {
				t.Fatal(err)
			}
			for _, word := range forbidden {
				if strings.Contains(column, word) {
					t.Fatalf("%s.%s: a publisher holds no %s. Nothing about a subscriber may "+
						"have a column here (docs/security.md)", table, column, word)
				}
			}
		}
		if err := columns.Err(); err != nil {
			t.Fatal(err)
		}
		_ = columns.Close()
	}
}

// A database is a publisher's identity as much as its credential is, and sandbox and production
// are different promises. Both are stamped into the file, because the way they get mixed up is a
// copied compose file pointed at a volume that already exists.
func TestTheFileRemembersWhoseItIsAndWhichEnvironment(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	first, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	t.Run("another publisher is refused", func(t *testing.T) {
		moved := stamp()
		moved.ServerID = other
		if _, err := Open(path, moved); !errors.Is(err, ErrOtherServer) {
			t.Fatalf("expected ErrOtherServer, got %v", err)
		}
	})
	t.Run("the other environment is refused", func(t *testing.T) {
		moved := stamp()
		moved.Environment = "sandbox"
		if _, err := Open(path, moved); !errors.Is(err, ErrOtherEnvironment) {
			t.Fatalf("expected ErrOtherEnvironment, got %v", err)
		}
	})
	t.Run("a gateway that moved is recorded, not refused", func(t *testing.T) {
		moved := stamp()
		moved.GatewayURL = "https://feeds.example.net"
		documents, err := Open(path, moved)
		if err != nil {
			t.Fatalf("a deployment may move gateway: %v", err)
		}
		defer func() { _ = documents.Close() }()
		var held string
		if err := documents.reader.QueryRow(
			`SELECT gateway_url FROM deployment WHERE id = 1`).Scan(&held); err != nil {
			t.Fatal(err)
		}
		if held != "https://feeds.example.net" {
			t.Fatalf("the file still says %s", held)
		}
	})
}

func TestACreatedSignalIsAtRevisionOneAndIsNotPublishedYet(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()

	record, held, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	if held {
		t.Fatal("a first create was answered as a replay")
	}
	if record.Signal.Revision != 1 {
		t.Fatalf("revision %d: a first publication is revision 1, and zero is never published",
			record.Signal.Revision)
	}
	if record.Signal.Status != signals.Open {
		t.Fatalf("status %q", record.Signal.Status)
	}
	if record.Signal.Fingerprint == "" {
		t.Fatal("no fingerprint was computed")
	}
	if state := record.Publication.State(record.Signal); state != "pending" {
		t.Fatalf("publication %s: nothing has been published yet", state)
	}

	due, err := documents.Due(ctx, now, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(due) != 1 || due[0].Signal.ProposalID != record.Signal.ProposalID {
		t.Fatalf("the outbox holds %d signals", len(due))
	}
	pending, err := documents.Pending(ctx)
	if err != nil || pending != 1 {
		t.Fatalf("pending %d (%v)", pending, err)
	}
}

// An idempotency key belongs to one signal. The same key with the same request is the same signal;
// the same key with a different request is two signals asking to be one, which is a conflict and
// not a duplicate.
func TestAnIdempotencyKeyBelongsToOneSignal(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	if _, held, err := documents.Replay(ctx, "key-1", "request-1"); err != nil || held {
		t.Fatalf("unused key: held %v (%v)", held, err)
	}

	first, _, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	lookedUp, held, err := documents.Replay(ctx, "key-1", "request-1")
	if err != nil || !held || lookedUp.Signal.ProposalID != first.Signal.ProposalID {
		t.Fatalf("replay lookup: held %v, signal %s (%v)", held,
			lookedUp.Signal.ProposalID, err)
	}
	if _, _, err := documents.Replay(ctx, "key-1", "request-2"); !errors.Is(err, ErrKeyReused) {
		t.Fatalf("replay lookup: expected ErrKeyReused, got %v", err)
	}

	// The same call again, which is what a caller that lost its answer sends. A second identity
	// would be a second signal on everyone's phone.
	second := swap()
	second.ProposalID = "1e2f3a4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b"
	replay, held, err := documents.Create(ctx, "key-1", "request-1", second)
	if err != nil {
		t.Fatal(err)
	}
	if !held {
		t.Fatal("a replay was not recognized")
	}
	if replay.Signal.ProposalID != first.Signal.ProposalID {
		t.Fatalf("the replay created %s beside %s", replay.Signal.ProposalID,
			first.Signal.ProposalID)
	}

	if _, _, err := documents.Create(ctx, "key-1", "request-2", second); !errors.Is(err,
		ErrKeyReused) {
		t.Fatalf("expected ErrKeyReused, got %v", err)
	}
	// And nothing was stored for it.
	all, err := documents.Signals(ctx)
	if err != nil || len(all) != 1 {
		t.Fatalf("%d signals are held (%v)", len(all), err)
	}

	// A different key with the same content is a different signal, deliberately: the key is what
	// says "this is the same statement", and content cannot say it — a trader may well publish the
	// same pair twice.
	third := swap()
	third.ProposalID = "2f3a4b5c-6d7e-4f8a-8b9c-1d2e3f4a5b6c"
	if _, held, err := documents.Create(ctx, "key-2", "request-1", third); err != nil || held {
		t.Fatalf("held %v (%v)", held, err)
	}
	if all, err := documents.Signals(ctx); err != nil || len(all) != 2 {
		t.Fatalf("%d signals are held (%v)", len(all), err)
	}
}

// An update that changes nothing publishes nothing. A strategy engine may re-post its current view
// as often as it likes, and not one phone is woken by it.
func TestAnUpdateThatChangesNothingMovesNoRevision(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}

	same := swap()
	record, changed, err := documents.Update(ctx, created.Signal.ProposalID, same,
		now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("an identical statement was treated as a change")
	}
	if record.Signal.Revision != 1 || !record.Signal.UpdatedAt.Equal(now) {
		t.Fatalf("revision %d, updated %s", record.Signal.Revision, record.Signal.UpdatedAt)
	}

	moved := swap()
	moved.Title = "Will SOL close above $200?"
	moved.Terms[signals.MaxSlippageBps] = "80"
	record, changed, err = documents.Update(ctx, created.Signal.ProposalID, moved,
		now.Add(2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if !changed || record.Signal.Revision != 2 {
		t.Fatalf("changed %v, revision %d", changed, record.Signal.Revision)
	}
	if record.Signal.Terms[signals.MaxSlippageBps] != "80" {
		t.Fatalf("terms %v", record.Signal.Terms)
	}
	if record.Signal.Title != moved.Title {
		t.Fatalf("title %q, expected %q", record.Signal.Title, moved.Title)
	}
	if !record.Signal.CreatedAt.Equal(now) {
		t.Fatalf("the creation time moved to %s: it says when this proposal began, and a "+
			"publication that changed it would describe a different proposal", record.Signal.CreatedAt)
	}
	if !record.Signal.UpdatedAt.Equal(now.Add(2 * time.Minute)) {
		t.Fatalf("updated %s", record.Signal.UpdatedAt)
	}
}

// A withdrawal is final. A phone that acted on a proposal keeps its own record for ever and must
// never be shown the same identity as open again, so a withdrawn signal cannot be updated and
// withdrawing it twice is not an event.
func TestAWithdrawalIsFinal(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	id := created.Signal.ProposalID

	record, changed, err := documents.Cancel(ctx, id, now.Add(time.Minute))
	if err != nil || !changed {
		t.Fatalf("changed %v (%v)", changed, err)
	}
	if record.Signal.Status != signals.Cancelled || record.Signal.Revision != 2 {
		t.Fatalf("status %q at revision %d", record.Signal.Status, record.Signal.Revision)
	}
	if record.Signal.Fingerprint == created.Signal.Fingerprint {
		t.Fatal("a withdrawal left the fingerprint alone, so a retry would look like a change")
	}

	again, changed, err := documents.Cancel(ctx, id, now.Add(2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if changed || again.Signal.Revision != 2 {
		t.Fatalf("changed %v at revision %d", changed, again.Signal.Revision)
	}

	if _, _, err := documents.Update(ctx, id, swap(), now); !errors.Is(err, ErrCancelled) {
		t.Fatalf("expected ErrCancelled, got %v", err)
	}
}

func TestNothingIsHeldUnderAnUnknownID(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	for _, call := range []struct {
		name string
		run  func() error
	}{
		{"read", func() error { _, err := documents.Signal(ctx, other); return err }},
		{"update", func() error { _, _, err := documents.Update(ctx, other, swap(), now); return err }},
		{"cancel", func() error { _, _, err := documents.Cancel(ctx, other, now); return err }},
		{"retry", func() error { _, _, err := documents.Retry(ctx, other, now); return err }},
	} {
		t.Run(call.name, func(t *testing.T) {
			if err := call.run(); !errors.Is(err, ErrNoSignal) {
				t.Fatalf("expected ErrNoSignal, got %v", err)
			}
		})
	}
}

// What the gateway confirmed, what it deferred, and what it refused — and the one rule that keeps
// a late answer from confirming a document that has since moved on.
func TestAPublicationIsRecordedAgainstTheRevisionItWasFor(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	id := created.Signal.ProposalID

	// An answer for a revision this signal is no longer at confirms nothing.
	moved := swap()
	moved.Note = "second thoughts"
	if _, _, err := documents.Update(ctx, id, moved, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := documents.Published(ctx, id, 1, ""); err != nil {
		t.Fatal(err)
	}
	record, err := documents.Signal(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	if record.Publication.ConfirmedRevision != 0 {
		t.Fatalf("a late answer for revision 1 confirmed %d while the signal is at %d",
			record.Publication.ConfirmedRevision, record.Signal.Revision)
	}

	if err := documents.Published(ctx, id, 2, ""); err != nil {
		t.Fatal(err)
	}
	record, _ = documents.Signal(ctx, id)
	if record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
	if due, err := documents.Due(ctx, now.Add(time.Hour), 10); err != nil || len(due) != 0 {
		t.Fatalf("%d signals are still due (%v)", len(due), err)
	}
}

func TestADeferredPublicationWaitsAndARefusedOneStops(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	id := created.Signal.ProposalID

	if err := documents.Deferred(ctx, id, now.Add(time.Minute), "the gateway is not answering"); err != nil {
		t.Fatal(err)
	}
	if due, err := documents.Due(ctx, now, 10); err != nil || len(due) != 0 {
		t.Fatalf("a deferred signal was due immediately: %d (%v)", len(due), err)
	}
	due, err := documents.Due(ctx, now.Add(time.Minute), 10)
	if err != nil || len(due) != 1 {
		t.Fatalf("%d signals are due once the delay has passed (%v)", len(due), err)
	}
	if due[0].Publication.Attempts != 1 {
		t.Fatalf("attempts %d", due[0].Publication.Attempts)
	}

	if err := documents.Refused(ctx, id, "foreign_channel", "channel (channel)"); err != nil {
		t.Fatal(err)
	}
	if due, err := documents.Due(ctx, now.Add(time.Hour), 10); err != nil || len(due) != 0 {
		t.Fatalf("a refused signal is retried by itself: %d (%v)", len(due), err)
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 0 {
		t.Fatalf("pending %d (%v): a refused signal is not waiting, it is refused", pending, err)
	}
	record, _ := documents.Signal(ctx, id)
	if record.Publication.State(record.Signal) != "refused" ||
		record.Publication.Problem != "foreign_channel" {
		t.Fatalf("%+v", record.Publication)
	}

	// And the one way out of it is somebody asking.
	retried, cleared, err := documents.Retry(ctx, id, now.Add(time.Hour))
	if err != nil || !cleared {
		t.Fatalf("cleared %v (%v)", cleared, err)
	}
	if retried.Publication.Problem != "" || retried.Publication.Attempts != 0 {
		t.Fatalf("%+v", retried.Publication)
	}
	if due, err := documents.Due(ctx, now.Add(time.Hour), 10); err != nil || len(due) != 1 {
		t.Fatalf("%d signals are due after a retry (%v)", len(due), err)
	}
	if _, cleared, err := documents.Retry(ctx, id, now.Add(time.Hour)); err != nil || cleared {
		t.Fatalf("a retry of something that was not refused cleared %v (%v)", cleared, err)
	}
}

// The manifest's revision counts settings changes and nothing else. A restart republishes the same
// revision, which the gateway answers "unchanged", so no phone re-reads a manifest that has not
// changed and nobody is notified.
func TestTheManifestRevisionMovesOnlyWhenTheSettingsDo(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()

	revision, err := documents.ManifestRevision(ctx, "fingerprint-a", now)
	if err != nil || revision != 1 {
		t.Fatalf("revision %d (%v)", revision, err)
	}
	if revision, err := documents.ManifestRevision(ctx, "fingerprint-a", now); err != nil ||
		revision != 1 {
		t.Fatalf("a restart moved the revision to %d (%v)", revision, err)
	}
	if revision, err := documents.ManifestRevision(ctx, "fingerprint-b", now); err != nil ||
		revision != 2 {
		t.Fatalf("settings that changed left the revision at %d (%v)", revision, err)
	}

	// A start is a deliberate act, so it clears a refusal: whatever a previous process was told,
	// the operator who restarted this one is entitled to have it tried again.
	if err := documents.ManifestRefused(ctx, "other_gateway", "gateway_url"); err != nil {
		t.Fatal(err)
	}
	if _, state, err := documents.Manifest(ctx); err != nil || state.Problem == "" {
		t.Fatalf("%+v (%v)", state, err)
	}
	if _, err := documents.ManifestRevision(ctx, "fingerprint-b", now); err != nil {
		t.Fatal(err)
	}
	revision, state, err := documents.Manifest(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if state.Problem != "" || state.Attempts != 0 || revision != 2 {
		t.Fatalf("revision %d, %+v", revision, state)
	}

	if err := documents.ManifestPublished(ctx, 2); err != nil {
		t.Fatal(err)
	}
	if _, state, _ := documents.Manifest(ctx); state.ConfirmedRevision != 2 {
		t.Fatalf("confirmed %d", state.ConfirmedRevision)
	}
	// A confirmation for a revision the manifest is no longer at confirms nothing, as for a
	// signal.
	if _, err := documents.ManifestRevision(ctx, "fingerprint-c", now); err != nil {
		t.Fatal(err)
	}
	if err := documents.ManifestPublished(ctx, 2); err != nil {
		t.Fatal(err)
	}
	if _, state, _ := documents.Manifest(ctx); state.ConfirmedRevision != 2 {
		t.Fatalf("confirmed %d while the manifest is at 3", state.ConfirmedRevision)
	}
}

// Everything survives a restart, which is the whole reason a signal is stored before it is
// published: the answer a caller was given has to outlive the process that gave it.
func TestWhatIsHeldSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	first, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	createdSignal := swap()
	createdSignal.Title = "Will SOL close above $200?"
	created, _, err := first.Create(ctx, "key-1", "request-1", createdSignal)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := first.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}
	if err := first.Deferred(ctx, created.Signal.ProposalID, now, "the gateway is not answering"); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	second, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = second.Close() }()
	held, err := second.Signal(ctx, created.Signal.ProposalID)
	if err != nil {
		t.Fatal(err)
	}
	if held.Signal.Fingerprint != created.Signal.Fingerprint {
		t.Fatal("the signal came back different")
	}
	if held.Signal.Title != createdSignal.Title {
		t.Fatalf("title %q, expected %q", held.Signal.Title, createdSignal.Title)
	}
	if held.Signal.Revision != 1 || held.Publication.ConfirmedRevision != 0 {
		t.Fatalf("revision %d, confirmed %d", held.Signal.Revision,
			held.Publication.ConfirmedRevision)
	}
	if due, err := second.Due(ctx, now, 10); err != nil || len(due) != 1 {
		t.Fatalf("the new process found %d signals to publish (%v)", len(due), err)
	}
	// The same idempotency key still resolves to the same signal, so a caller retrying across a
	// restart is still safe.
	replay, replayed, err := second.Create(ctx, "key-1", "request-1", createdSignal)
	if err != nil || !replayed {
		t.Fatalf("replayed %v (%v)", replayed, err)
	}
	if replay.Signal.ProposalID != created.Signal.ProposalID {
		t.Fatal("the key resolved to another signal")
	}
	if keys, err := second.Keys(ctx, created.Signal.ProposalID); err != nil ||
		strings.Join(keys, ",") != "key-1" {
		t.Fatalf("keys %v (%v)", keys, err)
	}
}

func TestSignalsAreListedNewestFirst(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	for index, moment := range []time.Time{now, now.Add(time.Hour), now.Add(-time.Hour)} {
		signal := swap()
		signal.ProposalID = strings.Replace(signal.ProposalID, "8c9d", []string{"1111", "2222",
			"3333"}[index], 1)
		signal.CreatedAt, signal.UpdatedAt = moment, moment
		signal.ExpiresAt = moment.Add(time.Hour)
		if _, _, err := documents.Create(ctx, "key-"+signal.ProposalID, "request", signal); err != nil {
			t.Fatal(err)
		}
	}
	records, err := documents.Signals(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 3 {
		t.Fatalf("%d signals", len(records))
	}
	for index := 1; index < len(records); index++ {
		if records[index].Signal.CreatedAt.After(records[index-1].Signal.CreatedAt) {
			t.Fatalf("signal %d was created after the one before it", index)
		}
	}
}
