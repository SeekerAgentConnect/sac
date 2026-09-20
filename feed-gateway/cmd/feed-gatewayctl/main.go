// Command feed-gatewayctl registers the publishers a feed gateway accepts, rotates and revokes
// their credentials, and forgets one entirely (SEE-90,
// docs/wiki/feed-gateway.md#registering-a-publisher).
//
// It is a local tool on purpose. Registering a publisher is the one act that grants the ability to
// publish, and there is no network surface for it at all: no administrative API, no account system,
// no invitation flow. The operator runs this on the host that holds the database, and the gateway
// itself has no method that could add a publisher however a request were authenticated.
//
// A credential is shown once, when it is created, and only its SHA-256 is stored — the same thing
// the sidecar does with a phone's credential. Losing one means rotating it, not recovering it.
//
//	feed-gatewayctl register --server <uuid> [--label <note>]
//	feed-gatewayctl rotate   --server <uuid> [--label <note>]
//	feed-gatewayctl revoke   --credential <id>
//	feed-gatewayctl revoke   --server <uuid> --all
//	feed-gatewayctl list     [--server <uuid>]
//	feed-gatewayctl forget   --server <uuid> --yes
//
// The database is --database, or BROADCAST_DATABASE_PATH, which is the same variable the gateway
// reads: pointing the two at different files is the one mistake that would look like a credential
// that does not work.
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}

const usage = `feed-gatewayctl manages the publishers a feed gateway accepts.

  register --server <uuid> [--label <note>]   register a publisher and print one credential
  rotate   --server <uuid> [--label <note>]   add a second credential, so the first can be retired
  revoke   --credential <id>                  end one credential
  revoke   --server <uuid> --all              end every credential a publisher holds
  list     [--server <uuid>]                  what is registered, and which credentials exist
  forget   --server <uuid> --yes              remove a publisher and everything it published

  --database <path>   the gateway's database, or BROADCAST_DATABASE_PATH
`

