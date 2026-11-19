package betterstack

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

// newTestClient builds an ingest client for url (source token testToken) with fast
// retries; mutate adjusts the config.
func newTestClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: url, Retry: fastRetry(3)}, SourceToken: NewSecret(testToken)}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func TestClientConfig(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			EnvPrefix + "URL":            "http://grow:1",
			"BETTERSTACK_INGESTING_HOST": "s1.eu-nbg-2.betterstackdata.com",
			"BETTERSTACK_SOURCE_TOKEN":   "vendor-token",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil || cfg.URL != "http://grow:1" || cfg.SourceToken.Reveal() != "vendor-token" {
			t.Fatalf("env: %v %s", err, cfg)
		}
		env[EnvPrefix+"SOURCE_TOKEN"] = "grow-token"
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.SourceToken.Reveal() != "grow-token" {
			t.Fatal("GROW_* token must beat the vendor variable")
		}
		cfg, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "https://explicit"}, SourceToken: NewSecret("explicit")}, env)
		if cfg.URL != "https://explicit" || cfg.SourceToken.Reveal() != "explicit" {
			t.Fatal("explicit must win")
		}
		delete(env, EnvPrefix+"URL")
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.URL != "https://s1.eu-nbg-2.betterstackdata.com" || cfg.RequestTimeout != 0 && cfg.RequestTimeout != DefaultRequestTimeout {
			t.Fatalf("vendor host: %s", cfg)
		}
		env["BETTERSTACK_INGESTING_URL"] = "http://better-stack-logs:8080/"
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.URL != "http://better-stack-logs:8080" {
			t.Fatalf("vendor url order: %s", cfg.URL)
		}
		_, err = ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "TOTAL_TIMEOUT": "forever-xyz"})
		if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), EnvPrefix+"TOTAL_TIMEOUT") || strings.Contains(err.Error(), "forever-xyz") {
			t.Fatalf("invalid value: %v", err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "URL": "ftp://nope", EnvPrefix + "SOURCE_TOKEN": "x"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("invalid url: %v", err)
		}
		t.Setenv(EnvPrefix+"URL", "http://process-env:9")
		t.Setenv(EnvPrefix+"SOURCE_TOKEN", "tok")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg.URL)
		}
	})

	t.Run("query env", func(t *testing.T) {
		env := map[string]string{
			"BETTERSTACK_QUERY_HOST":                "eu-nbg-2-connect.betterstackdata.com",
			"BETTERSTACK_QUERY_USERNAME":            "vendor-user",
			"BETTERSTACK_QUERY_PASSWORD":            "vendor-pass",
			"BETTERSTACK_SOURCE_TABLE":              "t1_vendor",
			EnvPrefix + "QUERY_USERNAME":            "grow-user",
			EnvPrefix + "ENCODING":                  "MsgPack",
			"BETTERSTACK_SOURCE_TOKEN_IS_NOT_READ":  "x",
			EnvPrefix + "SOURCE_TABLE":              "t1_grow",
			"GROW_BETTER_STACK_LOGS_QUERY_PASSWORD": "grow-pass",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil {
			t.Fatal(err)
		}
		if cfg.QueryURL != "https://eu-nbg-2-connect.betterstackdata.com" || cfg.QueryUsername != "grow-user" ||
			cfg.QueryPassword.Reveal() != "grow-pass" || cfg.SourceTable != "t1_grow" || cfg.Encoding != EncodingMsgPack {
			t.Fatalf("query env: %s", cfg)
		}
		if cfg.HasIngest() || !cfg.HasQuery() {
			t.Fatal("sets")
		}
	})

	t.Run("validate", func(t *testing.T) {
		ok := Config{CommonConfig: CommonConfig{URL: "https://in"}, SourceToken: NewSecret("t")}
		if err := ok.Validate(); err != nil {
			t.Fatal(err)
		}
		bad := map[string]Config{
			"empty":         {},
			"url only":      {CommonConfig: CommonConfig{URL: "https://in"}},
			"token only":    {SourceToken: NewSecret("t")},
			"query partial": {QueryURL: "https://q", QueryUsername: "u"},
			"bad query url": {QueryURL: "ftp://q", QueryUsername: "u", QueryPassword: NewSecret("p")},
			"bad table":     {CommonConfig: CommonConfig{URL: "https://in"}, SourceToken: NewSecret("t"), SourceTable: "t1; DROP"},
			"bad encoding":  {CommonConfig: CommonConfig{URL: "https://in"}, SourceToken: NewSecret("t"), Encoding: "xml"},
		}
		for name, c := range bad {
			if err := c.Validate(); !errors.Is(err, ErrConfig) {
				t.Errorf("%s: %v", name, err)
			}
		}
		if NormalizeBaseURL("  ") != "" || NormalizeBaseURL("h:1/") != "https://h:1" {
			t.Fatal("normalize")
		}
	})

	t.Run("core.redaction.config", func(t *testing.T) {
		cfg := Config{
			CommonConfig:  CommonConfig{URL: "https://h", Headers: map[string]string{"X-Api-Key": "hdr-xyz"}},
			SourceToken:   NewSecret("tok-xyz"),
			QueryURL:      "https://q",
			QueryUsername: "user",
			QueryPassword: NewSecret("pw-xyz"),
			SourceTable:   "t1_x",
			Encoding:      EncodingNDJSON,
		}
		forms := fmt.Sprintf("%v|%+v|%#v|%s", cfg, cfg, cfg, cfg.String())
		if strings.Contains(forms, "tok-xyz") || strings.Contains(forms, "hdr-xyz") || strings.Contains(forms, "pw-xyz") ||
			!strings.Contains(forms, "SourceToken:"+Redacted) || !strings.Contains(forms, "QueryPassword:"+Redacted) {
			t.Fatalf("forms: %s", forms)
		}
	})

	t.Run("invalid config", func(t *testing.T) {
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "mailto:x"}, SourceToken: NewSecret("t")}); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClient: %v", err)
		}
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "https://h", TLS: TLSConfig{CACertPEM: "bad"}}, SourceToken: NewSecret("t")}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad TLS: %v", err)
		}
		if _, err := NewClient(Config{
			CommonConfig: CommonConfig{URL: "https://h"}, SourceToken: NewSecret("t"),
			QueryURL: "https://q", QueryUsername: "u", QueryPassword: NewSecret("p"),
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := NewClient(Config{
			CommonConfig: CommonConfig{URL: "https://h", TLS: TLSConfig{CACertPath: "/nonexistent/ca.pem"}}, SourceToken: NewSecret("t"),
			QueryURL: "https://q", QueryUsername: "u", QueryPassword: NewSecret("p"),
		}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad TLS both: %v", err)
		}
		if _, err := NewClient(Config{
			CommonConfig: CommonConfig{TLS: TLSConfig{CACertPath: "/nonexistent/ca.pem"}},
			QueryURL:     "https://q", QueryUsername: "u", QueryPassword: NewSecret("p"),
		}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad TLS query: %v", err)
		}
	})
}

