// Command gateway-onboarding is the smallest independent-server onboarding example (SEE-109).
// It works for a website, bot, agent, or terminal: the backend creates the invitation and may hand
// its hosted URL or QR payload to any frontend. The publisher credential never leaves this process.
package main

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/sdk"
)

func main() {
	user := flag.String("user", "onboarding-session-001", "opaque server-scoped user/session reference")
	wait := flag.Bool("wait", false, "wait for SAC to confirm the connection")
	kind := flag.String("kind", "trading", "integration example: trading, prediction, or mcp")
	environment := flag.String("environment", "sandbox", "connection environment: sandbox or production")
	send := flag.Bool("send", false, "after confirmation, send the example's private request")
	flag.Parse()
	profile, err := exampleFor(*kind)
	if err != nil {
		log.Fatal(err)
	}
	served, err := environmentFor(*environment)
	if err != nil {
		log.Fatal(err)
	}

	url, token, serverID := os.Getenv("GATEWAY_URL"), os.Getenv("GATEWAY_TOKEN"), os.Getenv("SERVER_ID")
	client, err := sdk.NewGateway(sdk.GatewayOptions{URL: url, Token: token, ServerID: serverID})
	if err != nil {
		log.Fatal(err)
	}
	ctx := context.Background()
	_, err = client.PublishServerManifest(ctx, &serverv1.ServerManifest{
		ServerId: serverID, ProtocolVersion: 1, SettingsRevision: 1,
		Mode: serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE,
		Reference: &serverv1.ServerManifest_GatewayPrivate{GatewayPrivate: &serverv1.GatewayPrivate{
			GatewayUrl: url,
		}},
		RequiredPlugins: []*serverv1.PluginRequirement{{
			PluginId: profile.plugin, MinContract: 1, MaxContract: 1,
		}},
		Environments: []serverv1.ServerEnvironment{served},
		DisplayName:  profile.name,
	})
	if err != nil {
		log.Fatal(err)
	}

	invitation, err := client.CreateInvitation(ctx, *user, 15*time.Minute)
	if err != nil {
		log.Fatal(err)
	}
	// invitation_url is the shared hosted page for a website, chat bot, or terminal. app_uri is the
	// QR payload and Open-in-SAC target. Both expire; neither contains GATEWAY_TOKEN.
	fmt.Printf("shareable page: %s\nQR / Open in SAC: %s\nexpires: %s\n",
		invitation.GetInvitationUrl(), invitation.GetAppUri(), invitation.GetExpiresAt().AsTime())
	if !*wait {
		return
	}
	connected, err := client.WaitForConnection(ctx, invitation.GetInvitationId(), time.Second)
	if err != nil {
		log.Fatal(err)
	}
	if connected.GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED {
		log.Fatalf("invitation finished as %s", connected.GetStatus())
	}
	fmt.Printf("connected: %s\n", connected.GetConnectionId())
	if !*send {
		return
	}
	requestID, err := randomUUID()
	if err != nil {
		log.Fatal(err)
	}
	record, err := client.SendRequest(ctx, sdk.PrivateRequest{
		RequestID: requestID, Revision: 1, UserRef: *user,
		ConnectionID: connected.GetConnectionId(), ExpiresAt: time.Now().Add(10 * time.Minute),
		Title: profile.title, Description: profile.description,
		CapabilityID: profile.capability, CapabilityVersion: 1, PluginID: profile.plugin,
		Parameters: profile.parameters, OwnerInputs: profile.inputs,
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("request waiting for separate review: %s on connection %s\n",
		record.GetRequest().GetIdentity().GetRequestId(), record.GetConnectionId())
}

func environmentFor(value string) (serverv1.ServerEnvironment, error) {
	switch value {
	case "sandbox":
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX, nil
	case "production":
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION, nil
	default:
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED,
			fmt.Errorf("unknown environment %q (want sandbox or production)", value)
	}
}

type example struct {
	name, title, description, capability, plugin string
	parameters                                   []*requestv2.Value
	inputs                                       []*requestv2.OwnerInput
}

func exampleFor(kind string) (example, error) {
	amount := &requestv2.OwnerInput{Key: "amount", Label: "Amount", Required: true,
		Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT, Minimum: "1"}
	slippage := &requestv2.OwnerInput{Key: "slippage_bps", Label: "Slippage (bps)", Required: true,
		Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_COUNT, Minimum: "1", Maximum: "50"}
	swap := example{name: "Trading bot", title: "Review example swap",
		description: "A private trading request; SAC still prepares and inspects the transaction.",
		capability:  "swap", plugin: "jupiter.swap",
		parameters: []*requestv2.Value{
			textValue("input_mint", "So11111111111111111111111111111111111111112"),
			integerValue("input_decimals", "9"),
			textValue("output_mint", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"),
			integerValue("output_decimals", "6"), integerValue("max_slippage_bps", "50"),
		}, inputs: []*requestv2.OwnerInput{amount, slippage}}
	switch kind {
	case "trading":
		return swap, nil
	case "mcp":
		swap.name = "MCP-backed trading agent"
		swap.title = "Review MCP-triggered swap"
		swap.description = "The backend MCP tool asked through the same SDK; it cannot approve this request."
		return swap, nil
	case "prediction":
		return example{name: "Prediction bot", title: "Review example prediction",
			description: "SAC reads the named market from the provider before preparing any order.",
			capability:  "prediction", plugin: "jupiter.prediction",
			parameters: []*requestv2.Value{
				textValue("market_id", "example-market"),
				textValue("deposit_mint", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"),
				integerValue("deposit_decimals", "6"), integerValue("least_deposit", "5000000"),
			}, inputs: []*requestv2.OwnerInput{
				{Key: "side", Label: "Side", Required: true,
					Kind:    requestv2.OwnerInputKind_OWNER_INPUT_KIND_CHOICE,
					Options: []*requestv2.InputOption{{Value: "yes", Label: "Yes"}, {Value: "no", Label: "No"}}},
				{Key: "stake", Label: "Stake", Required: true,
					Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT, Minimum: "5000000"},
				slippage,
			}}, nil
	default:
		return example{}, fmt.Errorf("unknown kind %q (want trading, prediction, or mcp)", kind)
	}
}

func textValue(key, value string) *requestv2.Value {
	return &requestv2.Value{Key: key, Value: &requestv2.Value_Text{Text: value}}
}

func integerValue(key, value string) *requestv2.Value {
	return &requestv2.Value{Key: key, Value: &requestv2.Value_Integer{Integer: value}}
}

func randomUUID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", value[0:4], value[4:6], value[6:8], value[8:10], value[10:16]), nil
}
