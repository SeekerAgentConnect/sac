package admin

import (
	"fmt"
	"regexp"
	"strings"
	"testing"

	qrcode "github.com/skip2/go-qrcode"
)

// SEE-163: the panel pairs a device by letting the phone scan the reference. What is drawn has
// to be the encoder's own matrix, quiet zone and all — a drawing that drops or shifts one module
// does not scan as the reference, and one that paints into the quiet zone does not scan at all.

const qrReference = "seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

func TestTheReferenceQRIsADrawingOfTheEncodersOwnMatrix(t *testing.T) {
	svg := string(pairingQR(qrReference, "QR code of the feed reference", 176))
	for _, expected := range []string{
		fmt.Sprintf(`viewBox="0 0 %d %d"`, qrSize(t, qrReference), qrSize(t, qrReference)),
		`role="img" aria-label="QR code of the feed reference"`,
		`<path fill="#000" d="`,
	} {
		if !strings.Contains(svg, expected) {
			t.Fatalf("the reference is not drawn as one inline SVG:\n%s", svg)
		}
	}
	if !matchesTheEncoder(t, svg, qrReference) {
		t.Fatalf("the drawing is not the encoder's own matrix:\n%s", svg)
	}
}

func TestTheReferenceQRIsEmptyWithoutAReference(t *testing.T) {
	if drawn := pairingQR("", "QR code of the feed reference", 176); drawn != "" {
		t.Fatalf("an empty reference drew %q", drawn)
	}
	if drawn := (homeView{Reference: ""}).ReferenceQR(); drawn != "" {
		t.Fatalf("an empty home drew %q", drawn)
	}
}

func qrSize(t *testing.T, text string) int {
	t.Helper()
	code, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		t.Fatal(err)
	}
	return len(code.Bitmap())
}

// qrSVG pulls the reference's drawn code out of a rendered page, so a page test can ask what it
// says rather than only that it is there.
func qrSVG(t *testing.T, body string) string {
	t.Helper()
	found := regexp.MustCompile(`<svg[^>]*aria-label="QR code of the feed reference".*?</svg>`).FindString(body)
	if found == "" {
		t.Fatal("the page draws no reference QR code")
	}
	return found
}

// matchesTheEncoder reads the drawn rectangles back into a matrix and compares it with the
// encoder's own, proving the path says what the encoder said rather than merely looking like it
// might.
func matchesTheEncoder(t *testing.T, svg, text string) bool {
	t.Helper()
	code, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		t.Fatal(err)
	}
	wanted := code.Bitmap()
	size := len(wanted)
	drawn := make([][]bool, size)
	for y := range drawn {
		drawn[y] = make([]bool, size)
	}
	rectangle := regexp.MustCompile(`M(\d+) (\d+)h(\d+)v1h-\d+z`)
	for _, found := range rectangle.FindAllStringSubmatch(svg, -1) {
		var x, y, run int
		if _, err := fmt.Sscanf(found[1]+" "+found[2]+" "+found[3], "%d %d %d", &x, &y, &run); err != nil {
			t.Fatalf("a rectangle the test cannot read, %q: %v", found[0], err)
		}
		for i := 0; i < run; i++ {
			drawn[y][x+i] = true
		}
	}
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			if drawn[y][x] != wanted[y][x] {
				return false
			}
		}
	}
	return true
}
