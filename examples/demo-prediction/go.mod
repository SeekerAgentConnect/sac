// demo-prediction is one of two independent public-feed demonstrations. It builds, runs, tests
// and deploys on its own: it needs a reachable feed gateway and nothing else — not the CopyTrading
// demo, not the MCP server, and not the Direct Server SDK.
//
// The replace below is how a repository checkout resolves the shared source library. A copy of
// this demo taken out of the repository replaces it with an explicit module revision instead; the
// guide says how (examples/demo-prediction/README.md).
module github.com/BrRenat/SeekerAgentWallet/demo-prediction

go 1.27.1

require (
	connectrpc.com/connect v1.21.0
	github.com/BrRenat/SeekerAgentWallet/publisher-support v0.0.0
	github.com/skip2/go-qrcode v0.0.0-20200617195104-da1b6568686e
	golang.org/x/crypto v0.42.0
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/mattn/go-isatty v0.0.24 // indirect
	github.com/ncruces/go-strftime v1.0.0 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	golang.org/x/sys v0.47.0 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
	modernc.org/libc v1.75.7 // indirect
	modernc.org/mathutil v1.7.1 // indirect
	modernc.org/memory v1.12.1 // indirect
	modernc.org/sqlite v1.59.0 // indirect
)

replace github.com/BrRenat/SeekerAgentWallet/publisher-support => ../../packages/publisher-support
