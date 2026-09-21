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
//	feed-gatewayctl register --server <uuid> --label <note> [--host <url>] [--for publish|relay|both]
//	feed-gatewayctl rotate   --server <uuid> [--label <note>] [--for publish|relay]
//	feed-gatewayctl capabilities --server <uuid> --for publish|relay|both|none
//	feed-gatewayctl revoke   --credential <id>
//	feed-gatewayctl revoke   --server <uuid> --all
//	feed-gatewayctl list     [--server <uuid>]
//	feed-gatewayctl forget   --server <uuid> --yes
//	feed-gatewayctl password [--password <text>]
//
// The database is --database, or BROADCAST_DATABASE_PATH, or BROADCAST_DATABASE_URL: the same
// variables the gateway reads, because pointing the two at different databases is the one mistake
// that would look like a credential that does not work. A Postgres URL is recognized by its scheme
// and anything else is a file, so one flag names either (SEE-145).
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
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/postgres"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

// operates is what this tool needs of a store: the operator's own surface, and the close. It is
// the administration contract and nothing else, so no command here can read a publication, a relay
// target or anything a phone authorized.
type operates interface {
	storage.PublisherAdminStore
	Close() error
}

// openStore opens whichever store the operator named. A Postgres URL is recognized by its scheme;
// everything else is a path, which is what it has always been.
func openStore(ctx context.Context, database string) (operates, error) {
	if strings.HasPrefix(database, "postgres://") || strings.HasPrefix(database, "postgresql://") {
		return postgres.Open(ctx, database)
	}
	return sqlite.Open(database)
}

// configuredDatabase is the default for --database. A deployment sets one of the two, and
// configuration refuses both, so reading the path first and the URL after it needs no precedence
// rule of its own.
func configuredDatabase() string {
	if path := strings.TrimSpace(os.Getenv("BROADCAST_DATABASE_PATH")); path != "" {
		return path
	}
	return strings.TrimSpace(os.Getenv("BROADCAST_DATABASE_URL"))
}

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}

const usage = `feed-gatewayctl manages the publishers a feed gateway accepts.

  register --server <uuid> [--label <note>] [--host <url>] [--for publish|relay|both]
                                              register a server and print one credential
  rotate   --server <uuid> [--label <note>] [--for publish|relay]
                                              add a second credential, so the first can be retired
  capabilities --server <uuid> --for publish|relay|both|none
                                              enable or disable what a server may do
  revoke   --credential <id>                  end one credential
  revoke   --server <uuid> --all              end every credential a server holds
  list     [--server <uuid>]                  what is registered, and which credentials exist
  forget   --server <uuid> --yes              remove a server and everything it published
  password [--password <text>]                print a hash for BROADCAST_ADMIN_PASSWORD_HASH

  --database <path|url>
                      the gateway's database: a file, or a Postgres URL. Taken from
                      BROADCAST_DATABASE_PATH or BROADCAST_DATABASE_URL when it is not given

A credential works for one capability and only while that capability is enabled (SEE-144).
"publish" is the public feed; "relay" lets an independently hosted direct server wake a phone
that has authorized it. Omitted, --for is publish, which is what every registration was before.
`

// capabilitiesOf reads --for as a registration's capabilities. "none" is allowed and means a
// registration that may do nothing, which is a real state: it is what an operator leaves a server
// in while they decide, and it is not the same as forgetting it.
func capabilitiesOf(value string) (bool, bool, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "publish":
		return true, false, nil
	case "relay":
		return false, true, nil
	case "both":
		return true, true, nil
	case "none":
		return false, false, nil
	default:
		return false, false, fmt.Errorf("--for must be publish, relay, both or none")
	}
}

// credentialFor reads --for as the one capability a credential is issued for. "both" is refused
// here on purpose: a credential does one thing, and a flag that seemed to make one do two would be
// exactly the confusion the capability split exists to prevent.
func credentialFor(value string) (storage.Capability, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "publish":
		return storage.Publishing, nil
	case "relay":
		return storage.Relaying, nil
	default:
		return "", fmt.Errorf("--for must be publish or relay: a credential is for one of them")
	}
}

func enabled(publishing, relaying bool) string {
	switch {
	case publishing && relaying:
		return "publish, relay"
	case publishing:
		return "publish"
	case relaying:
		return "relay"
	default:
		return "nothing"
	}
}