func TestClientIngestOnly(t *testing.T) {
	fi := &fakeIngest{}
	ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		if r.Method == http.MethodGet {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		fi.handler(w, r, n)
	})

	t.Run("health ping version", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		if hs, err := c.Health(t.Context()); err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["httpStatus"] != "405" || hs.Details["endpoint"] != "ingest" {
			t.Fatalf("health %+v %v", hs, err)
		}
		if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		if evs, _, _ := fi.snapshot(); len(evs) != 0 {
			t.Fatalf("ping stored events: %v", evs)
		}
		if _, err := c.ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("version %v", err)
		}
		if c.Config().URL != ts.URL || c.Query() != nil || c.Exporter() == nil || c.OTLPExporter() == nil {
			t.Fatal("accessors")
		}
	})

	t.Run("authentication errors", func(t *testing.T) {
		bad := newTestClient(t, ts.URL, func(cfg *Config) { cfg.SourceToken = NewSecret("wrong-token") })
		_, err := bad.Ping(t.Context())
		if !errors.Is(err, ErrAuthentication) || strings.Contains(err.Error(), "wrong-token") {
			t.Fatalf("bad token: %v", err)
		}
	})

	t.Run("health unhealthy 5xx", func(t *testing.T) {
		ts500 := newTestServer(t, statusHandler(http.StatusBadGateway))
		c := newTestClient(t, ts500.URL, nil)
		if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unhealthy" {
			t.Fatalf("health %+v %v", hs, err)
		}
	})

	t.Run("health cancelled", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := c.Health(ctx); !errors.Is(err, ErrCancelled) {
			t.Fatalf("health %v", err)
		}
	})

	t.Run("query side is a config error", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		q := &Query{client: c}
		if _, err := q.Query(t.Context(), "SELECT 1", nil); !errors.Is(err, ErrConfig) {
			t.Fatalf("query: %v", err)
		}
		if _, err := q.SearchLogs(t.Context(), SearchLogsOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("search: %v", err)
		}
		if _, err := q.Tail(t.Context(), "x", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("tail: %v", err)
		}
	})

	t.Run("core.close.idempotent", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		first, second := c.Close(), c.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
		if _, err := c.NewLogClient(LogClientOptions{}); !errors.Is(err, ErrCancelled) {
			t.Fatalf("log client after close: %v", err)
		}
	})

	t.Run("core.health.unreachable", func(t *testing.T) {
		c := newTestClient(t, "http://"+closedAddr(t), func(cfg *Config) { cfg.ConnectTimeout = time.Second })
		if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unreachable" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
	})

	t.Run("from env", func(t *testing.T) {
		t.Setenv(EnvPrefix+"URL", ts.URL)
		t.Setenv(EnvPrefix+"SOURCE_TOKEN", testToken)
		c, err := NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		t.Setenv(EnvPrefix+"URL", "mailto:x")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})
}

