// Command feed-gatewayctl registers the publishers a feed gateway accepts, rotates and revokes
// their credentials, and forgets one entirely (SEE-90,
// docs/wiki/feed-gateway.md#registering-a-publisher).
//
// It is a local tool, and since SEE-141 it is no longer the only way: the gateway can also serve a
// password-protected admin page to its operator. The two do the same things through the same store
// and the same semantics, and each sees the other's work. This one stays because it is the recovery
// path — it needs no listener, no password and no browser, only the file — and because there are
// deployments that will never expose an administrative route at all.
//
// Neither is an API. There is still no publisher-facing or phone-facing method that could register
// anything however a request were authenticated, and an ordinary publishing credential grants no
// administrative authority anywhere.
//
// A credential is shown once, when it is created, and only its SHA-256 is stored — the same thing
// the sidecar does with a phone's credential. Losing one means rotating it, not recovering it.
//
//	feed-gatewayctl register --server <uuid> --label <note> [--host <url>]
//	feed-gatewayctl rotate   --server <uuid> [--label <note>]
//	feed-gatewayctl revoke   --credential <id>
//	feed-gatewayctl revoke   --server <uuid> --all
//	feed-gatewayctl list     [--server <uuid>]
//	feed-gatewayctl forget   --server <uuid> --yes
//	feed-gatewayctl password [--password <text>]
//
// The database is --database, or BROADCAST_DATABASE_PATH, which is the same variable the gateway
// reads: pointing the two at different files is the one mistake that would look like a credential
// that does not work.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/admin"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}

const usage = `feed-gatewayctl manages the publishers a feed gateway accepts.

  register --server <uuid> [--label <note>] [--host <url>]
                                              register a publisher and print one credential
  rotate   --server <uuid> [--label <note>]   add a second credential, so the first can be retired
  revoke   --credential <id>                  end one credential
  revoke   --server <uuid> --all              end every credential a publisher holds
  list     [--server <uuid>]                  what is registered, and which credentials exist
  forget   --server <uuid> --yes              remove a publisher and everything it published
  password [--password <text>]                print a hash for BROADCAST_ADMIN_PASSWORD_HASH

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
	credentialID := flags.String("credential", "", "a credential ID, as `list` prints it")
	host := flags.String("host", "",
		"the publisher's own base URL, as a note: never fetched, and never an authority")
	all := flags.Bool("all", false, "with revoke: every credential this publisher holds")
	yes := flags.Bool("yes", false, "with forget: confirm that documents will be deleted")
	password := flags.String("password", "",
		"with password: the administrator password to hash; omitted, it is read from stdin")
	if err := flags.Parse(arguments); err != nil {
		return err
	}

	// The one command that touches no database: it turns a password into the hash a deployment
	// configures, so an operator never has to put the password itself anywhere (SEE-141).
	if command == "password" {
		return hashPassword(*password, out)
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
		secret, hash := credential.New()
		note := *label
		if note == "" {
			note = command + " " + now.UTC().Format(time.RFC3339)
		}
		var issued string
		if command == "register" {
			// The same validation the admin page applies, from the same function: a host is a note
			// about which developer a registration belongs to, and nothing here ever fetches it.
			recorded, hostErr := admin.Host(*host)
			if hostErr != nil {
				return fmt.Errorf("--host %v", hostErr)
			}
			issued, err = documents.Register(ctx, storage.Registration{
				ServerID: *serverID, Label: note, Host: recorded,
			}, hash, now)
			if errors.Is(err, storage.ErrPublisherExists) {
				return fmt.Errorf("%s is already registered; use `rotate --server %s` to add a "+
					"credential to it", *serverID, *serverID)
			}
		} else {
			if strings.TrimSpace(*host) != "" {
				return fmt.Errorf("--host belongs to `register`: rotation adds a credential and " +
					"changes nothing else about a publisher")
			}
			issued, err = documents.AddCredential(ctx, *serverID, note, hash, now)
		}
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "publisher   %s\n", *serverID)
		fmt.Fprintf(out, "channel     %s\n", rules.ChannelFor(*serverID))
		fmt.Fprintf(out, "credential  %s\n", issued)
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
		case *credentialID != "":
			revoked, err := documents.Revoke(ctx, *credentialID, now)
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
			if publisher.Host != "" {
				fmt.Fprintf(out, "%*s  host %s\n", len(publisher.ServerID), "", publisher.Host)
			}
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

// hashPassword turns the operator's chosen password into the one line a deployment configures.
// The password itself is never written anywhere by this command: it is read, stretched and
// forgotten, and what is printed cannot be turned back into it.
//
// Reading from standard input is the usual way, because an argument is in the shell's history and
// in every process list on the machine while it runs; --password exists for the automated case and
// says so.
func hashPassword(given string, out io.Writer) error {
	chosen := given
	if strings.TrimSpace(chosen) == "" {
		read, err := io.ReadAll(os.Stdin)
		if err != nil {
			return fmt.Errorf("read the password: %w", err)
		}
		chosen = strings.TrimRight(string(read), "\r\n")
	}
	hash, err := credential.HashPassword(chosen)
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "%s\n", hash)
	fmt.Fprint(out, "\nSet that as BROADCAST_ADMIN_PASSWORD_HASH. It is the whole of what the "+
		"gateway keeps about\nthe password, and the admin surface does not exist at all until "+
		"it is configured.\nKeep the password itself in a password manager: it cannot be "+
		"recovered from this.\n")
	return nil
}
