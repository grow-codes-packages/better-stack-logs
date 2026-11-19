package integration

import (
	"context"
	"strings"
	"testing"

	betterstack "github.com/grow-codes-packages/better-stack-logs/go"
	"github.com/grow-codes-packages/better-stack-logs/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
//
//   - "query": records are read back through the SQL query host (ClickHouse HTTP).
//   - no "version": Better Stack is a SaaS and exposes no server version
//     (ServerVersion returns UNSUPPORTED, asserted in betterstack_test.go).
var capabilities = []string{"query"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("logging", ",")...)
}

// factories builds the conformance targets: core → *betterstack.Client; logging → a
// batching LogClient over the ingesting host plus the SQL Query client.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return betterstack.NewClientFromEnv()
		}),
		"logging": func(ctx context.Context, env *testkit.Env) (any, error) {
			c, err := betterstack.NewClientFromEnv()
			if err != nil {
				return nil, err
			}
			env.AddCleanup(func() { _ = c.Close() })
			return testkit.LoggingFactory(func(_ context.Context, opts betterstack.BatchOptions) (testkit.LoggingTarget, error) {
				lc, err := c.NewLogClient(betterstack.LogClientOptions{
					Batch:    opts,
					Resource: map[string]betterstack.AttrValue{"service.name": "grow-conformance-go", "deployment.environment": "conformance"},
				})
				if err != nil {
					return testkit.LoggingTarget{}, err
				}
				return testkit.LoggingTarget{Client: lc, Query: c.Query()}, nil
			})(ctx, env)
		},
	}
}