func TestClientQueryOnly(t *testing.T) {
	srv := newSQLServer(t, func(w http.ResponseWriter, r *http.Request, sql string, _ int) {
		switch {
		case r.URL.Path == "/ping":
			loadFixture(t, "health.query_ping").serve(w)
		case r.Header.Get("Authorization") != basicAuth(testQueryUser, testQueryPassword):
			loadFixture(t, "query.401_bad_password").serve(w)
		case strings.Contains(sql, "WHERE 1 = 0"):
			loadFixture(t, "ping.fallback_count").serve(w)
		default:
			_, _ = w.Write([]byte(`{"ok":1}` + "\n"))
		}
	})

	t.Run("health ping", func(t *testing.T) {
		c := newQueryClient(t, srv.URL, nil)
		if hs, err := c.Health(t.Context()); err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["endpoint"] != "query" {
			t.Fatalf("health %+v %v", hs, err)
		}
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		if got := srv.sql(-1); got != "SELECT count() AS c FROM remote(t1_grow_sdk_logs) WHERE 1 = 0 FORMAT JSONEachRow" {
			t.Fatalf("ping sql %q", got)
		}
		noTable := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.SourceTable = "" })
		if _, err := noTable.Ping(t.Context()); err != nil || srv.sql(-1) != "SELECT 1 AS ok FORMAT JSONEachRow" {
			t.Fatalf("ping no table %v %q", err, srv.sql(-1))
		}
		if c.Config().URL != "" || c.Exporter() != nil || c.Query() == nil {
			t.Fatal("accessors")
		}
	})

	t.Run("credentials stay on their host", func(t *testing.T) {
		c := newQueryClient(t, srv.URL, nil)
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		r := srv.request(int(srv.calls.Load()) - 1)
		if strings.Contains(r.Header.Get("Authorization"), testToken) {
			t.Fatal("source token sent to the query host")
		}
	})

	t.Run("bad password", func(t *testing.T) {
		c := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.QueryPassword = NewSecret("wrong-pass") })
		_, err := c.Ping(t.Context())
		if !errors.Is(err, ErrAuthentication) || strings.Contains(err.Error(), "wrong-pass") {
			t.Fatalf("ping: %v", err)
		}
	})

	t.Run("ingest side is a config error", func(t *testing.T) {
		c := newQueryClient(t, srv.URL, nil)
		if _, err := c.NewLogClient(LogClientOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("log client: %v", err)
		}
		if _, err := c.NewLogClientWith(nil, LogClientOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("log client nil exporter: %v", err)
		}
		e := &IngestExporter{client: c}
		if _, err := e.Export(t.Context(), []LogRecord{{Body: "x"}}, ExportOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("export: %v", err)
		}
		o := &OTLPLogExporter{client: c}
		if _, err := o.Export(t.Context(), []LogRecord{{Body: "x"}}, ExportOptions{}); !errors.Is(err, ErrConfig) {
			t.Fatalf("otlp export: %v", err)
		}
	})
}

func basicAuth(user, pass string) string {
	r := http.Request{Header: http.Header{}}
	r.SetBasicAuth(user, pass)
	return r.Header.Get("Authorization")
}
