package publishertest

import "strings"

// Environment is a deployment that starts: the settings with no default, plus the two credentials.
//
// It is shared because both halves of a Prediction deployment are read at once — the settings every
// publisher has, and the ones only a discovering demo has — so the demo's own configuration tests
// need exactly the environment the support library's tests use. A second copy of it would drift the
// first time a setting was added (publisher-support/config, demo-prediction/internal/config).
func Environment() map[string]string {
	return map[string]string{
		"PUBLISHER_SERVER_ID":     ServerID,
		"PUBLISHER_GATEWAY_URL":   "https://feeds.example.com",
		"PUBLISHER_ENVIRONMENT":   "production",
		"PUBLISHER_DATABASE_PATH": "/data/publisher.db",
		"PUBLISHER_API_TOKEN":     strings.Repeat("t", 43),
		"BROADCAST_CREDENTIAL":    strings.Repeat("c", 43),
	}
}

// LookupIn reads an environment from a map, so a test configures a demo without touching the
// process. It answers the shape `config.Lookup` has, and names no package, so a configuration
// package's own tests can use it.
func LookupIn(environment map[string]string) func(name string) (string, bool) {
	return func(name string) (string, bool) {
		value, set := environment[name]
		return value, set
	}
}
