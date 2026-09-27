// Command publishctl publishes and inspects this demo's signals through its own API
// (docs/integrations/signal-api.md).
//
// It is a client and nothing else: every command is one HTTP call that any other program could
// make, which is the point of it. A strategy engine, a cron job or a person with `curl` all reach
// this demo the same way, through the same validation, the same idempotency and the same one path
// to the gateway — so the tool is a worked example as much as it is a convenience, and there is no
// privileged back door into the store for it to use.
//
// The implementation is the shared library's, because both demos answer the same API and neither
// may import the other (packages/publisher-support/publisherctl). This module builds and ships the binary.
package main

import "github.com/BrRenat/SeekerAgentWallet/publisher-support/publisherctl"

func main() { publisherctl.Main() }
