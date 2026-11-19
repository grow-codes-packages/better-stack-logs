// Command example shows the Better Stack Logs Go SDK: configuration from the
// environment, health and ping, sending logs through the batching LogClient (with
// the Logger facade and log/slog), reading them back with the SQL query API
// (SearchLogs, Query with parameters, WaitForLog) and error handling.
//
//	GROW_BETTER_STACK_LOGS_URL=https://s1234.eu-nbg-2.betterstackdata.com \
//	GROW_BETTER_STACK_LOGS_SOURCE_TOKEN=... \
//	GROW_BETTER_STACK_LOGS_QUERY_URL=https://eu-nbg-2-connect.betterstackdata.com \
//	GROW_BETTER_STACK_LOGS_QUERY_USERNAME=... GROW_BETTER_STACK_LOGS_QUERY_PASSWORD=... \
//	GROW_BETTER_STACK_LOGS_SOURCE_TABLE=t1234_myapp go run .
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"os"
	"time"

	betterstack "github.com/grow-codes-packages/better-stack-logs/go"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	client, err := betterstack.NewClientFromEnv()
	if err != nil {
		return err // a CONFIG error names the missing variable, never a value
	}
	defer func() { _ = client.Close() }()

	hs, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: %s (healthy=%t)\n", hs.Status, hs.Healthy)
	if d, err := client.Ping(ctx); err != nil {
		if errors.Is(err, betterstack.ErrAuthentication) {
			return fmt.Errorf("check the source token / query credentials: %w", err)
		}
		return err
	} else {
		fmt.Printf("ping: %s\n", d.Round(time.Millisecond))
	}

	// Send logs (batched, gzip, redaction of password/token/... attributes).
	logs, err := client.NewLogClient(betterstack.LogClientOptions{
		Resource: map[string]betterstack.AttrValue{"service.name": "example-go", "deployment.environment": "dev"},
	})
	if err != nil {
		return err
	}
	run := fmt.Sprintf("example-%d", time.Now().UnixNano())
	logs.Logger("checkout", nil).Info(ctx, "order placed "+run, "order_id", 42, "password", "never-sent")
	slog.New(betterstack.NewSlogHandler(logs, nil)).Warn("slow payment "+run, "ms", 950)
	if err := logs.Flush(ctx); err != nil {
		return err
	}
	fmt.Printf("sent: %+v\n", logs.Stats())

	q := client.Query()
	if q == nil {
		fmt.Println("no query credentials: skipping the read-back")
		return nil
	}
	rec, err := q.WaitForLog(ctx, run, betterstack.WaitForLogOptions{Timeout: 60 * time.Second, Interval: 2 * time.Second})
	if err != nil {
		return err
	}
	fmt.Printf("found: %s [%s]\n", rec.Body, rec.SeverityText)

	page, err := q.SearchLogs(ctx, betterstack.SearchLogsOptions{Contains: run, Start: time.Now().Add(-time.Hour), Limit: 10})
	if err != nil {
		return err
	}
	for _, r := range page.Records {
		fmt.Printf("row: %s %s %s\n", r.Timestamp.Format(time.RFC3339Nano), r.SeverityText, r.Body)
	}

	table := client.Config().SourceTable
	if table == "" {
		table = os.Getenv("BETTERSTACK_SOURCE_TABLE")
	}
	counts, err := q.Query(ctx,
		"SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0",
		map[string]any{"table": table + "_logs", "needle": run})
	if err != nil {
		return err
	}
	fmt.Printf("count: %v\n", counts[0]["c"])
	return nil
}
