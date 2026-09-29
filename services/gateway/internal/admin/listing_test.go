package admin_test

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/admin"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Registering with "Show in app recommendations" and a description stores both (SEE-176), and
// leaving the switch off — the form's default — stores an unlisted feed.
func TestRegisteringAListedFeedThroughTheUI(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()

	form := one.token(at + "/")
	page := one.get(at + "/")
	body := read(t, page)
	_ = page.Body.Close()
	if !strings.Contains(body, "Show in app recommendations") ||
		strings.Contains(body, `name="recommended" value="on" checked`) {
		t.Fatal("the registration drawer does not offer the recommendation switch, off")
	}

	response := one.post(at+"/servers", url.Values{
		"csrf":        {form},
		"server":      {publisher},
		"label":       {"signals"},
		"publishing":  {"on"},
		"access":      {"restricted"},
		"auth_origin": {"https://signals.example.com"},
		"recommended": {"on"},
		"description": {"  Swap ideas,\r\nevery morning.  "},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("registering answered %s", response.Status)
	}
	held, err := one.documents.Publisher(context.Background(), publisher)
	if err != nil || held == nil {
		t.Fatalf("the publisher was not registered: %v", err)
	}
	if held.Listing != (storage.Listing{Recommended: true, Description: "Swap ideas,\nevery morning."}) {
		t.Fatalf("the registration holds %+v", held.Listing)
	}
	// Listing it did not open it.
	if !held.Access.Restricted() {
		t.Fatalf("a listed restricted feed was registered as %+v", held.Access)
	}

	response = one.post(at+"/servers", url.Values{
		"csrf": {one.token(at + "/")}, "server": {other}, "label": {"plain"}, "publishing": {"on"},
	})
	_ = response.Body.Close()
	if held, _ := one.documents.Publisher(context.Background(), other); held == nil ||
		held.Listing != (storage.Listing{}) {
		t.Fatalf("a registration that said nothing is listed: %+v", held)
	}
}

func TestAnInvalidListingIsRefusedAtRegistration(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	for _, form := range []url.Values{
		{"description": {"bell\a"}},
		{"description": {strings.Repeat("x", storage.MaxDescriptionRunes+1)}},
		// A relay-only server has no feed to recommend.
		{"recommended": {"on"}, "publishing": nil, "relaying": {"on"}},
	} {
		form["csrf"] = []string{one.token(at + "/")}
		form["server"], form["label"] = []string{publisher}, []string{"x"}
		if _, set := form["publishing"]; !set {
			form["publishing"] = []string{"on"}
		}
		response := one.post(at+"/servers", form)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("%v answered %s", form, response.Status)
		}
	}
	if publishers, _ := one.documents.Publishers(context.Background()); len(publishers) != 0 {
		t.Fatalf("a refused registration wrote %d publisher(s)", len(publishers))
	}
}

// Editing a listing is one form on the publisher's page. It needs no ID typed back, because it
// moves nothing a reader holds, and the page says why a listed feed is not shown yet.
func TestEditingAListingExplainsWhatIsMissingAndMovesNothingElse(t *testing.T) {
	one := newFixture(t, openLimiter{})
	registerDirectly(t, one.documents, publisher, "signals")
	one.logIn()
	ctx := context.Background()
	page := at + "/servers/" + publisher
	before, err := one.documents.Access(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}

	response := one.post(page+"/listing", url.Values{"csrf": {one.token(page)},
		"recommended": {"on"}})
	body := read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK || !strings.Contains(body, "write a public description first") {
		t.Fatalf("listing without a description answered %s", response.Status)
	}

	response = one.post(page+"/listing", url.Values{"csrf": {one.token(page)},
		"recommended": {"on"}, "description": {"Daily ideas."}})
	body = read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK ||
		!strings.Contains(body, "has not published a manifest yet") ||
		!strings.Contains(body, "Daily ideas.") {
		t.Fatalf("listing without a manifest answered %s", response.Status)
	}
	held, err := one.documents.Publisher(ctx, publisher)
	if err != nil || held.Listing != (storage.Listing{Recommended: true, Description: "Daily ideas."}) {
		t.Fatalf("the store holds %+v: %v", held.Listing, err)
	}
	if after, _ := one.documents.Access(ctx, publisher); after != before {
		t.Fatalf("a listing moved the access policy: %+v → %+v", before, after)
	}

	// Unlisting keeps the description, and a bad one is refused without writing anything.
	response = one.post(page+"/listing", url.Values{"csrf": {one.token(page)},
		"description": {"Daily ideas."}})
	_ = response.Body.Close()
	response = one.post(page+"/listing", url.Values{"csrf": {one.token(page)},
		"recommended": {"on"}, "description": {"nul\x00"}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("a control character answered %s", response.Status)
	}
	if held, _ := one.documents.Publisher(ctx, publisher); held.Listing !=
		(storage.Listing{Description: "Daily ideas."}) {
		t.Fatalf("the store holds %+v", held.Listing)
	}

	// Without the session's token nothing is written.
	response = one.post(page+"/listing", url.Values{"recommended": {"on"},
		"description": {"Daily ideas."}})
	_ = response.Body.Close()
	if held, _ := one.documents.Publisher(ctx, publisher); held.Listing.Recommended {
		t.Fatal("a listing was changed without the session's token")
	}
}

func TestAListingIsShownOnlyOnceItsMetadataIsComplete(t *testing.T) {
	complete := storage.Publisher{Publishing: true,
		Listing: storage.Listing{Recommended: true, Description: "d"}}
	for _, one := range []struct {
		publisher   storage.Publisher
		name        string
		hasManifest bool
		shown       bool
		says        string
	}{
		{storage.Publisher{Publishing: true}, "n", true, false, "Not shown"},
		{storage.Publisher{Listing: complete.Listing}, "n", true, false, "not enabled for publishing"},
		{storage.Publisher{Publishing: true, Listing: storage.Listing{Recommended: true}}, "n", true,
			false, "description"},
		{complete, "", false, false, "manifest yet"},
		{complete, " ", true, false, "no display name"},
		{complete, "Signals", true, true, "“Signals”"},
	} {
		shown, says := admin.ListingState(one.publisher, one.name, one.hasManifest)
		if shown != one.shown || !strings.Contains(says, one.says) {
			t.Fatalf("%+v reads %t %q", one, shown, says)
		}
	}
}