func run(arguments []string, out io.Writer) error {
	if len(arguments) == 0 {
		fmt.Fprint(out, usage)
		return fmt.Errorf("name a command")
	}
	command, arguments := arguments[0], arguments[1:]

	flags := flag.NewFlagSet("feed-gatewayctl "+command, flag.ContinueOnError)
	database := flags.String("database", configuredDatabase(),
		"the gateway's database: a file, or a Postgres URL")
	serverID := flags.String("server", "", "the publisher's server ID (a lowercase UUID)")
	label := flags.String("label", "", "a note for the operator; never served to anyone")
	credentialID := flags.String("credential", "", "a credential ID, as `list` prints it")
	host := flags.String("host", "",
		"the publisher's own base URL, as a note: never fetched, and never an authority")
	all := flags.Bool("all", false, "with revoke: every credential this publisher holds")
	yes := flags.Bool("yes", false, "with forget: confirm that documents will be deleted")
	password := flags.String("password", "",
		"with password: the administrator password to hash; omitted, it is read from stdin")
	capability := flags.String("for", "",
		"publish, relay, both or none: what a registration may do, or what a credential is for")
	if err := flags.Parse(arguments); err != nil {
		return err
	}

	// The one command that touches no database: it turns a password into the hash a deployment
	// configures, so an operator never has to put the password itself anywhere (SEE-141).
	if command == "password" {
		return hashPassword(*password, out)
	}

	if strings.TrimSpace(*database) == "" {
		return fmt.Errorf(
			"--database, or BROADCAST_DATABASE_PATH or BROADCAST_DATABASE_URL, " +
				"must name the gateway's database")
	}

	ctx := context.Background()
	documents, err := openStore(ctx, strings.TrimSpace(*database))
	if err != nil {
		return err
	}
	defer func() { _ = documents.Close() }()
	now := time.Now()

	switch command {
	case "capabilities":
		if !rules.IsID(*serverID) {
			return fmt.Errorf("--server must be a lowercase UUID")
		}
		publishing, relaying, err := capabilitiesOf(*capability)
		if err != nil {
			return err
		}
		if err := documents.SetCapabilities(ctx, *serverID, publishing, relaying); err != nil {
			return err
		}
		// Saying what this is not is the useful half: an operator who meant to end a credential
		// and switched a capability off instead would otherwise find it working again later.
		fmt.Fprintf(out, "%s may now: %s\n", *serverID, enabled(publishing, relaying))
		fmt.Fprint(out, "Credentials are not revoked by this. A capability switched off refuses "+
			"them from the next\ncall and switching it on again makes the same ones work; "+
			"`revoke` is what ends one for good.\n")
		return nil

	case "register", "rotate":
		if !rules.IsID(*serverID) {
			return fmt.Errorf("--server must be a lowercase UUID, which is what a server's " +
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
			publishing, relaying, capErr := capabilitiesOf(*capability)
			if capErr != nil {
				return capErr
			}
			if !publishing && !relaying {
				return fmt.Errorf("--for must name at least one capability when registering: " +
					"a server that may do nothing is a row nobody asked for")
			}
			// A server enabled for both gets its publishing credential here and its relay
			// credential from `rotate --for relay`, because one credential that did both is the
			// thing the capability split exists to prevent.
			first := storage.Publishing
			if !publishing {
				first = storage.Relaying
			}
			issued, err = documents.Register(ctx, storage.Registration{
				ServerID: *serverID, Label: note, Host: recorded,
				Publishing: publishing, Relaying: relaying,
			}, first, hash, now)
			if errors.Is(err, storage.ErrPublisherExists) {
				return fmt.Errorf("%s is already registered; use `rotate --server %s` to add a "+
					"credential to it", *serverID, *serverID)
			}
			if err == nil {
				fmt.Fprintf(out, "capabilities %s\n", enabled(publishing, relaying))
			}
		} else {
			if strings.TrimSpace(*host) != "" {
				return fmt.Errorf("--host belongs to `register`: rotation adds a credential and " +
					"changes nothing else about a server")
			}
			for_, capErr := credentialFor(*capability)
			if capErr != nil {
				return capErr
			}
			held, readErr := documents.Publisher(ctx, *serverID)
			if readErr != nil {
				return readErr
			}
			switch {
			case held == nil:
				return fmt.Errorf("%w: %s", storage.ErrNoPublisher, *serverID)
			case for_ == storage.Publishing && !held.Publishing,
				for_ == storage.Relaying && !held.Relaying:
				// A credential for a capability this server does not have would be refused on
				// every call, and issuing one anyway is how an operator comes to believe they
				// have set something up that they have not.
				return fmt.Errorf("%s is not enabled for %s; run "+
					"`capabilities --server %s --for ...` first", *serverID, for_, *serverID)
			}
			issued, err = documents.AddCredential(ctx, *serverID, note, for_, hash, now)
			if err == nil {
				fmt.Fprintf(out, "for         %s\n", for_)
			}
		}
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "server      %s\n", *serverID)
		fmt.Fprintf(out, "channel     %s\n", rules.ChannelFor(*serverID))
		fmt.Fprintf(out, "credential  %s\n", issued)
		fmt.Fprintf(out, "\n%s\n\n", secret)
		fmt.Fprint(out, "That credential is shown once and is not stored. Give it to the "+
			"developer,\nand keep it out of version control. "+
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