func run(arguments []string, out io.Writer) error {
	if len(arguments) == 0 {
		fmt.Fprint(out, usage)
		return fmt.Errorf("name a command")
	}
	command, arguments := arguments[0], arguments[1:]

	flags := flag.NewFlagSet("feed-gatewayctl "+command, flag.ContinueOnError)
	database := flags.String("database", os.Getenv("BROADCAST_DATABASE_PATH"),
		"the gateway's database file")
	serverID := flags.String("server", "", "the publisher's server ID (a lowercase UUID)")
	label := flags.String("label", "", "a note for the operator; never served to anyone")
	credential := flags.String("credential", "", "a credential ID, as `list` prints it")
	all := flags.Bool("all", false, "with revoke: every credential this publisher holds")
	yes := flags.Bool("yes", false, "with forget: confirm that documents will be deleted")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if strings.TrimSpace(*database) == "" {
		return fmt.Errorf("--database, or BROADCAST_DATABASE_PATH, must name the gateway's database")
	}

	documents, err := sqlite.Open(*database)
	if err != nil {
		return err
	}
	defer func() { _ = documents.Close() }()
	ctx := context.Background()
	now := time.Now()

	switch command {
	case "register", "rotate":
		if !rules.IsID(*serverID) {
			return fmt.Errorf("--server must be a lowercase UUID, which is what a publisher's " +
				"manifest and every proposal of its own has to name")
		}
		secret, hash := newCredential()
		note := *label
		if note == "" {
			note = command + " " + now.UTC().Format(time.RFC3339)
		}
		if command == "register" {
			err = documents.Register(ctx, *serverID, note, hash, now)
		} else {
			err = documents.AddCredential(ctx, *serverID, note, hash, now)
		}
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "publisher   %s\n", *serverID)
		fmt.Fprintf(out, "channel     %s\n", rules.ChannelFor(*serverID))
		fmt.Fprintf(out, "credential  %s\n", sqlite.CredentialID(hash))
		fmt.Fprintf(out, "\n%s\n\n", secret)
		fmt.Fprint(out, "That credential is shown once and is not stored. Give it to the "+
			"publisher as BROADCAST_CREDENTIAL,\nand keep it out of version control. "+
			"Rotate with `rotate`, then `revoke --credential <id>`.\n")
		return nil

	case "revoke":
		switch {
		case *all:
			if !rules.IsID(*serverID) {
				return fmt.Errorf("--server must be a lowercase UUID")
			}
			revoked, err := documents.RevokeAll(ctx, *serverID, now)
			if err != nil {
				return err
			}
			fmt.Fprintf(out, "revoked %d credential(s) of %s\n", revoked, *serverID)
			// Its documents stay: a publisher that can no longer publish is not a reason for the
			// proposals phones already hold to disappear from the feed they read.
			fmt.Fprint(out, "Its manifest and proposals are still served. Use `forget` to "+
				"remove those too.\n")
			return nil
		case *credential != "":
			revoked, err := documents.Revoke(ctx, *credential, now)
			if err != nil {
				return err
			}
			if revoked == 0 {
				return fmt.Errorf("no credential of that ID is in use")
			}
			fmt.Fprintf(out, "revoked %d credential(s)\n", revoked)
			return nil
		default:
			return fmt.Errorf("revoke needs --credential <id>, or --server <uuid> --all")
		}

	case "list":
		if *serverID != "" {
			credentials, err := documents.Credentials(ctx, *serverID)
			if err != nil {
				return err
			}
			if len(credentials) == 0 {
				fmt.Fprintf(out, "%s has no credentials\n", *serverID)
				return nil
			}
			for _, one := range credentials {
				state := "in use"
				if one.RevokedAt != nil {
					state = "revoked " + one.RevokedAt.Format(time.RFC3339)
				}
				fmt.Fprintf(out, "%s  %-24s  created %s  %s\n", one.ID, one.Label,
					one.CreatedAt.Format(time.RFC3339), state)
			}
			return nil
		}
		publishers, err := documents.Publishers(ctx)
		if err != nil {
			return err
		}
		if len(publishers) == 0 {
			fmt.Fprint(out, "no publishers are registered\n")
			return nil
		}
		for _, publisher := range publishers {
			fmt.Fprintf(out, "%s  %-24s  created %s  %d credential(s) in use\n",
				publisher.ServerID, publisher.Label,
				publisher.CreatedAt.Format(time.RFC3339), publisher.Active)
		}
		return nil

	case "forget":
		if !rules.IsID(*serverID) {
			return fmt.Errorf("--server must be a lowercase UUID")
		}
		if !*yes {
			return fmt.Errorf("forget removes the publisher, its manifest and every proposal it " +
				"published; pass --yes to confirm")
		}
		if err := documents.Forget(ctx, *serverID, rules.ChannelFor(*serverID)); err != nil {
			return err
		}
		fmt.Fprintf(out, "forgot %s and everything it published\n", *serverID)
		// The phones that hold its proposals keep them: what a device decided about a proposal is
		// the device's, and it goes when the owner removes the feed (SEE-89).
		fmt.Fprint(out, "Phones that already read its proposals keep their own copies until "+
			"their owners remove the feed.\n")
		return nil

	default:
		fmt.Fprint(out, usage)
		return fmt.Errorf("unknown command %q", command)
	}
}

// newCredential makes one: 32 random bytes as 43 base64url characters, which is the shape the
// sidecar's phone credential has, and the SHA-256 that is all the gateway keeps.
func newCredential() (string, []byte) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand does not fail on any platform this runs on; if it ever did, printing a
		// predictable credential would be far worse than stopping.
		panic("feed-gatewayctl: no randomness available: " + err.Error())
	}
	secret := base64.RawURLEncoding.EncodeToString(raw)
	sum := sha256.Sum256([]byte(secret))
	return secret, sum[:]
}
