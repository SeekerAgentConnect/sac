package postgres

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// ClientConfig reads the operator's client configuration (SEE-185), or the empty one at revision 0
// when none was ever saved. It writes nothing: asking is the same for everyone, and is not recorded.
func (s *Store) ClientConfig(ctx context.Context) (*storage.StoredClientConfig, error) {
	return readClientConfig(ctx, s.reader)
}

func readClientConfig(ctx context.Context, from querier) (*storage.StoredClientConfig, error) {
	var (
		revision int64
		document []byte
		updated  int64
	)
	err := from.QueryRowContext(ctx,
		`SELECT revision, document, updated_at_ms FROM `+Schema+`.client_config WHERE singleton`).
		Scan(&revision, &document, &updated)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return &storage.StoredClientConfig{Document: &gatewayv1.ClientConfig{}}, nil
	case err != nil:
		return nil, fmt.Errorf("read client configuration: %w", err)
	}
	config := &gatewayv1.ClientConfig{}
	if err := proto.Unmarshal(document, config); err != nil {
		return nil, fmt.Errorf("read client configuration: %w", err)
	}
	config.Revision = uint64(revision)
	return &storage.StoredClientConfig{Document: config, UpdatedAt: instant(updated)}, nil
}

// SaveClientConfig replaces the client configuration, or changes nothing when it is the one held
// (storage.PublisherAdminStore). The read and the write are one transaction under the write lock
// every writer takes, so two operators — or two gateways and feed-gatewayctl — saving at once
// cannot both move the revision from the same number.
func (s *Store) SaveClientConfig(
	ctx context.Context,
	given *gatewayv1.ClientConfig,
	at time.Time,
) (*storage.StoredClientConfig, bool, error) {
	content, problem := rules.ClientConfig(given)
	if problem != nil {
		return nil, false, fmt.Errorf("%w: %v", storage.ErrInvalidClientConfig, problem)
	}
	var (
		saved   *storage.StoredClientConfig
		changed bool
	)
	err := s.write(ctx, func(tx *Tx) error {
		held, err := readClientConfig(ctx, tx.tx)
		if err != nil {
			return err
		}
		revision := held.Document.GetRevision()
		current := proto.CloneOf(held.Document)
		current.Revision = 0
		if proto.Equal(current, content) {
			saved = held
			return nil
		}
		document, err := proto.Marshal(content)
		if err != nil {
			return fmt.Errorf("write client configuration: %w", err)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO `+Schema+`.client_config (singleton, revision, document, updated_at_ms)
			 VALUES (TRUE, $1, $2, $3)
			 ON CONFLICT (singleton) DO UPDATE SET
			   revision = EXCLUDED.revision,
			   document = EXCLUDED.document,
			   updated_at_ms = EXCLUDED.updated_at_ms`,
			int64(revision+1), document, milliseconds(at)); err != nil {
			return fmt.Errorf("write client configuration: %w", err)
		}
		stored := proto.CloneOf(content)
		stored.Revision = revision + 1
		saved = &storage.StoredClientConfig{Document: stored, UpdatedAt: instant(milliseconds(at))}
		changed = true
		return nil
	})
	if err != nil {
		return nil, false, err
	}
	return saved, changed, nil
}
