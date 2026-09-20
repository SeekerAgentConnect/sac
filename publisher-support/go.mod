// publisher-support is the demos' shared source library, and nothing else: it has no command, no
// main package, no Dockerfile, no listener and no deployment of its own. It exists so that
// demo-copytrading and demo-prediction share one durable publication engine rather than two
// subtly different copies of it (SEE-134, docs/development/demos.md).
//
// It is deliberately not a published "feed publisher client" product. Its gateway package is an
// ordinary authenticated HTTP/Connect call to the gateway's documented publication API, which any
// HTTP client in any language can make; it is here because both demos happen to be written in Go.
module github.com/BrRenat/SeekerAgentWallet/publisher-support

go 1.27.1

require (
	connectrpc.com/connect v1.21.0
	google.golang.org/protobuf v1.36.12
	modernc.org/sqlite v1.59.0
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/mattn/go-isatty v0.0.24 // indirect
	github.com/ncruces/go-strftime v1.0.0 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	golang.org/x/sys v0.47.0 // indirect
	modernc.org/libc v1.75.7 // indirect
	modernc.org/mathutil v1.7.1 // indirect
	modernc.org/memory v1.12.1 // indirect
)
