package rules

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// The publisher these tests are about, and one that is not it. Both are lowercase UUIDs, because
// that is the only shape an identity has anywhere in this protocol.
//
// They are the same identities the phone's own tests use (apps/android/.../servers/Manifests.kt), so a
// document from one side can be read by the other without translating anything.
const (
	publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	stranger  = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	proposalA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
)

const gatewayURL = "https://feeds.example.com"

var published = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

func expectation() Expectation {
	return Expectation{ServerID: publisher, GatewayURL: gatewayURL}
}

// A manifest as a well-behaved publisher sends one.
func manifest(change ...func(*serverv1.ServerManifest)) *serverv1.ServerManifest {
	message := &serverv1.ServerManifest{
		ServerId:         publisher,
		ProtocolVersion:  Protocol,
		SettingsRevision: 3,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		RequiredPlugins: []*serverv1.PluginRequirement{
			{PluginId: "jupiter.swap", MinContract: 1, MaxContract: 1},
		},
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		},
		DisplayName: "Copy trading",
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: gatewayURL,
			Channel:    ChannelFor(publisher),
		}},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

// A proposal as a well-behaved publisher sends one.
func proposal(change ...func(*proposalv1.Proposal)) *proposalv1.Proposal {
	message := &proposalv1.Proposal{
		ServerId:      publisher,
		Channel:       ChannelFor(publisher),
		ProposalId:    proposalA,
		Revision:      4,
		Operation:     "swap",
		PluginId:      "jupiter.swap",
		Status:        proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:     timestamppb.New(published),
		UpdatedAt:     timestamppb.New(published.Add(30 * time.Minute)),
		ExpiresAt:     timestamppb.New(published.Add(3 * time.Hour)),
		PublisherNote: "Ротация в USDC 📉",
		Values: []*proposalv1.ProposalValue{
			{Key: "input_mint", Text: "So11111111111111111111111111111111111111112"},
			{Key: "published_price", Text: "139420000"},
		},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

func TestAManifestTheGatewayAccepts(t *testing.T) {
	stored, fault := Manifest(manifest(), expectation())
	if fault != nil {
		t.Fatalf("a well-formed manifest was refused: %v", fault.Problem)
	}
	// Rebuilt, not relayed: what comes back is what was validated, field for field.
	if !proto.Equal(stored, manifest()) {
		t.Fatalf("the stored manifest is not the one that was published:\n%v", stored)
	}
}

func TestEveryManifestRuleHasItsOwnAnswer(t *testing.T) {
	for _, one := range []struct {
		name    string
		message *serverv1.ServerManifest
		problem gatewayv1.GatewayProblem
	}{
		{"no protocol", manifest(func(m *serverv1.ServerManifest) {
			m.ProtocolVersion = 0
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL},
		{"a contract this gateway does not serve", manifest(func(m *serverv1.ServerManifest) {
			m.ProtocolVersion = 2
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL},
		{"an identity that is not a UUID", manifest(func(m *serverv1.ServerManifest) {
			m.ServerId = "copytrading"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID},
		{"another publisher's identity", manifest(func(m *serverv1.ServerManifest) {
			m.ServerId = stranger
			m.Reference = &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
				GatewayUrl: gatewayURL, Channel: ChannelFor(stranger),
			}}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER},
		{"a direct server", manifest(func(m *serverv1.ServerManifest) {
			m.Mode = serverv1.ConnectionMode_CONNECTION_MODE_DIRECT
			m.Reference = &serverv1.ServerManifest_Direct{
				Direct: &serverv1.DirectServer{Url: "https://somewhere.example.com"},
			}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED},
		{"no mode at all", manifest(func(m *serverv1.ServerManifest) {
			m.Mode = serverv1.ConnectionMode_CONNECTION_MODE_UNSPECIFIED
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED},
		{"a feed with no reference", manifest(func(m *serverv1.ServerManifest) {
			m.Reference = nil
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED},
		{"another gateway's origin", manifest(func(m *serverv1.ServerManifest) {
			m.GetFeed().GatewayUrl = "https://someone-elses-gateway.example.com"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_GATEWAY},
		{"another publisher's channel", manifest(func(m *serverv1.ServerManifest) {
			m.GetFeed().Channel = ChannelFor(stranger)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL},
		{"a channel that is not one", manifest(func(m *serverv1.ServerManifest) {
			m.GetFeed().Channel = "everyone"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL},
		{"no revision", manifest(func(m *serverv1.ServerManifest) {
			m.SettingsRevision = 0
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION},
		{"a revision no phone could order", manifest(func(m *serverv1.ServerManifest) {
			m.SettingsRevision = MaxRevision + 1
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION},
		{"a plugin name that is not one", manifest(func(m *serverv1.ServerManifest) {
			m.RequiredPlugins[0].PluginId = "https://plugins.example.com/swap.wasm"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN},
		{"an empty contract range", manifest(func(m *serverv1.ServerManifest) {
			m.RequiredPlugins[0].MinContract = 2
			m.RequiredPlugins[0].MaxContract = 1
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN},
		{"a contract range starting at zero", manifest(func(m *serverv1.ServerManifest) {
			m.RequiredPlugins[0].MinContract = 0
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN},
		{"the same plugin twice", manifest(func(m *serverv1.ServerManifest) {
			m.RequiredPlugins = append(m.RequiredPlugins,
				&serverv1.PluginRequirement{PluginId: "jupiter.swap", MinContract: 1, MaxContract: 2})
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_DUPLICATE_PLUGIN},
		{"more plugins than a manifest may name", manifest(func(m *serverv1.ServerManifest) {
			m.RequiredPlugins = nil
			for i := 0; i <= MaxRequiredPlugins; i++ {
				m.RequiredPlugins = append(m.RequiredPlugins, &serverv1.PluginRequirement{
					PluginId:    fmt.Sprintf("jupiter.p%d", i),
					MinContract: 1, MaxContract: 1,
				})
			}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PLUGINS},
		{"no environment", manifest(func(m *serverv1.ServerManifest) {
			m.Environments = nil
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT},
		{"an unspecified environment", manifest(func(m *serverv1.ServerManifest) {
			m.Environments = []serverv1.ServerEnvironment{
				serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED,
			}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT},
		{"the same environment twice", manifest(func(m *serverv1.ServerManifest) {
			m.Environments = []serverv1.ServerEnvironment{
				serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
				serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
			}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT},
		{"a name longer than a label", manifest(func(m *serverv1.ServerManifest) {
			m.DisplayName = strings.Repeat("n", MaxNameBytes+1)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME},
		{"a name with a line break in it", manifest(func(m *serverv1.ServerManifest) {
			m.DisplayName = "Copy trading\nSigned: your bank"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME},
		{"a name padded to look centred", manifest(func(m *serverv1.ServerManifest) {
			m.DisplayName = "   Copy trading   "
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME},
	} {
		t.Run(one.name, func(t *testing.T) {
			stored, fault := Manifest(one.message, expectation())
			if stored != nil || fault == nil {
				t.Fatalf("%s was accepted", one.name)
			}
			if fault.Problem != one.problem {
				t.Fatalf("%s answered %v, expected %v", one.name, fault.Problem, one.problem)
			}
			if fault.Field == "" {
				t.Fatalf("%s named no field", one.name)
			}
		})
	}
}

func TestAProposalTheGatewayAccepts(t *testing.T) {
	stored, fault := Proposal(proposal(), expectation())
	if fault != nil {
		t.Fatalf("a well-formed proposal was refused: %v", fault.Problem)
	}
	if !proto.Equal(stored, proposal()) {
		t.Fatalf("the stored proposal is not the one that was published:\n%v", stored)
	}
}

func TestEveryProposalRuleHasItsOwnAnswer(t *testing.T) {
	for _, one := range []struct {
		name    string
		message *proposalv1.Proposal
		problem gatewayv1.GatewayProblem
	}{
		{"an identity that is not a UUID", proposal(func(p *proposalv1.Proposal) {
			p.ServerId = "copytrading"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID},
		{"another publisher's identity", proposal(func(p *proposalv1.Proposal) {
			p.ServerId = stranger
			p.Channel = ChannelFor(stranger)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER},
		{"another publisher's channel", proposal(func(p *proposalv1.Proposal) {
			p.Channel = ChannelFor(stranger)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL},
		{"a proposal ID that is not a UUID", proposal(func(p *proposalv1.Proposal) {
			p.ProposalId = "todays-swap"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID},
		{"no revision", proposal(func(p *proposalv1.Proposal) {
			p.Revision = 0
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION},
		{"a revision no phone could order", proposal(func(p *proposalv1.Proposal) {
			p.Revision = MaxRevision + 1
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION},
		{"no status", proposal(func(p *proposalv1.Proposal) {
			p.Status = proposalv1.ProposalStatus_PROPOSAL_STATUS_UNSPECIFIED
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STATUS},
		{"a withdrawal dressed as a publication", proposal(func(p *proposalv1.Proposal) {
			p.Status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH},
		{"a provider's name as the operation", proposal(func(p *proposalv1.Proposal) {
			p.Operation = "Jupiter Swap"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_OPERATION},
		{"no operation", proposal(func(p *proposalv1.Proposal) {
			p.Operation = ""
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_OPERATION},
		{"a plugin name that is loadable", proposal(func(p *proposalv1.Proposal) {
			p.PluginId = "/data/plugins/swap.so"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN},
		{"no creation time", proposal(func(p *proposalv1.Proposal) {
			p.CreatedAt = nil
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES},
		{"no expiry", proposal(func(p *proposalv1.Proposal) {
			p.ExpiresAt = nil
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES},
		{"an expiry at its own creation", proposal(func(p *proposalv1.Proposal) {
			p.ExpiresAt = p.GetCreatedAt()
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES},
		{"an update before its creation", proposal(func(p *proposalv1.Proposal) {
			p.UpdatedAt = timestamppb.New(published.Add(-time.Hour))
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES},
		{"a time outside the calendar", proposal(func(p *proposalv1.Proposal) {
			p.ExpiresAt = &timestamppb.Timestamp{Seconds: 1 << 62}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES},
		{"a term key that is not a name", proposal(func(p *proposalv1.Proposal) {
			p.Values[0].Key = "Input Mint"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE},
		{"a term longer than a term", proposal(func(p *proposalv1.Proposal) {
			p.Values[0].Text = strings.Repeat("x", MaxValueTextBytes+1)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE},
		{"a term with a control character in it", proposal(func(p *proposalv1.Proposal) {
			p.Values[0].Text = "139420000\u0000 and something else"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE},
		{"the same term twice", proposal(func(p *proposalv1.Proposal) {
			p.Values = append(p.Values,
				&proposalv1.ProposalValue{Key: "published_price", Text: "1"})
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_DUPLICATE_VALUE},
		{"more terms than a proposal may carry", proposal(func(p *proposalv1.Proposal) {
			p.Values = nil
			for i := 0; i <= MaxValues; i++ {
				p.Values = append(p.Values, &proposalv1.ProposalValue{
					Key:  fmt.Sprintf("term_%d", i),
					Text: "1",
				})
			}
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_VALUES},
		{"a note longer than a note", proposal(func(p *proposalv1.Proposal) {
			p.PublisherNote = strings.Repeat("n", MaxNoteBytes+1)
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NOTE},
		{"a note with a control character in it", proposal(func(p *proposalv1.Proposal) {
			p.PublisherNote = "Approved\u0000 by you"
		}), gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NOTE},
	} {
		t.Run(one.name, func(t *testing.T) {
			stored, fault := Proposal(one.message, expectation())
			if stored != nil || fault == nil {
				t.Fatalf("%s was accepted", one.name)
			}
			if fault.Problem != one.problem {
				t.Fatalf("%s answered %v, expected %v", one.name, fault.Problem, one.problem)
			}
			if fault.Field == "" {
				t.Fatalf("%s named no field", one.name)
			}
		})
	}
}

// A proposal may carry as many terms as the bound allows, and a note with a line break in it: the
// bound is the rule, and prose is prose.
func TestAProposalMayBeAsLargeAsTheBoundsAllow(t *testing.T) {
	message := proposal(func(p *proposalv1.Proposal) {
		p.Values = nil
		for i := 0; i < MaxValues; i++ {
			p.Values = append(p.Values, &proposalv1.ProposalValue{
				Key:  fmt.Sprintf("term_%d", i),
				Text: strings.Repeat("9", MaxValueTextBytes),
			})
		}
		p.PublisherNote = "Two lines.\nThe second one."
	})
	if _, fault := Proposal(message, expectation()); fault != nil {
		t.Fatalf("a proposal at its bounds was refused: %v (%s)", fault.Problem, fault.Field)
	}
}

// The gateway stores what it read, so a field it does not know about cannot reach a subscriber. The
// test is the reverse of that: every field it *does* know about has to survive, or a document would
// be quietly trimmed on its way through.
func TestNothingTheGatewayUnderstandsIsLostAndNothingElseSurvives(t *testing.T) {
	message := proposal()
	message.ProtoReflect().SetUnknown([]byte{
		// Field 99, a length-delimited string: what an attacker or a confused publisher would use
		// to smuggle something the contract has no room for.
		0x9a, 0x06, 0x06, 'w', 'a', 'l', 'l', 'e', 't',
	})
	stored, fault := Proposal(message, expectation())
	if fault != nil {
		t.Fatalf("an unknown field made the whole proposal unreadable: %v", fault.Problem)
	}
	if len(stored.ProtoReflect().GetUnknown()) != 0 {
		t.Fatal("the gateway kept a field it does not understand, and would relay it")
	}
	bytes, err := proto.Marshal(stored)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(bytes), "wallet") {
		t.Fatal("what was smuggled in survived into the stored document")
	}
	// And the rest of it is untouched.
	if !proto.Equal(stored, proposal()) {
		t.Fatalf("the document changed on its way through:\n%v", stored)
	}

	same := manifest()
	same.ProtoReflect().SetUnknown([]byte{0x9a, 0x06, 0x06, 'w', 'a', 'l', 'l', 'e', 't'})
	kept, fault := Manifest(same, expectation())
	if fault != nil {
		t.Fatalf("an unknown field made the whole manifest unreadable: %v", fault.Problem)
	}
	if len(kept.ProtoReflect().GetUnknown()) != 0 {
		t.Fatal("the gateway kept a manifest field it does not understand")
	}
}

// Two encodings of the same instant are one document, so "the same revision with the same content"
// is a question about content and not about how a publisher's runtime wrote a timestamp.
func TestTheSameInstantWrittenTwoWaysIsOneDocument(t *testing.T) {
	first, fault := Proposal(proposal(), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	second, fault := Proposal(proposal(func(p *proposalv1.Proposal) {
		p.CreatedAt = &timestamppb.Timestamp{Seconds: published.Unix(), Nanos: 0}
	}), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	if decision, fault := AdvanceProposal(first, second); fault != nil || decision != Unchanged {
		t.Fatalf("the same document was read as a change: %v %v", decision, fault)
	}
}

func TestWhatARevisionMeansForAProposal(t *testing.T) {
	held, fault := Proposal(proposal(), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}

	t.Run("the same revision with the same content is a retry", func(t *testing.T) {
		next, _ := Proposal(proposal(), expectation())
		decision, fault := AdvanceProposal(held, next)
		if fault != nil || decision != Unchanged {
			t.Fatalf("a retry was not one: %v %v", decision, fault)
		}
	})

	t.Run("the same revision with different content is a contradiction", func(t *testing.T) {
		next, _ := Proposal(proposal(func(p *proposalv1.Proposal) {
			p.Values[1].Text = "999999999"
		}), expectation())
		_, fault := AdvanceProposal(held, next)
		if fault == nil ||
			fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT {
			t.Fatalf("a contradiction was accepted: %v", fault)
		}
		if fault.Held != held.GetRevision() {
			t.Fatalf("the answer did not say what is held: %d", fault.Held)
		}
	})

	t.Run("a lower revision is late", func(t *testing.T) {
		next, _ := Proposal(proposal(func(p *proposalv1.Proposal) {
			p.Revision = 3
		}), expectation())
		_, fault := AdvanceProposal(held, next)
		if fault == nil ||
			fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION {
			t.Fatalf("a stale publication was accepted: %v", fault)
		}
	})

	t.Run("a higher revision is the terms moving", func(t *testing.T) {
		next, _ := Proposal(proposal(func(p *proposalv1.Proposal) {
			p.Revision = 5
			p.Values[1].Text = "141000000"
			p.UpdatedAt = timestamppb.New(published.Add(time.Hour))
		}), expectation())
		decision, fault := AdvanceProposal(held, next)
		if fault != nil || decision != Stored {
			t.Fatalf("a new revision was refused: %v %v", decision, fault)
		}
	})

	t.Run("a creation time cannot move", func(t *testing.T) {
		next, _ := Proposal(proposal(func(p *proposalv1.Proposal) {
			p.Revision = 5
			p.CreatedAt = timestamppb.New(published.Add(time.Hour))
			p.UpdatedAt = timestamppb.New(published.Add(time.Hour))
		}), expectation())
		_, fault := AdvanceProposal(held, next)
		if fault == nil || fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES {
			t.Fatalf("a proposal was allowed to have begun at a different time: %v", fault)
		}
	})

	t.Run("nothing is published over a withdrawal", func(t *testing.T) {
		withdrawn, fault := Cancelled(held, 9, published.Add(2*time.Hour))
		if fault != nil {
			t.Fatal(fault.Problem)
		}
		next, _ := Proposal(proposal(func(p *proposalv1.Proposal) { p.Revision = 10 }), expectation())
		_, fault = AdvanceProposal(withdrawn, next)
		if fault == nil || fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED {
			t.Fatalf("a withdrawn proposal was re-opened: %v", fault)
		}
	})
}

func TestWhatARevisionMeansForAManifest(t *testing.T) {
	held, fault := Manifest(manifest(), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	for _, one := range []struct {
		name     string
		next     *serverv1.ServerManifest
		decision Decision
		problem  gatewayv1.GatewayProblem
	}{
		{"a retry", manifest(), Unchanged,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNSPECIFIED},
		{"new settings", manifest(func(m *serverv1.ServerManifest) {
			m.SettingsRevision = 4
			m.DisplayName = "Copy trading (beta)"
		}), Stored, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNSPECIFIED},
		{"different settings at the same revision", manifest(func(m *serverv1.ServerManifest) {
			m.DisplayName = "Something else"
		}), Stored, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT},
		{"a late retry", manifest(func(m *serverv1.ServerManifest) {
			m.SettingsRevision = 2
		}), Stored, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION},
		// The one field a higher revision cannot move (SEE-97). Everything else about this
		// document may change; what the server promises when the owner approves may not, or a
		// phone that added a demonstration would be holding a production feed without being asked.
		{"a sandbox server that publishes itself as production", manifest(
			func(m *serverv1.ServerManifest) {
				m.SettingsRevision = 4
				m.Environments = []serverv1.ServerEnvironment{
					serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
				}
			}), Stored, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_ENVIRONMENT},
		{"one that adds an environment to the one it had", manifest(
			func(m *serverv1.ServerManifest) {
				m.SettingsRevision = 4
				m.Environments = append(m.Environments,
					serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX)
			}), Stored, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_ENVIRONMENT},
	} {
		t.Run(one.name, func(t *testing.T) {
			next, fault := Manifest(one.next, expectation())
			if fault != nil {
				t.Fatal(fault.Problem)
			}
			decision, fault := AdvanceManifest(held, next)
			if one.problem == gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNSPECIFIED {
				if fault != nil {
					t.Fatalf("%s was refused: %v", one.name, fault.Problem)
				}
				if decision != one.decision {
					t.Fatalf("%s was %v", one.name, decision)
				}
				return
			}
			if fault == nil || fault.Problem != one.problem {
				t.Fatalf("%s answered %v, expected %v", one.name, fault, one.problem)
			}
		})
	}
}

// The promise is the set, not the order a publisher happened to write it in: a document that lists
// the same two environments the other way round has changed nothing, and refusing it would make a
// publisher's serialization order part of its identity (SEE-97).
func TestReorderingTheEnvironmentsChangesNothing(t *testing.T) {
	both := func(first, second serverv1.ServerEnvironment) *serverv1.ServerManifest {
		return manifest(func(m *serverv1.ServerManifest) {
			m.Environments = []serverv1.ServerEnvironment{first, second}
		})
	}
	production := serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION
	sandbox := serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX
	held, fault := Manifest(both(production, sandbox), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	reordered := both(sandbox, production)
	reordered.SettingsRevision = 4
	next, fault := Manifest(reordered, expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	decision, fault := AdvanceManifest(held, next)
	if fault != nil {
		t.Fatalf("a reorder was refused: %v", fault.Problem)
	}
	if decision != Stored {
		t.Fatalf("a higher revision was %v", decision)
	}
}

func TestAWithdrawalKeepsTheDocumentAndClosesIt(t *testing.T) {
	held, _ := Proposal(proposal(), expectation())
	at := published.Add(90 * time.Minute)
	withdrawn, fault := Cancelled(held, 9, at)
	if fault != nil {
		t.Fatalf("a withdrawal was refused: %v", fault.Problem)
	}
	if withdrawn.GetStatus() != proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		t.Fatal("the withdrawal did not close the proposal")
	}
	if withdrawn.GetRevision() != 9 {
		t.Fatalf("the withdrawal is at revision %d", withdrawn.GetRevision())
	}
	if !withdrawn.GetUpdatedAt().AsTime().Equal(at.Truncate(time.Second)) {
		t.Fatalf("the update time is %v", withdrawn.GetUpdatedAt().AsTime())
	}
	// Everything else is the publisher's, exactly as they published it.
	if withdrawn.GetPublisherNote() != held.GetPublisherNote() ||
		len(withdrawn.GetValues()) != len(held.GetValues()) ||
		!withdrawn.GetCreatedAt().AsTime().Equal(held.GetCreatedAt().AsTime()) ||
		!withdrawn.GetExpiresAt().AsTime().Equal(held.GetExpiresAt().AsTime()) {
		t.Fatalf("the withdrawal changed the document:\n%v", withdrawn)
	}
	// And the withdrawal is still a new revision, not a rewrite of the one that stands.
	if _, fault := Cancelled(held, held.GetRevision(), at); fault == nil ||
		fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION {
		t.Fatalf("a withdrawal at the current revision was accepted: %v", fault)
	}
}

func TestWhatAnIdentityLooksLike(t *testing.T) {
	for _, value := range []string{publisher, proposalA} {
		if !IsID(value) {
			t.Fatalf("%q is an identity", value)
		}
	}
	for _, value := range []string{
		"", "copytrading", strings.ToUpper(publisher), publisher + " ",
		"11111111-2222-4333-8444-55555555555", "11111111222243338444555555555555",
	} {
		if IsID(value) {
			t.Fatalf("%q is not an identity", value)
		}
	}
	if ServerOf(ChannelFor(publisher)) != publisher {
		t.Fatal("a channel names its server")
	}
	for _, value := range []string{"", "server/", "server/nope", publisher, "channel/" + publisher} {
		if ServerOf(value) != "" {
			t.Fatalf("%q is not a channel of this gateway's", value)
		}
	}
	for _, value := range []string{"jupiter.swap", "jupiter.prediction", "a.b"} {
		if !IsPluginID(value) {
			t.Fatalf("%q is a plugin ID", value)
		}
	}
	for _, value := range []string{"", "swap", "Jupiter.Swap", "jupiter..swap", "jupiter.swap/x"} {
		if IsPluginID(value) {
			t.Fatalf("%q is not a plugin ID", value)
		}
	}
	for _, value := range []string{"swap", "prediction", "input_mint", "published_price"} {
		if !IsOperation(value) {
			t.Fatalf("%q is an operation name", value)
		}
	}
	for _, value := range []string{"", "Swap", "swap!", strings.Repeat("s", 65)} {
		if IsOperation(value) {
			t.Fatalf("%q is not an operation name", value)
		}
	}
}
