package admin

import (
	"fmt"
	"html"
	"html/template"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

// The panel's pairing QR codes (SEE-163). A phone pairs with this feed the way it pairs with
// anything: SAC → Add connection → Scan QR code, pointed at what the input beside the code
// already shows. Nothing new is encoded and nothing new is granted — the reference carries no
// secret, and a restricted feed's invitation is single use and bound to the one device it was
// issued for — so the code is a convenience for the same flow, not a second one.
//
// It is an inline SVG because the pages' CSP loads no images and stays that way: an <svg> in the
// document is neither a fetch nor a script, so a page with its script switched off still shows it.

// ReferenceQR is the feed reference as a scannable code.
func (v homeView) ReferenceQR() template.HTML {
	return pairingQR(v.Reference, "QR code of the feed reference", 176)
}

// InvitationQR is the live invitation of an approved device as a scannable code: that device —
// and no other — scans it in SAC and redeems it there. Showing it changes nothing about what
// the invitation already is.
func (d Device) InvitationQR() template.HTML {
	return pairingQR(d.Link(), "QR code of the device invitation", 132)
}

// pairingQR draws text as one inline SVG QR code, or nothing when there is nothing to show: the
// templates render around the value, and a page that cannot draw one still carries the text.
func pairingQR(text, label string, pixels int) template.HTML {
	if text == "" {
		return ""
	}
	code, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		// A reference or invitation is far below the encoder's limits; if one ever is not,
		// the text beside the code still pairs by pasting.
		return ""
	}
	bitmap := code.Bitmap() // the encoder's own matrix, quiet zone included
	var dark strings.Builder
	for y, row := range bitmap {
		for x := 0; x < len(row); {
			if !row[x] {
				x++
				continue
			}
			run := 1
			for x+run < len(row) && row[x+run] {
				run++
			}
			// One rectangle per run of dark modules, so the whole code is a single path.
			fmt.Fprintf(&dark, "M%d %dh%dv1h-%dz", x, y, run, run)
			x += run
		}
	}
	return template.HTML(fmt.Sprintf(
		`<svg width="%d" height="%d" viewBox="0 0 %d %d" role="img" aria-label="%s" shape-rendering="crispEdges"><path fill="#000" d="%s"/></svg>`,
		pixels, pixels, len(bitmap), len(bitmap), html.EscapeString(label), dark.String()))
}
