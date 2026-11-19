package integration

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	betterstack "github.com/grow-codes-packages/better-stack-logs/go"
)

// setup loads /bootstrap/env and skips outside docker/compose.yml.
func setup(t *testing.T) {
	t.Helper()
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_BETTER_STACK_LOGS_URL") == "" {
		t.Skip("GROW_BETTER_STACK_LOGS_URL is not set; run through docker/compose.yml")
	}
}

func runID(t *testing.T) string {
	t.Helper()
	b := make([]byte, 5)
	_, _ = rand.Read(b)
	return "go-" + hex.EncodeToString(b)
}

// newClient builds a client from the environment with explicit overrides.
func newClient(t *testing.T, explicit betterstack.Config) *betterstack.Client {
	t.Helper()
	cfg, err := betterstack.ConfigFromEnvWith(explicit, nil)
	if err != nil {
		t.Fatal(err)
	}
	c, err := betterstack.NewClient(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// waitRows polls SearchLogs until at least n rows contain needle.
func waitRows(t *testing.T, q *betterstack.Query, source, needle string, n int) []betterstack.LogRecord {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for {
		res, err := q.SearchLogs(t.Context(), betterstack.SearchLogsOptions{Source: source, Contains: needle, Order: betterstack.OrderAsc, Limit: 1000})
		if err != nil {
			t.Fatal(err)
		}
		if len(res.Records) >= n {
			return res.Records
		}
		if time.Now().After(deadline) {
			t.Fatalf("found %d of %d rows for %q", len(res.Records), n, needle)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// injectFault arms a stub fault (stub-only control endpoint, not part of the real API).
func injectFault(t *testing.T, target string, status int, body string) {
	t.Helper()
	ctl := os.Getenv("BETTERSTACK_STUB_INGEST_CONTROL_URL")
	if target == "query" {
		ctl = os.Getenv("BETTERSTACK_STUB_QUERY_CONTROL_URL")
	}
	if ctl == "" {
		t.Skip("no stub control URL")
	}
	payload, _ := json.Marshal(map[string]any{"status": status, "count": 1, "target": target, "body": body})
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPost, ctl+"/fault", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode >= 300 {
		t.Fatalf("fault control: %d", res.StatusCode)
	}
}

func TestCore(t *testing.T) {
	setup(t)
	c := newClient(t, betterstack.Config{})
	cfg := c.Config()
	if cfg.URL != os.Getenv("GROW_BETTER_STACK_LOGS_URL") || !cfg.HasIngest() || !cfg.HasQuery() || cfg.SourceTable == "" {
		t.Fatalf("config %s", cfg)
	}
	if strings.Contains(cfg.String(), cfg.SourceToken.Reveal()) || strings.Contains(cfg.String(), cfg.QueryPassword.Reveal()) {
		t.Fatal("config string leaks a credential")
	}
	hs, err := c.Health(t.Context())
	if err != nil || !hs.Healthy || hs.Status != "ok" {
		t.Fatalf("health %+v %v", hs, err)
	}
	if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
		t.Fatalf("ping %s %v", d, err)
	}
	if _, err := c.ServerVersion(t.Context()); !errors.Is(err, betterstack.ErrUnsupported) {
		t.Fatalf("serverVersion %v", err)
	}

	// Ingest-only client: health = reachability, ping = empty batch (nothing stored).
	ingestOnly, err := betterstack.NewClient(betterstack.Config{CommonConfig: betterstack.CommonConfig{URL: cfg.URL}, SourceToken: cfg.SourceToken})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = ingestOnly.Close() }()
	if hs, err := ingestOnly.Health(t.Context()); err != nil || !hs.Healthy {
		t.Fatalf("ingest health %+v %v", hs, err)
	}
	if _, err := ingestOnly.Ping(t.Context()); err != nil {
		t.Fatalf("ingest ping %v", err)
	}
	// Bare host:port (production form of BETTERSTACK_INGESTING_HOST) is https://host:port.
	if host := os.Getenv("BETTERSTACK_INGESTING_HOST"); host != "" && betterstack.NormalizeBaseURL(host) != "https://"+host {
		t.Fatal("normalize host")
	}

	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal("close must be idempotent")
	}
	if _, err := c.Ping(t.Context()); !errors.Is(err, betterstack.ErrCancelled) {
		t.Fatalf("ping after close %v", err)
	}
	if _, err := c.NewLogClient(betterstack.LogClientOptions{}); !errors.Is(err, betterstack.ErrCancelled) {
		t.Fatalf("log client after close %v", err)
	}
}

func TestAuthErrors(t *testing.T) {
	setup(t)
	bad := newClient(t, betterstack.Config{SourceToken: betterstack.NewSecret("wrong-source-token"), QueryPassword: betterstack.NewSecret("wrong-query-password")})
	_, err := bad.Ping(t.Context())
	if !errors.Is(err, betterstack.ErrAuthentication) || strings.Contains(err.Error(), "wrong-query-password") {
		t.Fatalf("query auth %v", err)
	}
	_, err = bad.Exporter().Export(t.Context(), []betterstack.LogRecord{{Body: "x"}}, betterstack.ExportOptions{})
	if !errors.Is(err, betterstack.ErrAuthentication) || strings.Contains(err.Error(), "wrong-source-token") {
		t.Fatalf("ingest auth %v", err)
	}
}

func TestIngestEncodingsRoundTrip(t *testing.T) {
	setup(t)
	run := runID(t)
	for _, enc := range []betterstack.Encoding{betterstack.EncodingJSON, betterstack.EncodingNDJSON, betterstack.EncodingMsgPack} {
		for _, gz := range []bool{false, true} {
			name := string(enc)
			if gz {
				name += "-gzip"
			}
			t.Run(name, func(t *testing.T) {
				c := newClient(t, betterstack.Config{Encoding: enc})
				lc, err := c.NewLogClient(betterstack.LogClientOptions{
					Batch:    betterstack.BatchOptions{DisableGzip: !gz, MaxBatchSize: 2},
					Resource: map[string]betterstack.AttrValue{"service.name": "grow-it-go"},
				})
				if err != nil {
					t.Fatal(err)
				}
				needle := run + "-" + name
				ts := time.Date(2026, 1, 2, 3, 4, 5, 678901000, time.UTC)
				lc.Send(t.Context(), betterstack.LogInput{Message: needle + " first", Severity: betterstack.SeverityWarn, Timestamp: ts,
					Attributes: map[string]betterstack.AttrValue{"n": 1, "unicode": "Grüße ✓", "token": "secret-value"},
					TraceID:    "4bf92f3577b34da6a3ce929d0e0e4736", SpanID: "00f067aa0ba902b7"})
				lc.SendBatch(t.Context(), []betterstack.LogEntry{
					betterstack.LogInput{Message: needle + " second", Timestamp: ts.Add(time.Second)},
					betterstack.LogInput{Message: needle + " third", Timestamp: ts.Add(2 * time.Second)},
				})
				if err := lc.Flush(t.Context()); err != nil {
					t.Fatal(err)
				}
				if st := lc.Stats(); st.Sent != 3 || st.Failed != 0 {
					t.Fatalf("stats %+v", st)
				}
				res, err := c.Query().SearchLogs(t.Context(), betterstack.SearchLogsOptions{
					Contains: needle, Start: ts.Add(-time.Minute), End: ts.Add(time.Minute), Order: betterstack.OrderAsc,
				})
				if err != nil || len(res.Records) != 3 {
					t.Fatalf("rows %d %v", len(res.Records), err)
				}
				rec := res.Records[0]
				if rec.Body != needle+" first" || rec.Severity != betterstack.SeverityWarn || rec.TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" ||
					rec.SpanID != "00f067aa0ba902b7" || rec.Attributes["unicode"] != "Grüße ✓" || rec.Attributes["token"] == "secret-value" ||
					rec.Resource["service.name"] != "grow-it-go" || !rec.Timestamp.Equal(ts) {
					t.Fatalf("record %+v", rec)
				}
			})
		}
	}
}

func TestExporterAndOTLP(t *testing.T) {
	setup(t)
	run := runID(t)
	c := newClient(t, betterstack.Config{})
	res, err := c.Exporter().Export(t.Context(), []betterstack.LogRecord{{Body: run + " direct", Severity: betterstack.SeverityInfo, SeverityText: "INFO", Timestamp: time.Now()}}, betterstack.ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 1 {
		t.Fatalf("export %+v %v", res, err)
	}
	waitRows(t, c.Query(), "", run+" direct", 1)

	// Raw events and a custom exporter (MessagePack, own limits).
	if err := c.Exporter().SendEvents(t.Context(), []map[string]any{{"message": run + " raw event", "dt": time.Now(), "custom": map[string]any{"k": "v"}}}, betterstack.SendEventsOptions{}); err != nil {
		t.Fatal(err)
	}
	raw := waitRows(t, c.Query(), "", run+" raw event", 1)
	if raw[0].Attributes["custom"].(map[string]any)["k"] != "v" {
		t.Fatalf("raw event %+v", raw[0])
	}
	mp, err := c.NewExporter(betterstack.IngestExporterOptions{Encoding: betterstack.EncodingMsgPack, MaxRequestBytes: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	mlc, err := c.NewLogClientWith(mp, betterstack.LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	mlc.Send(t.Context(), betterstack.LogInput{Message: run + " custom exporter"})
	if err := mlc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	waitRows(t, c.Query(), "", run+" custom exporter", 1)

	otlp, err := c.NewLogClientWith(c.OTLPExporter(), betterstack.LogClientOptions{Resource: map[string]betterstack.AttrValue{"service.name": "grow-it-go-otlp"}})
	if err != nil {
		t.Fatal(err)
	}
	otlp.Logger("otlp", nil).Error(t.Context(), run+" via otlp", "k", "v")
	if err := otlp.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	if st := otlp.Stats(); st.Sent != 1 {
		t.Fatalf("otlp stats %+v", st)
	}
	rows := waitRows(t, c.Query(), "", run+" via otlp", 1)
	if rec := rows[0]; rec.Body != run+" via otlp" || rec.Severity != betterstack.SeverityError {
		t.Fatalf("otlp record %+v", rec)
	}
}

func TestQueryAndSearch(t *testing.T) {
	setup(t)
	run := runID(t)
	c := newClient(t, betterstack.Config{})
	q := c.Query()
	lc, err := c.NewLogClient(betterstack.LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	base := time.Now().UTC().Add(-time.Minute).Truncate(time.Millisecond)
	for i := 0; i < 5; i++ {
		lc.Send(t.Context(), betterstack.LogInput{Message: run + " it's \\ #" + string(rune('a'+i)), Timestamp: base.Add(time.Duration(i) * time.Second)})
	}
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}

	// query(sql, params): placeholders are bound server side (param_<name>, escaped TSV text).
	rows, err := q.Query(t.Context(),
		"SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
		map[string]any{"table": c.Config().SourceTable + "_logs", "needle": run + " it's \\", "since": base})
	if err != nil || len(rows) != 1 || rows[0]["c"] != "5" {
		t.Fatalf("query %v %v", rows, err)
	}
	value := `C:\tmp\it's "` + run + `"`
	rows, err = q.Query(t.Context(), "SELECT {v:String} AS v", map[string]any{"v": value})
	if err != nil || len(rows) != 1 || rows[0]["v"] != value {
		t.Fatalf("query backslash+quote %v %v", rows, err)
	}
	rows, err = q.Query(t.Context(), "SELECT JSONExtractString(raw, 'message') AS m FROM remote("+c.Config().SourceTable+"_logs) WHERE position(raw, '"+run+"') > 0 ORDER BY dt ASC LIMIT 1 FORMAT JSON", nil)
	if err != nil || len(rows) != 1 || rows[0]["m"] != run+" it's \\ #a" {
		t.Fatalf("query JSON %v %v", rows, err)
	}

	// searchLogs: window, order, limit, offset.
	page, err := q.SearchLogs(t.Context(), betterstack.SearchLogsOptions{Contains: run + " it's \\", Start: base, End: base.Add(10 * time.Second), Limit: 2, Order: betterstack.OrderAsc})
	if err != nil || len(page.Records) != 2 || page.NextCursor == "" || !strings.HasSuffix(page.Records[1].Body, "#b") {
		t.Fatalf("searchLogs %+v %v", page, err)
	}
	page, err = q.SearchLogs(t.Context(), betterstack.SearchLogsOptions{Contains: run, Start: base, End: base.Add(10 * time.Second), Limit: 2, Order: betterstack.OrderAsc, Cursor: page.NextCursor})
	if err != nil || len(page.Records) != 2 || !strings.HasSuffix(page.Records[0].Body, "#c") {
		t.Fatalf("searchLogs page 2 %+v %v", page, err)
	}
	desc, err := q.SearchLogs(t.Context(), betterstack.SearchLogsOptions{Contains: run, Start: base.Add(-time.Second)})
	if err != nil || len(desc.Records) != 5 || desc.NextCursor != "" || !strings.HasSuffix(desc.Records[0].Body, "#e") {
		t.Fatalf("searchLogs desc %+v %v", desc, err)
	}
	if sql, err := q.RenderSearchSQL(betterstack.SearchLogsOptions{Contains: run}); err != nil || !strings.Contains(sql, "s3Cluster(primary, ") {
		t.Fatalf("render %s %v", sql, err)
	}
	raw, err := q.QueryRaw(t.Context(), "SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0 FORMAT JSON",
		map[string]any{"table": c.Config().SourceTable + "_logs", "needle": run}, betterstack.QueryOptions{Settings: map[string]any{"output_format_pretty_row_numbers": 0}})
	var doc struct {
		Data []map[string]any `json:"data"`
	}
	if err != nil || json.Unmarshal([]byte(raw), &doc) != nil || len(doc.Data) != 1 || doc.Data[0]["c"] != "5" {
		t.Fatalf("queryRaw %q %v", raw, err)
	}

	// LogQueryClient: search + cursor, SearchAll, waitForLog.
	res, err := q.Search(t.Context(), run, betterstack.SearchOptions{Limit: 2})
	if err != nil || len(res.Records) != 2 || res.NextCursor == "" {
		t.Fatalf("search %+v %v", res, err)
	}
	all, err := betterstack.SearchAll(t.Context(), q, run, betterstack.SearchOptions{Limit: 2}, 0)
	if err != nil || len(all) != 5 {
		t.Fatalf("searchAll %d %v", len(all), err)
	}
	rec, err := q.WaitForLog(t.Context(), run, betterstack.WaitForLogOptions{
		Timeout:   30 * time.Second,
		Predicate: func(r betterstack.LogRecord) bool { return strings.HasSuffix(r.Body, "#c") },
	})
	if err != nil || !strings.HasSuffix(rec.Body, "#c") {
		t.Fatalf("waitForLog %+v %v", rec, err)
	}
	if _, err := q.WaitForLog(t.Context(), run+" never", betterstack.WaitForLogOptions{Timeout: 1500 * time.Millisecond}); !errors.Is(err, betterstack.ErrTimeout) {
		t.Fatalf("waitForLog timeout %v", err)
	}

	streams, err := q.ListStreams(t.Context())
	if err != nil || len(streams) != 1 || streams[0] != c.Config().SourceTable {
		t.Fatalf("streams %v %v", streams, err)
	}
	if _, err := q.ListLabels(t.Context()); !errors.Is(err, betterstack.ErrUnsupported) {
		t.Fatalf("labels %v", err)
	}

	// Error mapping by ClickHouse code.
	if _, err := q.Query(t.Context(), "SELECT dt FROM remote(t1_does_not_exist_logs)", nil); !errors.Is(err, betterstack.ErrNotFound) {
		t.Fatalf("unknown table %v", err)
	}
	if _, err := q.Query(t.Context(), "SELEC nonsense", nil); !errors.Is(err, betterstack.ErrValidation) {
		t.Fatalf("syntax %v", err)
	}
}

func TestSourceIsolation(t *testing.T) {
	setup(t)
	tokenB, tableB := os.Getenv("BETTERSTACK_SOURCE_TOKEN_B"), os.Getenv("BETTERSTACK_SOURCE_TABLE_B")
	if tokenB == "" || tableB == "" {
		t.Skip("no second source")
	}
	run := runID(t)
	b := newClient(t, betterstack.Config{SourceToken: betterstack.NewSecret(tokenB), SourceTable: tableB})
	lc, err := b.NewLogClient(betterstack.LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	lc.Send(t.Context(), betterstack.LogInput{Message: run + " source b"})
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	waitRows(t, b.Query(), tableB, run, 1)
	a := newClient(t, betterstack.Config{})
	if res, err := a.Query().SearchLogs(t.Context(), betterstack.SearchLogsOptions{Contains: run}); err != nil || len(res.Records) != 0 {
		t.Fatalf("source a sees source b: %v %v", res, err)
	}
}

func TestTail(t *testing.T) {
	setup(t)
	run := runID(t)
	c := newClient(t, betterstack.Config{})
	var mu sync.Mutex
	var got []string
	h, err := c.Query().Tail(t.Context(), run, func(r betterstack.LogRecord) {
		mu.Lock()
		got = append(got, r.Body)
		mu.Unlock()
	}, betterstack.TailOptions{Start: time.Now().Add(-time.Second), Interval: 300 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Stop()
	lc, err := c.NewLogClient(betterstack.LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	for _, m := range []string{"one", "two"} {
		lc.Send(t.Context(), betterstack.LogInput{Message: run + " tail " + m})
	}
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		mu.Lock()
		n := len(got)
		mu.Unlock()
		if n >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("tail got %v", got)
		}
		time.Sleep(100 * time.Millisecond)
	}
	time.Sleep(700 * time.Millisecond) // two more polls must not repeat records
	h.Stop()
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 2 {
		t.Fatalf("tail delivered duplicates: %v", got)
	}
}

func TestSlogAndLogger(t *testing.T) {
	setup(t)
	run := runID(t)
	c := newClient(t, betterstack.Config{})
	lc, err := c.NewLogClient(betterstack.LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(betterstack.NewSlogHandler(lc, nil)).With("run", run)
	logger.Warn(run+" from slog", "user", "ada", "password", "hunter2")
	lc.Logger("facade", nil).Child(map[string]betterstack.AttrValue{"component": "child"}).Error(t.Context(), run+" from facade")
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	rows := waitRows(t, c.Query(), "", run, 2)
	for _, r := range rows {
		if fmt.Sprint(r.Attributes) == "" || strings.Contains(fmt.Sprint(r.Attributes), "hunter2") {
			t.Fatal("password not redacted")
		}
	}
}

func TestIngestFaults(t *testing.T) {
	setup(t)
	c := newClient(t, betterstack.Config{CommonConfig: betterstack.CommonConfig{Retry: &betterstack.RetryPolicy{MaxRetries: 0}}})
	injectFault(t, "ingest", 402, "Quota exceeded")
	_, err := c.Exporter().Export(t.Context(), []betterstack.LogRecord{{Body: "quota"}}, betterstack.ExportOptions{})
	var e *betterstack.Error
	if !errors.As(err, &e) || e.Code != betterstack.ErrorCodeRateLimited || e.Retryable || e.HTTPStatus != 402 {
		t.Fatalf("402 %+v", err)
	}
	injectFault(t, "query", 503, "Service Unavailable")
	if _, err := c.Query().Query(t.Context(), "SELECT 1", nil); !errors.Is(err, betterstack.ErrUnavailable) {
		t.Fatalf("503 %v", err)
	}
}
